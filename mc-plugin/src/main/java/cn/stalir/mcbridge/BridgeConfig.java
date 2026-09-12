package cn.stalir.mcbridge;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.logging.Logger;

/**
 * Immutable view of config.yml, validated once at load time.
 */
public final class BridgeConfig {

    public static final int MIN_SECRET_LENGTH = 32;

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
    private final boolean allowRemoteCommands;
    private final List<Pattern> remoteCommandWhitelist;
    private final Map<String, String> messages;
    private final List<String> problems;

    private BridgeConfig(
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
            boolean allowRemoteCommands,
            List<Pattern> remoteCommandWhitelist,
            Map<String, String> messages,
            List<String> problems
    ) {
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
        this.allowRemoteCommands = allowRemoteCommands;
        this.remoteCommandWhitelist = remoteCommandWhitelist;
        this.messages = messages;
        this.problems = problems;
    }

    public static BridgeConfig from(FileConfiguration config, Logger logger) {
        List<String> problems = new ArrayList<>();

        String forumUrl = config.getString("forum.url", "").trim();
        while (forumUrl.endsWith("/")) {
            forumUrl = forumUrl.substring(0, forumUrl.length() - 1);
        }
        if (forumUrl.isEmpty()) {
            problems.add("forum.url is empty");
        } else if (!forumUrl.startsWith("http://") && !forumUrl.startsWith("https://")) {
            problems.add("forum.url must start with http:// or https://");
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
            problems.add("server.key must match [A-Za-z0-9._-] and be 1-100 characters long");
        }

        String secret = config.getString("security.secret", "").trim();
        if (secret.isEmpty()) {
            problems.add("security.secret is empty - run \"php flarum mc-bridge:secret\" and paste the value");
        } else if (secret.length() < MIN_SECRET_LENGTH) {
            problems.add("security.secret must be at least " + MIN_SECRET_LENGTH + " characters long");
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
                problems.add("Invalid remote-command-whitelist entry \"" + raw + "\": " + exception.getDescription());
            }
        }

        Map<String, String> messages = new LinkedHashMap<>();

        if (config.isConfigurationSection("messages")) {
            for (String key : config.getConfigurationSection("messages").getKeys(false)) {
                messages.put(key, String.valueOf(config.get("messages." + key, "")));
            }
        }

        BridgeConfig built = new BridgeConfig(
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
                allowRemoteCommands,
                Collections.unmodifiableList(whitelist),
                Collections.unmodifiableMap(messages),
                Collections.unmodifiableList(problems)
        );

        for (String problem : problems) {
            logger.warning("Configuration problem: " + problem);
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

    public boolean allowRemoteCommands() {
        return allowRemoteCommands;
    }

    public List<Pattern> remoteCommandWhitelist() {
        return remoteCommandWhitelist;
    }

    public Map<String, String> messages() {
        return messages;
    }

    /** Look up a message template, falling back to the key itself. */
    public String rawMessage(String key) {
        return messages.getOrDefault(key, key);
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
