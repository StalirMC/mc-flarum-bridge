package cn.stalir.mcbridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime self test for the shared core, executed by the {@code selfTest} Gradle
 * task on a real JVM.
 *
 * Compilation alone cannot prove that a hand written YAML reader, the language
 * pipeline and the configuration validation behave correctly, and the shared
 * core is exactly the part that neither CI's static checks nor the mock forum
 * can execute. Everything here runs without a Minecraft server: it reads the
 * files that ship inside the jar and drives the same code paths the plugin uses
 * at startup.
 *
 * Exits with a non-zero status as soon as one assertion fails, so `gradle build`
 * fails with it.
 */
public final class SelfTest {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks = 0;

    private SelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path resources = Path.of("src/main/resources");

        if (!Files.isDirectory(resources)) {
            System.err.println("run this from the mc-plugin directory (src/main/resources not found)");
            System.exit(2);
        }

        testConfigParsing(resources);
        testLanguageFiles(resources);
        testConfigurationValidation(resources);
        testValidConfiguration(resources);

        System.out.println();
        System.out.println("checks run: " + checks);
        System.out.println("failures:   " + FAILURES.size());

        if (!FAILURES.isEmpty()) {
            for (String failure : FAILURES) {
                System.out.println("  FAIL " + failure);
            }

            System.exit(1);
        }

