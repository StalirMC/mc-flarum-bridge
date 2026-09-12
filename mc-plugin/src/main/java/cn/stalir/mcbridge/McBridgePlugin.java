package cn.stalir.mcbridge;

import cn.stalir.mcbridge.command.BindCommand;
import cn.stalir.mcbridge.command.BridgeCommand;
import cn.stalir.mcbridge.listener.PlayerListener;
import cn.stalir.mcbridge.task.HeartbeatTask;
import cn.stalir.mcbridge.task.OutboxTask;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point of the Minecraft side of the bridge.
 *
 * Threading contract
 * ------------------
 * Bukkit state must only be read on the main thread, so the status snapshot is
 * collected by a synchronous task and only the HTTP call is handed to an
 * asynchronous task. Everything else that runs off-thread ({@link EventQueue},
 * Gson serialisation, the HTTP client) is thread-safe by construction.
 *
 * Responsibilities:
 *  - report server status to Flarum on a fixed interval (heartbeat);
 *  - buffer and forward gameplay events (join/quit/death/...);
 *  - poll the forum outbox and relay announcements into the game;
 *  - expose /bind so a player can link their game account to the forum.
 */
public final class McBridgePlugin extends JavaPlugin {

    private static final int OUTBOX_BATCH_LOG_LIMIT = 5;

    /** Shutdown must not stall the server when the forum is unreachable. */
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(3);

    // Replaced wholesale on reload and read from async tasks, hence volatile.
    private volatile BridgeConfig bridgeConfig;
    private volatile Messages messages;
    private volatile HttpBridgeClient client;
    private volatile EventQueue eventQueue;

    private final List<BukkitTask> scheduledTasks = new ArrayList<>();
    private final AtomicLong failedHeartbeats = new AtomicLong();
    private final AtomicLong deliveredEvents = new AtomicLong();
    private final AtomicLong receivedMessages = new AtomicLong();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBridgeConfig();

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        registerCommands();
        scheduleTasks();

