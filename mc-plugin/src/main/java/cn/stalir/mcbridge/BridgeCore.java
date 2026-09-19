package cn.stalir.mcbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Platform-independent heart of the plugin.
 *
 * Everything that talks to the forum lives here: the announcement poller, the
 * broadcast submission, the account binding and the rendering of every command
 * reply. Paper, Folia and Velocity therefore talk to Flarum with byte-identical
 * requests no matter which jar entry point loaded.
 *
 * The plugin only ever <em>receives</em> from the forum. Reporting the server
 * status (heartbeat) and gameplay events were removed at the maintainer's
 * request, so nothing is pushed except the binding a player asks for.
 *
 * Threading: the {@link Platform} implementation decides where tasks run. The
 * outbox poller is asynchronous and hands each message to
 * {@link Platform#runSync(Runnable)}, because broadcasting and command dispatch
 * belong on the main thread / global region.
 */
public final class BridgeCore {

    /** Above this many relayed messages the per-message log line is dropped. */
    private static final int OUTBOX_BATCH_LOG_LIMIT = 5;

    private static final long OUTBOX_INITIAL_DELAY_MILLIS = 10_000L;

    private final Platform platform;

    // Replaced wholesale on reload and read from async tasks, hence volatile.
    private volatile BridgeConfig config;
    private volatile Messages messages;
    private volatile HttpBridgeClient client;

    private final AtomicLong receivedMessages = new AtomicLong();
    private final AtomicLong outboxFailures = new AtomicLong();

    /** Id of the activity poll currently announced in game (0 = none). */
    private volatile long activeActivityId = 0L;

    /** Id of the last poll whose results were announced in game (0 = none). */
    private volatile long announcedResultsId = 0L;

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
            platform.log().info(logText("log.enabled",
                    "platform", platform.id(),
                    "key", config.serverKey(),
                    "url", config.endpoint("/outbox")));
        } else {
            platform.log().warn(logText("log.idle"));
        }
    }

    public void stop() {
        platform.cancelTasks();

        if (client != null) {
            client.close();
        }

        platform.log().info(logText("log.disabled"));
    }

    /** Reload config.yml and restart the scheduled tasks. */
    public void reload() {
        platform.cancelTasks();
        load();
        schedule();
    }

    private void load() {
        HttpBridgeClient previousClient = this.client;

        // The language is read from the raw config first: it decides how the
        // configuration validation messages and every log line below render.
        Yaml raw = platform.readConfig();

        this.messages = Messages.load(platform, raw.getString("language", Messages.DEFAULT_LANGUAGE));
        this.config = BridgeConfig.from(raw, platform.log(), this.messages);
        this.client = new HttpBridgeClient(this.config);

        // Closing the old client releases its selector thread and connection pool.
        if (previousClient != null) {
            previousClient.close();
        }
    }

    private void schedule() {
        BridgeConfig current = this.config;

        platform.runAsyncRepeating(this::pollOutbox,
                OUTBOX_INITIAL_DELAY_MILLIS,
                current.outboxPollIntervalSeconds() * 1000L);
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
    // Outbox
    // ------------------------------------------------------------------

    /**
     * Pull pending forum messages and hand each one to the main thread / global
     * region, where broadcasting is safe.
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

        // The same poll cycle also picks up activity polls and their results.
        pollActivity();
    }

    /**
     * Ask the forum for the current activity poll (if any) and announce new
     * polls or their final results. Runs on the poller thread; broadcasting is
     * handed back to the main thread / global region.
     */
    private void pollActivity() {
        BridgeConfig current = this.config;

        if (current == null || !current.isUsable()) {
            return;
        }

        JsonObject response;

        try {
            response = client.fetchActivity();
        } catch (BridgeException exception) {
            // Activity polling is best-effort; a failure here must not break
            // the outbox cycle that already ran above.
            return;
        }

        JsonObject results = response.has("results") && response.get("results").isJsonObject()
                ? response.getAsJsonObject("results")
                : null;

        JsonObject open = response.has("open") && response.get("open").isJsonObject()
                ? response.getAsJsonObject("open")
                : null;

        platform.runSync(() -> {
            // A poll has just closed: announce the final tally, but only once.
            if (results != null) {
                long resultsId = optLong(results, "id", 0L);

                if (resultsId > 0L && resultsId != announcedResultsId) {
                    announcedResultsId = resultsId;
                    broadcast(renderActivityResults(results));
                }

                // The results replace whatever was open before.
                if (activeActivityId == resultsId) {
                    activeActivityId = 0L;
                }
            }

            // A new poll is open: announce it once, then remember it for /vote.
            if (open != null) {
                long openId = optLong(open, "id", 0L);

                if (openId > 0L && openId != activeActivityId && openId != announcedResultsId) {
                    activeActivityId = openId;
                    broadcast(renderActivityOpen(open));
                }
            }
        });
    }

    /** Handle one message pulled from the forum. */
    public void handleOutboxMessage(JsonObject message) {
        receivedMessages.incrementAndGet();

        String type = optString(message, "type", "announcement");
        String title = optString(message, "title", "");
        String body = optString(message, "body", "");
        String url = optString(message, "url", "");
        String targetUuid = optString(message, "target_uuid", "");

        // Instant feedback for binding: deliver only to the player who bound.
        if ("bind_success".equals(type) || "bind_unlinked".equals(type)) {
            if (!targetUuid.isBlank()) {
                try {
                    UUID uuid = UUID.fromString(targetUuid);
                    Component feedback = messages.legacy("&a" + title + "&r\n&7" + body);
                    platform.sendToPlayer(uuid, feedback);
                    platform.log().info(logText("log.bind-feedback-sent", "uuid", targetUuid, "type", type));
                } catch (IllegalArgumentException exception) {
                    platform.log().warn(logText("log.bind-feedback-uuid-invalid", "uuid", targetUuid));
                }
            } else {
                platform.log().warn(logText("log.bind-feedback-no-uuid", "type", type));
            }
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
            reply.add(this.messages.prefixed("outbox-empty"));
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
     * Fetch the latest announcements for in-game display.
     *
     * Unlike {@link #outboxMessages()}, this filters to announcement type only
     * and limits the count, suitable for showing to regular players.
     *
     * Blocking: call it off the main thread.
     */
    public List<Component> newsMessages(int limit) {
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

        // Filter to announcements only and apply limit.
        List<JsonObject> announcements = new ArrayList<>();
        for (JsonElement element : messages) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject message = element.getAsJsonObject();
            if ("announcement".equals(optString(message, "type", ""))) {
                announcements.add(message);
                if (announcements.size() >= limit) {
                    break;
                }
            }
        }

        if (announcements.isEmpty()) {
            reply.add(this.messages.prefixed("news-empty"));
            return reply;
        }

        reply.add(this.messages.prefixed("news-header", "count", String.valueOf(announcements.size())));

        for (JsonObject message : announcements) {
            String title = optString(message, "title", "");
            String body = optString(message, "body", "");

            if (!title.isBlank()) {
                reply.add(this.messages.legacy("&e" + title));
            }
            if (!body.isBlank()) {
                reply.add(this.messages.legacy("&7" + body));
            }
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

    /**
     * Report a player to the forum.
     *
     * Blocking: call it off the main thread.
     */
    public Component reportPlayer(UUID reporterUuid, String reporterName, String targetName, String reason) {
        try {
            client.reportPlayer(reporterUuid.toString(), reporterName, targetName, reason);
            return messages.prefixed("report-sent", "target", targetName);
        } catch (BridgeException exception) {
            return messages.prefixed("status-unreachable", "reason", exception.getMessage());
        }
    }

    /**
     * Submit a vote in the current activity poll and render the outcome.
     *
     * Blocking: call it off the main thread. Returns the reply component; when
     * the poll is unknown to the forum the forum's translated error is shown.
     */
    public Component voteResult(long activityId, UUID playerUuid, String playerName, int optionIndex) {
        try {
            JsonObject response = client.submitVote((int) activityId, playerUuid, playerName, optionIndex);

            JsonElement total = response.get("total_votes");
            int totalVotes = total != null && total.isJsonPrimitive() && total.getAsJsonPrimitive().isNumber()
                    ? total.getAsInt()
                    : 0;

            return messages.prefixed("vote-sent",
                    "option", String.valueOf(optionIndex + 1),
                    "total", String.valueOf(totalVotes));
        } catch (BridgeException exception) {
            return messages.prefixed("vote-failed", "reason", exception.getMessage());
        }
    }

    /** The id of the poll currently announced in game (0 = none). */
    public long activeActivity() {
        return activeActivityId;
    }

    // ------------------------------------------------------------------
    // Activity rendering
    // ------------------------------------------------------------------

    private Component renderActivityOpen(JsonObject activity) {
        String title = optString(activity, "title", "");
        List<String> options = new ArrayList<>();

        if (activity.has("options") && activity.get("options").isJsonArray()) {
            int index = 1;

            for (JsonElement option : activity.getAsJsonArray("options")) {
                if (option.isJsonPrimitive() && option.getAsJsonPrimitive().isString()) {
                    options.add("&e" + index + " &7" + option.getAsString());
                    index++;
                }
            }
        }

        Component header = messages.prefixed("activity-open", "title", title.isBlank() ? "?" : title);

        if (options.isEmpty()) {
            return header;
        }

        Component component = header.append(Component.newline()).append(messages.legacy(String.join("\n", options)));
        component = component.append(Component.newline()).append(messages.prefixed("activity-vote-hint"));

        return component;
    }

    private Component renderActivityResults(JsonObject activity) {
        String title = optString(activity, "title", "");
        List<String> lines = new ArrayList<>();

        if (activity.has("options") && activity.get("options").isJsonArray()
                && activity.has("tally") && activity.get("tally").isJsonArray()) {
            JsonArray options = activity.getAsJsonArray("options");
            JsonArray tally = activity.getAsJsonArray("tally");
            int total = 0;

            for (JsonElement element : tally) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                    total += element.getAsInt();
                }
            }

            for (int i = 0; i < options.size(); i++) {
                JsonElement option = options.get(i);

                if (!option.isJsonPrimitive() || !option.getAsJsonPrimitive().isString()) {
                    continue;
                }

                int count = i < tally.size() && tally.get(i).isJsonPrimitive()
                        ? tally.get(i).getAsInt()
                        : 0;

                int percent = total > 0 ? (count * 100) / total : 0;

                lines.add(messages.plain("activity-result-line",
                        "option", option.getAsString(),
                        "count", String.valueOf(count),
                        "percent", String.valueOf(percent)));
            }
        }

        JsonElement winner = activity.get("winner");
        String winnerOption = "";

        if (winner != null && winner.isJsonPrimitive() && winner.getAsJsonPrimitive().isNumber()
                && activity.has("options") && activity.get("options").isJsonArray()) {
            int winnerIndex = winner.getAsInt();
            JsonArray options = activity.getAsJsonArray("options");

            if (winnerIndex >= 0 && winnerIndex < options.size()
                    && options.get(winnerIndex).isJsonPrimitive()) {
                winnerOption = options.get(winnerIndex).getAsString();
            }
        }

        Component component = messages.prefixed("activity-results-header", "title", title.isBlank() ? "?" : title);

        if (!lines.isEmpty()) {
            component = component.append(Component.newline())
                    .append(messages.legacy(String.join("\n", lines)));
        }

        if (!winnerOption.isEmpty()) {
            component = component.append(Component.newline())
                    .append(messages.prefixed("activity-winner", "option", winnerOption));
        }

        return component;
    }

    private static long optLong(JsonObject object, String key, long fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isNumber()) {
            return fallback;
        }

        return object.get(key).getAsLong();
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
        lines.add(messages.string("stats-received", "received", String.valueOf(receivedMessages.get())));
        lines.add(messages.string("stats-config", "state", state));

        return lines;
    }

    /** Usage list shared by both command implementations. */
    public List<String> helpLines() {
        return List.of(
                messages.string("help-header"),
                messages.string("help-news"),
                messages.string("help-outbox"),
                messages.string("help-broadcast"),
                messages.string("help-stats"),
                messages.string("help-reload")
        );
    }

    /** Subcommands the admin command accepts, used for the unknown-subcommand reply. */
    public static List<String> subcommands() {
        return List.of("news", "outbox", "broadcast", "stats", "reload");
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

    public long receivedMessages() {
        return receivedMessages.get();
    }
}
