package cn.stalir.mcbridge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable view of config.yml, validated once at load time.
 *
 * Validation messages are localised through {@link Messages}, which is built
 * before this class so the language is known.
 */
public final class BridgeConfig {

    public static final int MIN_SECRET_LENGTH = 32;

    /**
     * The largest chat window {@code report.chat-context-lines} may ask for.
     *
     * Also the fixed capacity of {@link ChatLog}, which is why a reload can
     * shorten the window without losing what has already been recorded.
     */
    public static final int MAX_CHAT_CONTEXT_LINES = 50;

    /** Default title of the discussion a player report creates on the forum. */
    public static final String DEFAULT_REPORT_TITLE_FORMAT = "[举报] {target}（由 {reporter} 提交）";

    private final String language;
    private final String forumUrl;
    private final String apiPrefix;
    private final Duration requestTimeout;
    private final String serverKey;
    private final String serverName;
    private final String secret;
    private final int outboxPollIntervalSeconds;
    private final String announceFormat;
    private final String announceBodyFormat;
    private final List<DisplayChannel> announceDisplay;
    private final int titleSeconds;
    private final int bossbarSeconds;
    private final boolean promptUnbound;
    private final String reportTitleFormat;
    private final List<String> reportTags;
    private final String reportActor;
    private final int reportChatContextLines;
    private final List<String> problems;

    private BridgeConfig(
            String language,
            String forumUrl,
            String apiPrefix,
            Duration requestTimeout,
            String serverKey,
            String serverName,
            String secret,
            int outboxPollIntervalSeconds,
            String announceFormat,
            String announceBodyFormat,
            List<DisplayChannel> announceDisplay,
            int titleSeconds,
            int bossbarSeconds,
            boolean promptUnbound,
            String reportTitleFormat,
            List<String> reportTags,
            String reportActor,
            int reportChatContextLines,
            List<String> problems
    ) {
        this.language = language;
        this.forumUrl = forumUrl;
        this.apiPrefix = apiPrefix;
        this.requestTimeout = requestTimeout;
        this.serverKey = serverKey;
        this.serverName = serverName;
        this.secret = secret;
        this.outboxPollIntervalSeconds = outboxPollIntervalSeconds;
        this.announceFormat = announceFormat;
        this.announceBodyFormat = announceBodyFormat;
        this.announceDisplay = announceDisplay;
        this.titleSeconds = titleSeconds;
        this.bossbarSeconds = bossbarSeconds;
        this.promptUnbound = promptUnbound;
        this.reportTitleFormat = reportTitleFormat;
        this.reportTags = reportTags;
        this.reportActor = reportActor;
        this.reportChatContextLines = reportChatContextLines;
        this.problems = problems;
    }

    public static BridgeConfig from(Yaml config, Log log, Messages messages) {
        List<String> problems = new ArrayList<>();

        String language = messages.language();

        String forumUrl = config.getString("forum.url", "").trim();
        while (forumUrl.endsWith("/")) {
            forumUrl = forumUrl.substring(0, forumUrl.length() - 1);
        }
        if (forumUrl.isEmpty()) {
            problems.add(messages.plain("config.problem.url-empty"));
        } else if (!forumUrl.startsWith("http://") && !forumUrl.startsWith("https://")) {
            problems.add(messages.plain("config.problem.url-scheme"));
        }

        String apiPrefix = config.getString("forum.api-prefix", "/api/mc-bridge").trim();
        if (apiPrefix.isEmpty()) {
            apiPrefix = "/api/mc-bridge";
        }
        if (!apiPrefix.startsWith("/")) {
            apiPrefix = "/" + apiPrefix;
        }
        while (apiPrefix.endsWith("/")) {
            apiPrefix = apiPrefix.substring(0, apiPrefix.length() - 1);
        }

        Duration timeout = Duration.ofSeconds(Math.max(1, config.getInt("forum.request-timeout-seconds", 10)));

        String serverKey = config.getString("server.key", "").trim();
        if (!serverKey.matches("[A-Za-z0-9._-]{1,100}")) {
            problems.add(messages.plain("config.problem.server-key"));
        }

        String secret = config.getString("security.secret", "").trim();
        if (secret.isEmpty()) {
            problems.add(messages.plain("config.problem.secret-empty"));
        } else if (secret.length() < MIN_SECRET_LENGTH) {
            problems.add(messages.plain("config.problem.secret-short"));
        }

        int outboxPoll = Math.max(5, config.getInt("sync.outbox-poll-interval-seconds", 20));

        // Report layout. The title template is expanded by the plugin because it
        // may contain PlaceholderAPI placeholders, which only exist server side;
        // tag and actor are hints the forum resolves and validates - a hint it
        // cannot resolve is logged and ignored there rather than failing the
        // player's report.
        String reportTitleFormat = config.getString("report.title-format", DEFAULT_REPORT_TITLE_FORMAT).trim();

        if (reportTitleFormat.isEmpty()) {
            reportTitleFormat = DEFAULT_REPORT_TITLE_FORMAT;
        }

        List<String> reportTagList = splitList(config.getString("report.tags", ""), 100, 10);
        String reportActor = cap(config.getString("report.actor", "").trim(), 64);

        // How an announcement is presented. An unrecognised channel is reported
        // and skipped rather than collected as a configuration problem: a typo
        // here must not be able to leave the whole bridge unusable, and the
        // announcement still has to appear somewhere.
        List<DisplayChannel> announceDisplay = DisplayChannel.parse(
                config.getString("game.announce-display", "chat"),
                token -> log.warn(messages.plain("config.warn.display-unknown", "value", token))
        );

        // Only decide how long a title or a bar stays on screen, so the bounds are
        // generous and anything out of range is pulled in rather than rejected.
        int titleSeconds = clamp(config.getInt("game.title-seconds", 5), 1, 60);
        int bossbarSeconds = clamp(config.getInt("game.bossbar-seconds", 10), 1, 300);

        // How many of the reported player's own recent public chat lines travel
        // with a report. 0 turns the whole thing off.
        int reportChatContextLines = clamp(
                config.getInt("report.chat-context-lines", 10), 0, MAX_CHAT_CONTEXT_LINES);

        BridgeConfig built = new BridgeConfig(
                language,
                forumUrl,
                apiPrefix,
                timeout,
                serverKey,
                config.getString("server.name", serverKey),
                secret,
                outboxPoll,
                config.getString("game.announce-format", "&e[论坛] &f{title}"),
                config.getString("game.announce-body-format", "&7{body}"),
                announceDisplay,
                titleSeconds,
                bossbarSeconds,
                config.getBoolean("game.prompt-unbound", true),
                reportTitleFormat,
                reportTagList,
                reportActor,
                reportChatContextLines,
                Collections.unmodifiableList(problems)
        );

        for (String problem : problems) {
            log.warn(messages.plain("config.problem.summary", "message", problem));
        }

        return built;
    }