        if (bridgeConfig.isUsable()) {
            enqueueServerEvent("start", "Server started (" + getServer().getVersion() + ")");
            flushEventsAsync();
            getLogger().info("McBridge enabled as server '" + bridgeConfig.serverKey() + "' -> " + bridgeConfig.endpoint("/heartbeat"));
        } else {
            getLogger().severe("McBridge is idle until the configuration problems listed above are fixed.");
        }
    }

    @Override
    public void onDisable() {
        cancelTasks();

        if (client != null && bridgeConfig != null && bridgeConfig.isUsable()) {
            // onDisable runs on the main thread, so building the snapshot is safe.
            // Short timeouts keep a dead forum from delaying the server stop.
            try {
                client.heartbeat(buildHeartbeatPayload(false), SHUTDOWN_TIMEOUT);
            } catch (BridgeException exception) {
                getLogger().fine("Could not send the shutdown heartbeat: " + exception.getMessage());
            }

            enqueueServerEvent("stop", "Server stopped");
            flushEvents(SHUTDOWN_TIMEOUT);
        }

        if (client != null) {
            client.close();
        }

        getLogger().info("McBridge disabled.");
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    private void loadBridgeConfig() {
        HttpBridgeClient previousClient = this.client;
        EventQueue previousQueue = this.eventQueue;

        this.bridgeConfig = BridgeConfig.from(getConfig(), getLogger());
        this.messages = new Messages(bridgeConfig);
        this.client = new HttpBridgeClient(bridgeConfig, getLogger());

        EventQueue replacement = new EventQueue(bridgeConfig.maxQueuedEvents());

        // Carry buffered events across a reload instead of dropping them.
        if (previousQueue != null) {
            for (JsonObject pending : previousQueue.drain(previousQueue.size())) {
                replacement.add(pending);
            }
        }

        this.eventQueue = replacement;

        // Closing the old client releases its selector thread and connection pool.
        if (previousClient != null) {
            previousClient.close();
        }
    }

    /** Reload config.yml and restart the scheduled tasks. */
    public void reloadBridge() {
        cancelTasks();
        reloadConfig();
        loadBridgeConfig();
        scheduleTasks();
    }

    private void registerCommands() {
        if (getCommand("bind") != null) {
            getCommand("bind").setExecutor(new BindCommand(this));
        }

        if (getCommand("mcbridge") != null) {
            getCommand("mcbridge").setExecutor(new BridgeCommand(this));
        }
    }

    private void scheduleTasks() {
        long heartbeatTicks = bridgeConfig.heartbeatIntervalSeconds() * 20L;
        long outboxTicks = bridgeConfig.outboxPollIntervalSeconds() * 20L;
        long flushTicks = bridgeConfig.eventFlushIntervalSeconds() * 20L;

        // Heartbeat runs on the MAIN thread so it may safely read Bukkit state;
        // it only dispatches the HTTP call asynchronously.
        scheduledTasks.add(getServer().getScheduler().runTaskTimer(
                this, new HeartbeatTask(this), 20L * 5, heartbeatTicks));

        scheduledTasks.add(getServer().getScheduler().runTaskTimerAsynchronously(
                this, new OutboxTask(this), 20L * 10, outboxTicks));

        scheduledTasks.add(getServer().getScheduler().runTaskTimerAsynchronously(
                this, this::flushEvents, 20L * 8, flushTicks));
    }

    private void cancelTasks() {
        for (BukkitTask task : scheduledTasks) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
                // Already cancelled or the scheduler is shutting down.
            }
        }

        scheduledTasks.clear();
    }

    // ------------------------------------------------------------------
    // Heartbeat
    // ------------------------------------------------------------------

    /**
     * Collect the status on the main thread, then transmit it off-thread.
     *
     * Must be called from the main thread: {@link #buildHeartbeatPayload} reads
     * Bukkit state (online players, MOTD, player limit), which is not
     * thread-safe.
     */
    public void heartbeatAsync() {
        if (!bridgeConfig.isUsable()) {
            return;
        }

        JsonObject payload = buildHeartbeatPayload(true);

        getServer().getScheduler().runTaskAsynchronously(
                this, () -> transmitHeartbeat(payload, bridgeConfig.requestTimeout()));
    }

    private void transmitHeartbeat(JsonObject payload, Duration timeout) {
        try {
            client.heartbeat(payload, timeout);
            failedHeartbeats.set(0);
        } catch (BridgeException exception) {
            long failures = failedHeartbeats.incrementAndGet();

            // Log the first failure and then only every tenth one, to avoid spam.
            if (failures == 1 || failures % 10 == 0) {
                getLogger().warning("Heartbeat failed (" + failures + " in a row): " + exception.getMessage());
            }
        }
    }

    public JsonObject buildHeartbeatPayload(boolean online) {
        JsonObject payload = new JsonObject();
        payload.addProperty("server_key", bridgeConfig.serverKey());
        payload.addProperty("online", online);
        payload.addProperty("name", bridgeConfig.serverName());
        payload.addProperty("version", getServer().getVersion());
        payload.addProperty("motd", motd());

        JsonArray names = new JsonArray();
        int onlineCount = 0;

        if (online) {
            for (Player player : getServer().getOnlinePlayers()) {
                names.add(player.getName());
                onlineCount++;
            }
        }

        payload.addProperty("players_online", onlineCount);
        payload.addProperty("players_max", getServer().getMaxPlayers());
        payload.add("player_names", names);

        double[] tps = readTps();

        if (tps != null && tps.length > 0) {
            payload.addProperty("tps", round(tps[0]));
        }

        double mspt = readMspt();

        if (mspt >= 0) {
            payload.addProperty("mspt", round(mspt));
        }

        return payload;
    }

    @SuppressWarnings("deprecation") // Server#getMotd() is deprecated in favour of motd() but is universally available.
    private String motd() {
        return getServer().getMotd();
    }

    /**
     * Paper exposes {@code Server#getTPS()}; read it reflectively so the plugin
     * also loads on servers that do not implement it.
     */
    private double[] readTps() {
        try {
            Method method = getServer().getClass().getMethod("getTPS");
            Object value = method.invoke(getServer());

            if (value instanceof double[] array) {
                return array;
            }
        } catch (Throwable ignored) {
            // Not available on this server implementation.
        }

        return null;
    }

    private double readMspt() {
        try {
            Method method = getServer().getClass().getMethod("getAverageTickTime");
            Object value = method.invoke(getServer());

            if (value instanceof Number number) {
                return number.doubleValue();
            }
        } catch (Throwable ignored) {
            // Not available on this server implementation.
        }

        return -1;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    public void enqueuePlayerEvent(String type, Player player, String message) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("player_uuid", player.getUniqueId().toString());
        event.addProperty("player_name", player.getName());

        if (message != null && !message.isBlank()) {
            event.addProperty("message", message);
        }

        event.addProperty("happened_at", Instant.now().toString());
        eventQueue.add(event);
    }

    public void enqueueServerEvent(String type, String message) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);

        if (message != null && !message.isBlank()) {
            event.addProperty("message", message);
        }

        event.addProperty("happened_at", Instant.now().toString());
        eventQueue.add(event);
    }

    /** Send buffered events using the configured timeout. Safe from any thread. */
    public void flushEvents() {
        flushEvents(bridgeConfig.requestTimeout());
    }

    private void flushEvents(Duration timeout) {
        if (!bridgeConfig.isUsable()) {
            return;
        }

        EventQueue queue = this.eventQueue;

        if (queue.size() == 0) {
            return;
        }

        List<JsonObject> batch = queue.drain(50);

        if (batch.isEmpty()) {
            return;
        }

        JsonArray array = new JsonArray();
        batch.forEach(array::add);

        try {
            client.sendEvents(array, timeout);
            deliveredEvents.addAndGet(batch.size());
        } catch (BridgeException exception) {
            queue.requeue(batch);
            getLogger().warning("Could not deliver " + batch.size() + " event(s): " + exception.getMessage());
        }
    }

    private void flushEventsAsync() {
        getServer().getScheduler().runTaskAsynchronously(this, this::flushEvents);
    }

    // ------------------------------------------------------------------
    // Outbox
    // ------------------------------------------------------------------

    /** Handle one message pulled from the forum. Must run on the main thread. */
    public void handleOutboxMessage(JsonObject message) {
        receivedMessages.incrementAndGet();

        String type = optString(message, "type", "announcement");
        String title = optString(message, "title", "");
        String body = optString(message, "body", "");
        String url = optString(message, "url", "");

        if ("command".equals(type)) {
            handleRemoteCommand(message, body);
            return;
        }

        String rendered = bridgeConfig.announceFormat()
                .replace("{title}", title)
                .replace("{body}", body)
                .replace("{url}", url)
                .replace("{type}", type);

        Component component = messages.legacy(rendered);

        if (!body.isBlank() && !bridgeConfig.announceBodyFormat().isBlank()) {
            String second = bridgeConfig.announceBodyFormat()
                    .replace("{body}", body)
                    .replace("{title}", title)
                    .replace("{url}", url);

            if (!second.isBlank()) {
                component = component.append(Component.newline()).append(messages.legacy(second));
            }
        }

        if (!url.isBlank()) {
            component = component.append(Component.newline()).append(messages.legacy("&8&o" + url));
        }

        broadcast(component);

        if (receivedMessages.get() <= OUTBOX_BATCH_LOG_LIMIT) {
            getLogger().info("Relayed " + type + " from the forum: " + title);
        }
    }

    private void handleRemoteCommand(JsonObject message, String fallbackCommand) {
        JsonObject payload = message.has("payload") && message.get("payload").isJsonObject()
                ? message.getAsJsonObject("payload")
                : new JsonObject();

        String command = optString(payload, "command", fallbackCommand);

        if (!bridgeConfig.isRemoteCommandAllowed(command)) {
            getLogger().warning("Rejected remote command from the forum (not whitelisted): " + command);
            return;
        }

        getLogger().info("Executing remote command from the forum: " + command);
        getServer().dispatchCommand(getServer().getConsoleSender(), command);
    }

    public void broadcast(Component component) {
        for (Player player : getServer().getOnlinePlayers()) {
            player.sendMessage(component);
        }

        getServer().getConsoleSender().sendMessage(component);
    }

    private static String optString(JsonObject object, String key, String fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive()) {
            return object.get(key).getAsString();
        }

        return fallback;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public BridgeConfig config() {
        return bridgeConfig;
    }

    public Messages messages() {
        return messages;
    }

    public HttpBridgeClient client() {
        return client;
    }

    public EventQueue eventQueue() {
        return eventQueue;
    }

    public long deliveredEvents() {
        return deliveredEvents.get();
    }

    public long receivedMessages() {
        return receivedMessages.get();
    }
}