        System.out.println("SHARED CORE SELF TEST PASSED");
    }

    // ------------------------------------------------------------------
    // 1. YAML parsing of the shipped config.yml
    // ------------------------------------------------------------------

    private static void testConfigParsing(Path resources) throws Exception {
        String text = Files.readString(resources.resolve("config.yml"), StandardCharsets.UTF_8);
        Yaml config = Yaml.parse(text);

        section("1. config.yml parsing");

        check("forum.url", "https://forum.kxkl2024.cn", config.getString("forum.url", ""));
        check("forum.api-prefix", "/api/mc-bridge", config.getString("forum.api-prefix", ""));
        check("forum.request-timeout-seconds", 10, config.getInt("forum.request-timeout-seconds", 0));
        check("server.key", "survival", config.getString("server.key", ""));
        check("server.name keeps non-ASCII", "Stalir 生存服", config.getString("server.name", ""));
        check("security.secret parses as empty", "", config.getString("security.secret", "missing"));
        check("sync.outbox-poll-interval-seconds", 20, config.getInt("sync.outbox-poll-interval-seconds", 0));
        check("game.announce-format", "&e[论坛] &f{title}", config.getString("game.announce-format", ""));

        // Unknown keys must come back as the caller's fallback, never as null.
        check("missing key falls back", "fallback", config.getString("nope.nothing", "fallback"));
        check("scalar used as a section falls back", "fallback", config.getString("forum.url.nested", "fallback"));

        // Comments must not leak into values.
        check("comment is stripped", "20", String.valueOf(config.getInt("sync.outbox-poll-interval-seconds", 0)));
    }

    // ------------------------------------------------------------------
    // 2. Language files
    // ------------------------------------------------------------------

    private static void testLanguageFiles(Path resources) throws Exception {
        section("2. language files");

        Yaml zh = Yaml.load(resources.resolve("lang/zh_CN.yml"));
        Yaml en = Yaml.load(resources.resolve("lang/en.yml"));

        Set<String> zhKeys = new LinkedHashSet<>(zh.flattened().keySet());
        Set<String> enKeys = new LinkedHashSet<>(en.flattened().keySet());

        // A floor, not an exact count: it catches a file that failed to parse or
        // was truncated, while leaving room to add and remove keys freely.
        check("zh_CN has a usable key count (> 40)", true, zhKeys.size() > 40);
        check("en has a usable key count (> 40)", true, enKeys.size() > 40);
        check("zh_CN and en define the same keys", zhKeys, enKeys);

        check("prefix present in zh_CN", true, zh.getString("prefix", "").startsWith("&8"));
        check("nested log.enabled present", true, !zh.getString("log.enabled", "").isBlank());
        check("new stats-platform key present", true, !zh.getString("stats-platform", "").isBlank());
        check("new stats-platform key present in en", true, !en.getString("stats-platform", "").isBlank());

        // en.yml escapes double quotes inside a double-quoted scalar; the reader
        // must unescape them and must not treat the escaped quote as a delimiter.
        String escaped = en.getString("config.problem.secret-empty", "");

        check("escaped quotes are unescaped", true, escaped.contains("\"php flarum mc-bridge:secret\""));
        check("escaped quotes keep the tail", true, escaped.endsWith("paste the value"));
    }

    // ------------------------------------------------------------------
    // 3. Configuration validation on the shipped (incomplete) config
    // ------------------------------------------------------------------

    private static void testConfigurationValidation(Path resources) throws Exception {
        section("3. configuration validation");

        StubPlatform platform = new StubPlatform(resources);
        Yaml raw = platform.readConfig();

        Messages messages = Messages.load(platform, raw.getString("language", Messages.DEFAULT_LANGUAGE));
        BridgeConfig config = BridgeConfig.from(raw, platform.log(), messages);

        check("shipped config is not usable (empty secret)", false, config.isUsable());
        check("language defaults to zh_CN", "zh_CN", messages.language());

        String problems = String.join(" | ", config.problems());
        check("the missing secret is reported", true, problems.contains("security.secret"));

        // Log lines must be rendered, not returned as raw keys.
        String idle = messages.plain("log.idle");
        check("log.idle is localised", true, !idle.equals("log.idle") && !idle.isBlank());

        String enabled = messages.plain("log.enabled",
                "platform", "paper", "key", "survival", "url", "https://example.test");
        check("log.enabled interpolates the platform", true, enabled.contains("paper"));
        check("log.enabled interpolates the key", true, enabled.contains("survival"));
        check("log.enabled leaves no placeholder behind", false, enabled.contains("{"));

        check("apiPath", "/api/mc-bridge/outbox", config.apiPath("/outbox"));
        check("endpoint", "https://forum.kxkl2024.cn/api/mc-bridge/outbox", config.endpoint("/outbox"));
    }

    // ------------------------------------------------------------------
    // 4. A complete configuration behaves
    // ------------------------------------------------------------------

    private static void testValidConfiguration(Path resources) throws Exception {
        section("4. complete configuration");

        StubPlatform platform = new StubPlatform(resources);
        String text = Files.readString(resources.resolve("config.yml"), StandardCharsets.UTF_8);
        String secret = "0123456789abcdef0123456789abcdef";
        Yaml raw = Yaml.parse(text.replace("secret: \"\"", "secret: \"" + secret + "\""));

        check("the test secret was substituted", secret, raw.getString("security.secret", ""));

        Messages messages = Messages.load(platform, raw.getString("language", Messages.DEFAULT_LANGUAGE));
        BridgeConfig config = BridgeConfig.from(raw, platform.log(), messages);

        check("config is usable", true, config.isUsable());
        check("secret is read", secret, config.secret());
        check("server key is read", "survival", config.serverKey());
        check("outbox poll interval", 20, config.outboxPollIntervalSeconds());
        check("announce body format", "&7{body}", config.announceBodyFormat());

        // A too short secret must be reported.
        Yaml shortSecret = Yaml.parse(text.replace("secret: \"\"", "secret: \"tooshort\""));
        BridgeConfig broken = BridgeConfig.from(shortSecret, platform.log(), messages);
        check("a short secret makes the config unusable", false, broken.isUsable());

        // A URL without a scheme must be reported as well.
        Yaml badUrl = Yaml.parse(text
                .replace("secret: \"\"", "secret: \"" + secret + "\"")
                .replace("url: \"https://forum.kxkl2024.cn\"", "url: \"forum.kxkl2024.cn\""));
        BridgeConfig noScheme = BridgeConfig.from(badUrl, platform.log(), messages);
        check("a URL without a scheme is rejected", false, noScheme.isUsable());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    private static void check(String label, Object expected, Object actual) {
        checks++;

        boolean ok = expected == null ? actual == null : expected.equals(actual);

        if (ok) {
            System.out.println("  ok   " + label);
            return;
        }

        FAILURES.add(label + ": expected <" + expected + "> but was <" + actual + ">");
        System.out.println("  FAIL " + label + ": expected <" + expected + "> but was <" + actual + ">");
    }

    /**
     * Minimal {@link Platform} for the test: resources are read from the source
     * tree instead of the jar, and everything that needs a running server is a
     * no-op or a fixed value.
     */
    private static final class StubPlatform implements Platform {

        private final Path resources;
        private final TestLog log = new TestLog();

        StubPlatform(Path resources) {
            this.resources = resources;
        }

        @Override
        public String id() {
            return "selftest";
        }

        @Override
        public Log log() {
            return log;
        }

        @Override
        public Path dataFolder() {
            return resources;
        }

        @Override
        public void saveResource(String resourcePath) {
            // The source tree already contains the file.
        }

        @Override
        public Yaml readConfig() {
            return Yaml.load(resources.resolve("config.yml"));
        }

        @Override
        public void runAsync(Runnable task) {
        }

        @Override
        public void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
        }

        @Override
        public void runSyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
        }

        @Override
        public void runSync(Runnable task) {
        }

        @Override
        public void cancelTasks() {
        }


        @Override
        public void broadcast(net.kyori.adventure.text.Component message) {
        }

        @Override
        public void sendToPlayer(java.util.UUID uuid, net.kyori.adventure.text.Component message) {
        }

        @Override
        public void logToConsole(net.kyori.adventure.text.Component message) {
        }

    }

    /** Captures log lines so a crash inside the core would surface in the output. */
    private static final class TestLog implements Log {

        @Override
        public void info(String message) {
            System.out.println("  [info] " + message);
        }

        @Override
        public void warn(String message) {
            System.out.println("  [warn] " + message);
        }

        @Override
        public void fine(String message) {
        }

        @Override
        public void error(String message, Throwable throwable) {
            System.out.println("  [error] " + message + " -> " + throwable);
        }
    }
}