    /** Trim a configured value to the length the forum will accept. */
    private static String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Pull a configured number into the range the code can actually honour. */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Split a comma separated config value into at most {@code maxItems} entries.
     *
     * Used for the report tag list. Each entry is resolved by the forum, so one
     * that does not exist there is logged and skipped instead of breaking the
     * report.
     */
    private static List<String> splitList(String raw, int maxLength, int maxItems) {
        List<String> items = new ArrayList<>();

        for (String part : raw.split(",")) {
            String trimmed = part.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            items.add(cap(trimmed, maxLength));

            if (items.size() == maxItems) {
                break;
            }
        }

        return Collections.unmodifiableList(items);
    }

    /** True when the plugin has everything it needs to talk to the forum. */
    public boolean isUsable() {
        return problems.isEmpty();
    }

    public List<String> problems() {
        return problems;
    }

    /** Absolute API path for a suffix such as {@code "/outbox"}. */
    public String apiPath(String suffix) {
        return apiPrefix + suffix;
    }

    public String endpoint(String suffix) {
        return forumUrl + apiPath(suffix);
    }

    public String language() {
        return language;
    }

    public String forumUrl() {
        return forumUrl;
    }

    public String apiPrefix() {
        return apiPrefix;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public String serverKey() {
        return serverKey;
    }

    public String serverName() {
        return serverName;
    }

    public String secret() {
        return secret;
    }

    public int outboxPollIntervalSeconds() {
        return outboxPollIntervalSeconds;
    }

    public String announceFormat() {
        return announceFormat;
    }

    public String announceBodyFormat() {
        return announceBodyFormat;
    }

    /**
     * Every channel an announcement is delivered through, in configured order.
     *
     * Never empty: {@link DisplayChannel#parse} falls back to the chat line.
     */
    public List<DisplayChannel> announceDisplay() {
        return announceDisplay;
    }

    /** How long a title stays on screen. */
    public int titleSeconds() {
        return titleSeconds;
    }

    /** How long a boss bar stays on screen. */
    public int bossbarSeconds() {
        return bossbarSeconds;
    }

    /** Whether an unlinked player is told how to link an account when they join. */
    public boolean promptUnbound() {
        return promptUnbound;
    }

    /**
     * Template for the report discussion title.
     *
     * May contain this plugin's own tokens ({@code {target}} and friends) and
     * PlaceholderAPI {@code %placeholders%}; the latter are expanded in game,
     * before the report is sent.
     */
    public String reportTitleFormat() {
        return reportTitleFormat;
    }

    /** Tag slugs or ids for the report discussion; empty means the forum decides. */
    public List<String> reportTags() {
        return reportTags;
    }

    /** Forum username or id the report is published as; empty means the forum decides. */
    public String reportActor() {
        return reportActor;
    }

    /**
     * How many of the reported player's own recent public chat lines to attach.
     *
     * Zero disables the transcript. Only public chat is ever captured: private
     * messages and commands never reach the buffer this reads from.
     */
    public int reportChatContextLines() {
        return reportChatContextLines;
    }
}
