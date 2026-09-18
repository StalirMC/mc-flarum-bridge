package cn.stalir.mcbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.kyori.adventure.text.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Platform-independent heart of the plugin.
 *
 * Everything that decides what is sent to the forum lives here: the heartbeat
 * payload, the gameplay event buffer, the announcement poller and the rendering
 * of every command reply. Paper, Folia and Velocity therefore talk to Flarum
 * with byte-identical requests no matter which jar entry point loaded.
 *
 * Threading: the {@link Platform} implementation decides where tasks run. On
 * Paper/Folia {@link #heartbeatNow()} is scheduled on the main thread because it
 * reads server state, and the HTTP call it starts is handed to
 * {@link Platform#runAsync(Runnable)}. On Velocity everything runs on the proxy
 * scheduler.
 */
public final class BridgeCore {

    /** Above this many relayed messages the per-message log line is dropped. */
    private static final int OUTBOX_BATCH_LOG_LIMIT = 5;

    /** Shutdown must not stall the server when the forum is unreachable. */
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(3);

    private static final long HEARTBEAT_INITIAL_DELAY_MILLIS = 5_000L;
    private static final long OUTBOX_INITIAL_DELAY_MILLIS = 10_000L;
    private static final long FLUSH_INITIAL_DELAY_MILLIS = 8_000L;

    private static final int FLUSH_BATCH_SIZE = 50;

    private final Platform platform;

    // Replaced wholesale on reload and read from async tasks, hence volatile.
    private volatile BridgeConfig config;
    private volatile Messages messages;
    private volatile HttpBridgeClient client;
    private volatile EventQueue eventQueue;

    private final AtomicLong failedHeartbeats = new AtomicLong();
    private final AtomicLong deliveredEvents = new AtomicLong();
    private final AtomicLong receivedMessages = new AtomicLong();
    private final AtomicLong outboxFailures = new AtomicLong();

    public BridgeCore(Platform platform) {
        this.platform = platform;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void start() {
        load();
        schedule();

        if (config.isUsable()) {
            enqueueServerEvent("start", "Server started (" + platform.serverVersion() + ")");
            platform.runAsync(this::flushEvents);

            platform.log().info(logText("log.enabled",
                    "platform", platform.id(),
                    "key", config.serverKey(),
                    "url", config.endpoint("/heartbeat")));
        } else {
            platform.log().warn(logText("log.idle"));
        }
    }

    public void stop() {
        platform.cancelTasks();

        if (client != null && config != null && config.isUsable()) {
            // Building the snapshot is safe here: shutdown runs on the main
            // thread on Paper/Folia and the proxy exposes thread-safe getters.
            // Short timeouts keep a dead forum from delaying the stop.
            try {
                client.heartbeat(buildHeartbeatPayload(false), SHUTDOWN_TIMEOUT);
            } catch (BridgeException exception) {
                platform.log().fine("Could not send the shutdown heartbeat: " + exception.getMessage());
            }

            enqueueServerEvent("stop", "Server stopped");
            flushEvents(SHUTDOWN_TIMEOUT);
        }

        if (client != null) {
            client.close();
        }

        platform.log().info(logText("log.disabled"));
    }

    /** Reload config.yml, keep buffered events and restart the scheduled tasks. */
    public void reload() {
        platform.cancelTasks();
        load();
        schedule();
    }

    private void load() {
        HttpBridgeClient previousClient = this.client;
        EventQueue previousQueue = this.eventQueue;

        // The language is read from the raw config first: it decides how the
        // configuration validation messages and every log line below render.
        Yaml raw = platform.readConfig();

        this.messages = Messages.load(platform, raw.getString("language", Messages.DEFAULT_LANGUAGE));
        this.config = BridgeConfig.from(raw, platform.log(), this.messages);
        this.client = new HttpBridgeClient(this.config);

        EventQueue replacement = new EventQueue(this.config.maxQueuedEvents());

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

    private void schedule() {
        BridgeConfig current = this.config;

        // The heartbeat is captured on the main thread / global region, because
        // it reads server state; only the HTTP call is asynchronous.
        platform.runSyncRepeating(this::heartbeatNow,
                HEARTBEAT_INITIAL_DELAY_MILLIS,
                current.heartbeatIntervalSeconds() * 1000L);

        platform.runAsyncRepeating(this::pollOutbox,
                OUTBOX_INITIAL_DELAY_MILLIS,
                current.outboxPollIntervalSeconds() * 1000L);

        platform.runAsyncRepeating(this::flushEvents,
                FLUSH_INITIAL_DELAY_MILLIS,
                current.eventFlushIntervalSeconds() * 1000L);
    }

    /**
     * Render a message in the configured language.
     *
     * Safe before {@link #messages} is initialised (returns the key), which
     * matters during shutdown.
     */
    public String logText(String key, String... placeholders) {
        Messages current = this.messages;

        return current == null ? key : current.plain(key, placeholders);
    }

    // ------------------------------------------------------------------
    // Heartbeat
    // ------------------------------------------------------------------

    /**
     * Collect the status and transmit it.
     *
     * Must be called on the main thread on Paper/Folia: the payload reads online
     * players, the MOTD and the player limit, none of which are thread-safe.
     */
    public void heartbeatNow() {
        BridgeConfig current = this.config;

        if (current == null || !current.isUsable()) {
            return;
        }

        JsonObject payload = buildHeartbeatPayload(true);

        platform.runAsync(() -> transmitHeartbeat(payload, current.requestTimeout()));
    }

    private void transmitHeartbeat(JsonObject payload, Duration timeout) {
        try {
            client.heartbeat(payload, timeout);
            failedHeartbeats.set(0);
        } catch (BridgeException exception) {
            long failures = failedHeartbeats.incrementAndGet();

            // Log the first failure and then only every tenth one, to avoid spam.
            if (failures == 1 || failures % 10 == 0) {
                platform.log().warn(logText("log.heartbeat-failed",
                        "count", String.valueOf(failures),
                        "reason", exception.getMessage()));
            }
        }
    }

    /** The heartbeat body, identical on every platform. */
    public JsonObject buildHeartbeatPayload(boolean online) {
        JsonObject payload = new JsonObject();
        payload.addProperty("server_key", config.serverKey());
        payload.addProperty("online", online);
        payload.addProperty("name", config.serverName());
        payload.addProperty("version", platform.serverVersion());
        payload.addProperty("motd", platform.motd());

        JsonArray names = new JsonArray();
        int onlineCount = 0;

        if (online) {
            for (String name : platform.playerNames()) {
                names.add(name);
                onlineCount++;
            }
        }

        payload.addProperty("players_online", onlineCount);
        payload.addProperty("players_max", platform.maxPlayers());
        payload.add("player_names", names);

        double tps = platform.tps();

        if (tps >= 0) {
            payload.addProperty("tps", round(tps));
        }

        double mspt = platform.mspt();

        if (mspt >= 0) {
            payload.addProperty("mspt", round(mspt));
        }

        return payload;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    public void enqueuePlayerEvent(String type, UUID uuid, String playerName, String message) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);

        if (uuid != null) {
            event.addProperty("player_uuid", uuid.toString());
        }

        if (playerName != null && !playerName.isBlank()) {
            event.addProperty("player_name", playerName);
        }

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
        flushEvents(config.requestTimeout());
    }

    private void flushEvents(Duration timeout) {
        BridgeConfig current = this.config;

        if (current == null || !current.isUsable()) {
            return;
        }

        EventQueue queue = this.eventQueue;

        if (queue.size() == 0) {
            return;
        }

        List<JsonObject> batch = queue.drain(FLUSH_BATCH_SIZE);

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
            platform.log().warn(logText("log.events-failed",
                    "count", String.valueOf(batch.size()),
                    "reason", exception.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Outbox
    // ------------------------------------------------------------------

    /**
     * Pull pending forum messages and hand each one to the main thread /
     * global region, where broadcasting and command dispatch are safe.
     *
     * Messages are only marked as delivered on the forum when they are returned
     * without {@code peek}, so a crash mid-handling can at worst duplicate a
     * broadcast rather than lose it.
     */
    public void pollOutbox() {
        BridgeConfig current = this.config;

        if (current == null || !current.isUsable()) {
            return;
        }

        JsonObject response;

        try {
            response = client.fetchOutbox(false);
        } catch (BridgeException exception) {
            long failures = outboxFailures.incrementAndGet();

            if (failures == 1 || failures % 10 == 0) {
                platform.log().warn(logText("log.outbox-failed",
                        "count", String.valueOf(failures),
                        "reason", exception.getMessage()));
            }

            return;
        }

        outboxFailures.set(0);

        JsonArray messages = response.has("messages") && response.get("messages").isJsonArray()
                ? response.getAsJsonArray("messages")
                : new JsonArray();

        for (JsonElement element : messages) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject message = element.getAsJsonObject();

            platform.runSync(() -> {
                try {
                    handleOutboxMessage(message);
                } catch (Throwable throwable) {
                    platform.log().error(logText("log.outbox-handle-failed"), throwable);
                }
            });
        }
    }

    /** Handle one message pulled from the forum. */
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

        String rendered = config.announceFormat()
                .replace("{title}", title)
                .replace("{body}", body)
                .replace("{url}", url)
                .replace("{type}", type);

        Component component = messages.legacy(rendered);

        if (!body.isBlank() && !config.announceBodyFormat().isBlank()) {
            String second = config.announceBodyFormat()
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
            platform.log().info(logText("log.relayed", "type", type, "title", title));
        }
    }

    private void handleRemoteCommand(JsonObject message, String fallbackCommand) {
        JsonObject payload = message.has("payload") && message.get("payload").isJsonObject()
                ? message.getAsJsonObject("payload")
                : new JsonObject();

        String command = optString(payload, "command", fallbackCommand);

        if (!config.isRemoteCommandAllowed(command)) {
            platform.log().warn(logText("log.remote-rejected", "command", command));
            return;
        }

        // The proxy has no game console, so it reports the rejection instead.
        if (!platform.dispatchConsoleCommand(command)) {
            platform.log().warn(logText("log.remote-rejected", "command", command));
            return;
        }

        platform.log().info(logText("log.remote-executing", "command", command));
    }

    public void broadcast(Component component) {
        platform.broadcast(component);
        platform.logToConsole(component);
    }

    // ------------------------------------------------------------------
    // Shared command rendering
    // ------------------------------------------------------------------

    /**
     * Ask the forum for a binding code and render the reply.
     *
     * Blocking: call it off the main thread.
     */
    public List<Component> bindMessages(UUID uuid, String playerName) {
        List<Component> reply = new ArrayList<>();

        JsonObject response;

        try {
            response = client.bindStart(uuid, playerName);
        } catch (BridgeException exception) {
            reply.add(messages.prefixed("bind-failed", "reason", exception.getMessage()));
            return reply;
        }

        JsonElement alreadyBound = response.get("already_bound");

        // Check the JSON type before reading: getAsBoolean() on an object or
        // array would throw and abort the whole reply.
        if (alreadyBound != null && alreadyBound.isJsonPrimitive() && alreadyBound.getAsBoolean()) {
            reply.add(messages.prefixed("bind-already", "user", boundUsername(response)));
            return reply;
        }

        if (!response.has("code") || !response.get("code").isJsonPrimitive()) {
            reply.add(messages.prefixed("bind-failed-no-code"));
            return reply;
        }

        JsonElement expiresIn = response.get("expires_in_seconds");

        int minutes = expiresIn != null
                && expiresIn.isJsonPrimitive()
                && expiresIn.getAsJsonPrimitive().isNumber()
                ? Math.max(1, expiresIn.getAsInt() / 60)
                : 10;

        reply.add(messages.prefixed("bind-code",
                "code", response.get("code").getAsString(),
                "minutes", String.valueOf(minutes)));

        reply.add(messages.prefixed("bind-hint",
                "url", config.forumUrl() + "/mc-bridge/link"));

        return reply;
    }

    private String boundUsername(JsonObject response) {
        if (response.has("binding") && response.get("binding").isJsonObject()) {
            JsonObject binding = response.getAsJsonObject("binding");

            if (binding.has("username") && binding.get("username").isJsonPrimitive()) {
                return binding.get("username").getAsString();
            }
        }

        return "?";
    }

    /**
     * Tell a player how to link an account, unless the account is already linked.
     *
     * The lookup runs off-thread and the prompt comes from the language file, so
     * Paper, Folia and Velocity tell the player the same thing. A failed lookup
     * stays silent on purpose: a slow or unreachable forum must not turn into a
     * nagging message on every join.
     */
    public void promptBindingIfNeeded(UUID uuid, String playerName) {
        BridgeConfig current = this.config;

        if (current == null || !current.isUsable() || !current.promptUnbound() || uuid == null) {
            return;
        }

        platform.runAsync(() -> {
            JsonObject response;

            try {
                response = client.bindStatus(uuid);
            } catch (BridgeException exception) {
                platform.log().fine(
                        "Could not check the binding status of " + playerName + ": " + exception.getMessage());
                return;
            }

            if (optBoolean(response, "bound")) {
                return;
            }

            String url = current.forumUrl() + "/mc-bridge/link";
            String pending = optString(response, "pending_code", "");

            platform.sendToPlayer(uuid, pending.isBlank()
                    ? messages.prefixed("bind-prompt", "url", url)
                    : messages.prefixed("bind-prompt-code", "code", pending, "url", url));
        });
    }
    /**
     * Fetch the public status snapshot and render one line about it.
     *
     * Blocking: call it off the main thread.
     */
    public Component statusMessage() {
        JsonObject response;

        try {
            response = client.fetchStatus();
        } catch (BridgeException exception) {
            return messages.prefixed("status-unreachable", "reason", exception.getMessage());
        }

        JsonObject totals = response.has("totals") && response.get("totals").isJsonObject()
                ? response.getAsJsonObject("totals")
                : new JsonObject();

        return messages.prefixed("status-online",
                "servers", optString(totals, "servers", "0"),
                "servers_online", optString(totals, "servers_online", "0"),
                "players", optString(totals, "players_online", "0"));
    }

    /**
     * Peek at the messages waiting on the forum.
     *
     * Blocking: call it off the main thread. Peeking deliberately does not mark
     * anything as delivered, so a manual inspection never eats an announcement.
     */
    public List<Component> outboxMessages() {
        List<Component> reply = new ArrayList<>();

        JsonObject response;

        try {
            response = client.fetchOutbox(true);
        } catch (BridgeException exception) {
            reply.add(messages.prefixed("status-unreachable", "reason", exception.getMessage()));
            return reply;
        }

        JsonArray messages = response.has("messages") && response.get("messages").isJsonArray()
                ? response.getAsJsonArray("messages")
                : new JsonArray();

        if (messages.isEmpty()) {
            reply.add(messages().prefixed("outbox-empty"));
            return reply;
        }

        reply.add(this.messages.prefixed("outbox-header", "count", String.valueOf(messages.size())));

        for (JsonElement element : messages) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject message = element.getAsJsonObject();
            String title = optString(message, "title", "");

            reply.add(this.messages.prefixed("outbox-line",
                    "type", optString(message, "type", "announcement"),
                    "title", title.isBlank() ? this.messages.string("outbox-untitled") : title));
        }

        return reply;
    }

    /**
     * Submit a broadcast to the forum and render the outcome.
     *
     * Blocking: call it off the main thread.
     */
    public Component broadcastResult(String body, String title) {
        try {
            client.broadcast(body, title);
            return messages.prefixed("broadcast-sent");
        } catch (BridgeException exception) {
            return messages.prefixed("status-unreachable", "reason", exception.getMessage());
        }
    }

    /** Local counters, no I/O: safe to build on any thread. */
    public List<String> statsLines() {
        List<String> lines = new ArrayList<>();
        String state = config.isUsable()
                ? messages.string("stats-config-ok")
                : messages.string("stats-config-bad");

        lines.add(messages.string("stats-header"));
        lines.add(messages.string("stats-platform", "platform", platform.id()));
        lines.add(messages.string("stats-server-key", "key", config.serverKey()));
        lines.add(messages.string("stats-forum-url", "url", config.forumUrl()));
        lines.add(messages.string("stats-locale", "locale", messages.language()));
        lines.add(messages.string("stats-queue",
                "queued", String.valueOf(eventQueue.size()),
                "dropped", String.valueOf(eventQueue.droppedCount())));
        lines.add(messages.string("stats-delivered", "delivered", String.valueOf(deliveredEvents.get())));
        lines.add(messages.string("stats-received", "received", String.valueOf(receivedMessages.get())));
        lines.add(messages.string("stats-config", "state", state));

        return lines;
    }

    /** Usage list shared by both command implementations. */
    public List<String> helpLines() {
        return List.of(
                messages.string("help-header"),
                messages.string("help-status"),
                messages.string("help-outbox"),
                messages.string("help-broadcast"),
                messages.string("help-stats"),
                messages.string("help-reload")
        );
    }

    /** Subcommands the admin command accepts, used for the unknown-subcommand reply. */
    public static List<String> subcommands() {
        return List.of("status", "outbox", "broadcast", "stats", "reload");
    }

    private static boolean optBoolean(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return false;
        }

        JsonPrimitive primitive = object.getAsJsonPrimitive(key);

        return primitive.isBoolean() && primitive.getAsBoolean();
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

    public Platform platform() {
        return platform;
    }

    public BridgeConfig config() {
        return config;
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

    public long failedHeartbeats() {
        return failedHeartbeats.get();
    }
}
