package cn.stalir.mcbridge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable view of config.yml, validated once at load time.
 *
 * Validation messages are localised through {@link Messages}, which is built
 * before this class so the language is known.
 */
public final class BridgeConfig {

    public static final int MIN_SECRET_LENGTH = 32;

    private final String language;
    private final String forumUrl;
    private final String apiPrefix;
    private final Duration requestTimeout;
    private final String serverKey;
    private final String serverName;
    private final String secret;
    private final int heartbeatIntervalSeconds;
    private final int outboxPollIntervalSeconds;
    private final int eventFlushIntervalSeconds;
    private final int maxQueuedEvents;
    private final boolean reportJoins;
    private final boolean reportQuits;
    private final boolean reportDeaths;
    private final boolean reportAdvancements;
    private final String announceFormat;
    private final String announceBodyFormat;
    private final boolean promptUnbound;
    private final boolean allowRemoteCommands;
    private final List<Pattern> remoteCommandWhitelist;
    private final List<String> problems;

    private BridgeConfig(
            String language,
            String forumUrl,
            String apiPrefix,
            Duration requestTimeout,
            String serverKey,
            String serverName,
            String secret,
            int heartbeatIntervalSeconds,
            int outboxPollIntervalSeconds,
            int eventFlushIntervalSeconds,
            int maxQueuedEvents,
            boolean reportJoins,
            boolean reportQuits,
            boolean reportDeaths,
            boolean reportAdvancements,
            String announceFormat,
            String announceBodyFormat,
            boolean promptUnbound,
            boolean allowRemoteCommands,
            List<Pattern> remoteCommandWhitelist,
            List<String> problems
    ) {
        this.language = language;
        this.forumUrl = forumUrl;
        this.apiPrefix = apiPrefix;
        this.requestTimeout = requestTimeout;
        this.serverKey = serverKey;
        this.serverName = serverName;
        this.secret = secret;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.outboxPollIntervalSeconds = outboxPollIntervalSeconds;
        this.eventFlushIntervalSeconds = eventFlushIntervalSeconds;
        this.maxQueuedEvents = maxQueuedEvents;
        this.reportJoins = reportJoins;
        this.reportQuits = reportQuits;
        this.reportDeaths = reportDeaths;
        this.reportAdvancements = reportAdvancements;
        this.announceFormat = announceFormat;
        this.announceBodyFormat = announceBodyFormat;
        this.promptUnbound = promptUnbound;
        this.allowRemoteCommands = allowRemoteCommands;
        this.remoteCommandWhitelist = remoteCommandWhitelist;
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

        int heartbeat = Math.max(5, config.getInt("sync.heartbeat-interval-seconds", 30));
        int outboxPoll = Math.max(5, config.getInt("sync.outbox-poll-interval-seconds", 20));
        int eventFlush = Math.max(2, config.getInt("sync.event-flush-interval-seconds", 10));
        int maxQueued = Math.max(10, config.getInt("sync.max-queued-events", 200));

        boolean allowRemoteCommands = config.getBoolean("game.allow-remote-commands", false);
        List<Pattern> whitelist = new ArrayList<>();

        for (String raw : config.getStringList("game.remote-command-whitelist")) {
            if (raw == null || raw.isBlank()) {
                continue;
            }

            try {
                whitelist.add(Pattern.compile(raw));
            } catch (PatternSyntaxException exception) {
                problems.add(messages.plain("config.problem.whitelist",
                        "pattern", raw,
                        "reason", exception.getDescription()));
            }
        }

        BridgeConfig built = new BridgeConfig(
                language,
                forumUrl,
                apiPrefix,
                timeout,
                serverKey,
                config.getString("server.name", serverKey),
                secret,
                heartbeat,
                outboxPoll,
                eventFlush,
                maxQueued,
                config.getBoolean("sync.report-joins", true),
                config.getBoolean("sync.report-quits", true),
                config.getBoolean("sync.report-deaths", true),
                config.getBoolean("sync.report-advancements", false),
                config.getString("game.announce-format", "&e[论坛] &f{title}"),
                config.getString("game.announce-body-format", "&7{body}"),
                config.getBoolean("game.prompt-unbound", true),
                allowRemoteCommands,
                Collections.unmodifiableList(whitelist),
                Collections.unmodifiableList(problems)
        );

        for (String problem : problems) {
            log.warn(messages.plain("config.problem.summary", "message", problem));
        }

        return built;
    }

    /** True when the plugin has everything it needs to talk to the forum. */
    public boolean isUsable() {
        return problems.isEmpty();
    }

    public List<String> problems() {
        return problems;
    }

    /** Absolute API path for a suffix such as {@code "/heartbeat"}. */
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

    public int heartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public int outboxPollIntervalSeconds() {
        return outboxPollIntervalSeconds;
    }

    public int eventFlushIntervalSeconds() {
        return eventFlushIntervalSeconds;
    }

    public int maxQueuedEvents() {
        return maxQueuedEvents;
    }

    public boolean reportJoins() {
        return reportJoins;
    }

    public boolean reportQuits() {
        return reportQuits;
    }

    public boolean reportDeaths() {
        return reportDeaths;
    }

    public boolean reportAdvancements() {
        return reportAdvancements;
    }

    public String announceFormat() {
        return announceFormat;
    }

    public String announceBodyFormat() {
        return announceBodyFormat;
    }

    /** Whether an unlinked player is told how to link an account when they join. */
    public boolean promptUnbound() {
        return promptUnbound;
    }

    public boolean allowRemoteCommands() {
        return allowRemoteCommands;
    }

    public List<Pattern> remoteCommandWhitelist() {
        return remoteCommandWhitelist;
    }

    /** True when a command pushed from the forum is allowed to run. */
    public boolean isRemoteCommandAllowed(String command) {
        if (!allowRemoteCommands || command == null || command.isBlank()) {
            return false;
        }

        String trimmed = command.trim();

        for (Pattern pattern : remoteCommandWhitelist) {
            if (pattern.matcher(trimmed).matches()) {
                return true;
            }
        }

        return false;
    }
}
