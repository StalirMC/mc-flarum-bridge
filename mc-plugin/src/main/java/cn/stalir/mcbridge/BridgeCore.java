package cn.stalir.mcbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

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

    /** How many of a player's own reports {@code /report status} lists. */
    private static final int REPORT_STATUS_LIMIT = 5;

    /** Minecraft counts time in ticks of 50 ms. */
    private static final int TICKS_PER_SECOND = 20;

    /**
     * Title timing. {@code game.title-seconds} is the total time on screen, so
     * these are taken out of it; the floor stops a very short setting from
     * producing a title that only flickers.
     */
    private static final int TITLE_FADE_IN_TICKS = 10;
    private static final int TITLE_FADE_OUT_TICKS = 20;
    private static final int TITLE_MIN_STAY_TICKS = 20;

    private final Platform platform;

    // Replaced wholesale on reload and read from async tasks, hence volatile.
    private volatile BridgeConfig config;
    private volatile Messages messages;
    private volatile HttpBridgeClient client;

    private final AtomicLong receivedMessages = new AtomicLong();
    private final AtomicLong outboxFailures = new AtomicLong();

    /**
     * Recent public chat, for the transcript a report carries.
     *
     * Fixed at the largest window the configuration can ask for, so a reload that
     * shortens the window applies at once without discarding what is stored.
     */
    private final ChatLog chatLog = new ChatLog(BridgeConfig.MAX_CHAT_CONTEXT_LINES);

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
            // The version is part of this line on purpose: "which jar is actually
            // loaded" is otherwise unanswerable from the outside, and that question
            // has already cost a round trip once.
            platform.log().info(logText("log.enabled",
                    "platform", platform.id(),
                    "version", Version.VERSION,
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
                    Message feedback = messages.legacy("&a" + title + "&r\n&7" + body);
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

        // What happened to a report, delivered only to the player who filed it.
        // The forum sends the outcome, not a sentence, so the wording comes from
        // this server's own language file.
        if ("report_resolved".equals(type) || "report_rejected".equals(type)) {
            deliverReportOutcome(type, targetUuid, message);
            return;
        }

        Message headline = messages.legacy(tokens(config.announceFormat(), title, body, url, type));
        Message second = Message.of("");

        if (!body.isBlank() && !config.announceBodyFormat().isBlank()) {
            String renderedSecond = tokens(config.announceBodyFormat(), title, body, url, type).trim();

            if (!renderedSecond.isBlank()) {
                second = messages.legacy(renderedSecond);
            }
        }

        deliverAnnouncement(headline, second, url);

        if (receivedMessages.get() <= OUTBOX_BATCH_LOG_LIMIT) {
            platform.log().info(logText("log.relayed", "type", type, "title", title));
        }
    }

    /**
     * Tell the reporter what happened to their report.
     *
     * Kept in the core rather than rendered by the forum for one reason: the
     * wording belongs to this server's language file, which the forum has no
     * access to. The forum therefore sends the outcome and the target only.
     */
    private void deliverReportOutcome(String type, String targetUuid, JsonObject message) {
        if (targetUuid.isBlank()) {
            platform.log().warn(logText("log.report-outcome-no-uuid", "type", type));
            return;
        }

        UUID uuid;

        try {
            uuid = UUID.fromString(targetUuid);
        } catch (IllegalArgumentException exception) {
            platform.log().warn(logText("log.report-outcome-uuid-invalid", "uuid", targetUuid));
            return;
        }

        JsonObject payload = message.has("payload") && message.get("payload").isJsonObject()
                ? message.getAsJsonObject("payload")
                : new JsonObject();

        String target = optString(payload, "target", "?");

        // Both keys are spelled out rather than held in a variable: tools/verify.mjs
        // reads the literal passed to messages.prefixed() to decide which language
        // entries are still in use, so one behind a variable would look unused.
        Message notice = "report_rejected".equals(type)
                ? messages.prefixed("notice-report-rejected", "target", target)
                : messages.prefixed("notice-report-resolved", "target", target);

        String note = optString(payload, "note", "").trim();

        if (!note.isBlank()) {
            notice = notice.append(Message.newline())
                    .append(messages.prefixed("notice-report-note", "note", note));
        }

        platform.sendToPlayer(uuid, notice);
        platform.log().info(logText("log.report-outcome-sent", "uuid", targetUuid, "type", type));
    }

    /** Fill the {title} {body} {url} {type} tokens a format string may carry. */
    private static String tokens(String format, String title, String body, String url, String type) {        return format
                .replace("{title}", title)
                .replace("{body}", body)
                .replace("{url}", url)
                .replace("{type}", type);
    }

    /**
     * Deliver one announcement through every configured channel.
     *
     * The chat line is the only channel that carries everything: an action bar
     * and a boss bar hold a single line, and a title uses the body as its
     * subtitle rather than as a second line.
     */
    private void deliverAnnouncement(Message headline, Message second, String url) {
        // Logged once, whatever the channels are. A console has no use for a boss
        // bar, and an announcement shown only as a title would otherwise leave no
        // trace in the server log at all.
        platform.logToConsole(headline);

        for (DisplayChannel channel : config.announceDisplay()) {
            switch (channel) {
                case CHAT -> platform.broadcast(chatLine(headline, second, url));
                case ACTION_BAR -> platform.showActionBar(headline);
                case TITLE -> platform.showTitle(
                        headline,
                        second,
                        TITLE_FADE_IN_TICKS,
                        titleStayTicks(),
                        TITLE_FADE_OUT_TICKS
                );
                case BOSS_BAR -> platform.showBossBar(headline, config.bossbarSeconds());
            }
        }
    }

    /** The full multi-line form: headline, then the body and the link. */
    private Message chatLine(Message headline, Message second, String url) {
        Message line = headline;

        if (!second.isEmpty()) {
            line = line.append(Message.newline()).append(second);
        }

        if (!url.isBlank()) {
            line = line.append(Message.newline()).append(messages.legacy("&8&o" + url));
        }

        return line;
    }

    /** How long the title stays fully visible, once the fades are taken out. */
    private int titleStayTicks() {
        int total = config.titleSeconds() * TICKS_PER_SECOND;

        return Math.max(TITLE_MIN_STAY_TICKS, total - TITLE_FADE_IN_TICKS - TITLE_FADE_OUT_TICKS);
    }

    // ------------------------------------------------------------------
    // Recent public chat
    // ------------------------------------------------------------------

    /**
     * Record one line of public chat.
     *
     * Called by the platform's chat listener. Only public chat is ever routed
     * here: private messages and commands are not, and the buffer is bounded, so
     * this cannot grow with uptime.
     */
    public void recordChat(String playerName, String text) {
        chatLog.record(playerName, text);
    }

    /** How many lines are held for a player; used by the runtime self test. */
    public int chatLogSize(String playerName) {
        return chatLog.size(playerName);
    }

    /**
     * The reported player's own recent public chat, as a transcript.
     *
     * Deliberately only their lines. A report is about what that player said, and
     * pulling in everyone else's chat would put unrelated players in front of a
     * moderator for no reason.
     */
    private String chatContext(String targetName) {
        int lines = config.reportChatContextLines();

        if (lines <= 0) {
            return "";
        }

        return String.join("\n", chatLog.recent(targetName, lines));
    }

    // ------------------------------------------------------------------
    // Shared command rendering
    // ------------------------------------------------------------------

    /**
     * Ask the forum for a binding code and render the reply.
     *
     * Blocking: call it off the main thread.
     */
    public List<Message> bindMessages(UUID uuid, String playerName) {
        List<Message> reply = new ArrayList<>();

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
    public List<Message> outboxMessages() {
        List<Message> reply = new ArrayList<>();

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
    public List<Message> newsMessages(int limit) {
        List<Message> reply = new ArrayList<>();

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
    public Message broadcastResult(String body, String title) {
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
    public Message reportPlayer(
            UUID reporterUuid,
            String reporterName,
            String targetName,
            String reason,
            String titleTemplate
    ) {
        String title = renderReportTitle(titleTemplate, reporterName, targetName, reason);

        // Read once, before the first attempt: the retry below must send exactly
        // the same report, or the forum could file two discussions whose content
        // disagrees.
        String context = chatContext(targetName);
        logReportContext(targetName, context);

        // One id for both attempts: the forum keys its idempotency on it, so a
        // retry can never file the same report twice.
        String reportUid = UUID.randomUUID().toString();

        try {
            submitReport(reporterUuid, reporterName, targetName, reason, title, reportUid, context);

            return messages.prefixed("report-sent", "target", targetName);
        } catch (BridgeException first) {
            // A status code means the forum answered, so the outcome is known -
            // nothing was filed and retrying would only repeat the same error.
            if (first.statusCode() >= 0) {
                return messages.prefixed("status-unreachable", "reason", first.getMessage());
            }

            // No answer at all, which says nothing about whether the forum did the
            // work: a request that times out client side is very often processed
            // anyway. Retry once with the same id and let idempotency sort it out.
            try {
                submitReport(reporterUuid, reporterName, targetName, reason, title, reportUid, context);

                return messages.prefixed("report-sent", "target", targetName);
            } catch (BridgeException second) {
                // Still nothing. The report may well have been filed by the first
                // attempt, so this must not read as a plain failure: a player who
                // believes it failed simply reports again.
                return messages.prefixed("report-uncertain", "reason", second.getMessage());
            }
        }
    }

    /**
     * The reports this player has filed, as rendered lines.
     *
     * Blocking: call it off the main thread.
     */
    public List<Message> reportStatusMessages(UUID reporterUuid) {
        List<Message> reply = new ArrayList<>();

        JsonObject response;

        try {
            response = client.reportsFor(reporterUuid.toString(), REPORT_STATUS_LIMIT);
        } catch (BridgeException exception) {
            reply.add(messages.prefixed("status-unreachable", "reason", exception.getMessage()));
            return reply;
        }

        JsonElement reports = response.get("reports");

        if (reports == null || !reports.isJsonArray() || reports.getAsJsonArray().isEmpty()) {
            reply.add(messages.prefixed("report-status-empty"));
            return reply;
        }

        JsonArray list = reports.getAsJsonArray();

        reply.add(messages.prefixed("report-status-header", "count", String.valueOf(list.size())));

        for (JsonElement element : list) {
            // Checked before reading: a malformed entry must not cost the player
            // the rest of the list.
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject report = element.getAsJsonObject();

            reply.add(messages.render(
                    "report-status-line",
                    "id", optString(report, "id", "?"),
                    "target", optString(report, "target", "?"),
                    "status", statusLabel(optString(report, "status", "")),
                    "date", shortDate(optString(report, "created_at", ""))
            ));
        }

        return reply;
    }

    /** The localised label for one report status, colour codes included. */
    private String statusLabel(String status) {
        return switch (status) {
            case "resolved" -> messages.raw("report-status-resolved");
            case "rejected" -> messages.raw("report-status-rejected");
            case "pending" -> messages.raw("report-status-pending");
            default -> messages.raw("report-status-unknown");
        };
    }

    /** Trim an ISO timestamp down to "date time", which is all a chat line can hold. */
    private static String shortDate(String iso) {
        if (iso == null || iso.length() < 16) {
            return iso == null ? "" : iso;
        }

        return iso.substring(0, 16).replace('T', ' ');
    }

    private void submitReport(
            UUID reporterUuid,
            String reporterName,
            String targetName,
            String reason,
            String title,
            String reportUid,
            String context
    ) throws BridgeException {
        client.reportPlayer(
                reporterUuid.toString(),
                reporterName,
                targetName,
                reason,
                title,
                config.reportTags(),
                config.reportActor(),
                reportUid,
                context,
                // Whether the transcript feature is on at all. The forum needs this
                // to tell "the feature is off" from "this player had not spoken",
                // which otherwise look exactly the same in the discussion.
                config.reportChatContextLines() > 0
        );
    }

    /**
     * Say what a report is carrying, at INFO, once per report.
     *
     * Without this line the three cases - a transcript, a player who never spoke,
     * and a feature that is switched off - are indistinguishable from the forum,
     * which is precisely the confusion this exists to end.
     */
    private void logReportContext(String targetName, String context) {
        // Each key is written out as the first argument rather than chosen through
        // a conditional: tools/verify.mjs reads the literal passed to logText() to
        // decide which language entries are still in use, so a key behind a ternary
        // looks unused.
        if (!context.isEmpty()) {
            platform.log().info(logText("log.report-context",
                    "target", targetName,
                    "lines", String.valueOf(context.split("\n", -1).length)));

            return;
        }

        if (config.reportChatContextLines() <= 0) {
            platform.log().info(logText("log.report-context-off", "target", targetName));

            return;
        }

        platform.log().info(logText("log.report-context-empty", "target", targetName));
    }

    /**
     * Fill this plugin's own tokens in the report title.
     *
     * PlaceholderAPI {@code %placeholders%} were already expanded in game - they
     * need the live player, so the command does that on the main thread before
     * handing the template over. Only the plain tokens are left here.
     *
     * A template that renders to nothing returns null, which drops the field from
     * the request so the forum renders the title from its own setting rather than
     * posting an untitled discussion.
     */
    private String renderReportTitle(String template, String reporter, String target, String reason) {
        String rendered = template
                .replace("{target}", target)
                .replace("{reporter}", reporter)
                .replace("{reason}", reason)
                .replace("{server}", config.serverName())
                .trim();

        return rendered.isEmpty() ? null : rendered;
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
        lines.add(messages.string("stats-chat-buffer",
                "players", String.valueOf(chatLog.trackedPlayers()),
                "lines", String.valueOf(chatLog.totalLines())));
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
