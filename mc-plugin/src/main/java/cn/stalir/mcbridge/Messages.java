package cn.stalir.mcbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders player-facing messages and log lines from lang/&lt;language&gt;.yml.
 *
 * The selected language is bundled in the jar and extracted to the plugin data
 * folder on first use, so server owners can edit it in place. Any key missing
 * from the selected language falls back to {@link #FALLBACK_LANGUAGE}
 * (Simplified Chinese), and then to the key itself.
 *
 * Shared by every platform: the forum, the language selection and the shipped
 * language files are the same whether the plugin runs on Paper, Folia or
 * Velocity.
 */
public final class Messages {

    /** Language used when config.yml does not select one. */
    public static final String DEFAULT_LANGUAGE = "zh_CN";

    /** Language every other language falls back to. */
    public static final String FALLBACK_LANGUAGE = "zh_CN";

    /** Language files shipped inside the jar. */
    private static final String[] SHIPPED = {DEFAULT_LANGUAGE, "en"};

    private static final String FOLDER = "lang";

    private final String language;
    private final Map<String, String> selected;
    private final Map<String, String> fallback;

    private Messages(String language, Map<String, String> selected, Map<String, String> fallback) {
        this.language = language;
        this.selected = selected;
        this.fallback = fallback;
    }

    /**
     * Extract the bundled language files and load the requested one.
     *
     * @param requested value of the {@code language} config key.
     */
    public static Messages load(Platform platform, String requested) {
        Path directory = platform.dataFolder().resolve(FOLDER);

        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            platform.log().warn("Could not create the language directory: " + directory);
        }

        for (String shipped : SHIPPED) {
            // Existing (possibly edited) files are never overwritten.
            platform.saveResource(FOLDER + "/" + shipped + ".yml");
        }

        String language = requested == null ? "" : requested.trim();

        if (!language.matches("[A-Za-z0-9_-]{2,20}")) {
            language = DEFAULT_LANGUAGE;
        }

        Map<String, String> selected = flatten(directory.resolve(language + ".yml"));

        if (selected.isEmpty() && !language.equals(DEFAULT_LANGUAGE)) {
            platform.log().warn("Language file " + FOLDER + "/" + language + ".yml is missing or empty; using " + DEFAULT_LANGUAGE);
            language = DEFAULT_LANGUAGE;
            selected = flatten(directory.resolve(DEFAULT_LANGUAGE + ".yml"));
        }

        Map<String, String> fallback = language.equals(FALLBACK_LANGUAGE)
                ? selected
                : flatten(directory.resolve(FALLBACK_LANGUAGE + ".yml"));

        if (selected.isEmpty()) {
            platform.log().warn("No language file could be loaded from " + directory + "; falling back to key names.");
        }

        return new Messages(language, selected, fallback);
    }

    /** Flatten a language file into "dotted.key" -&gt; "value" pairs. */
    private static Map<String, String> flatten(Path file) {
        Map<String, String> values = Yaml.load(file).flattened();

        return values == null ? new LinkedHashMap<>() : values;
    }

    public String language() {
        return language;
    }

    /** Raw template for a key, before placeholder substitution. */
    public String raw(String key) {
        String value = selected.get(key);

        if (value == null || value.isEmpty()) {
            value = fallback.get(key);
        }

        return value == null ? key : value;
    }

    /** Convert an ampersand colour code string into a component. */
    public Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text == null ? "" : text);
    }

    /** Render a message template, substituting {name} placeholders. */
    public Component render(String key, String... placeholders) {
        return legacy(substitute(raw(key), placeholders));
    }

    /** Render a message and prefix it with the configured plugin prefix. */
    public Component prefixed(String key, String... placeholders) {
        return legacy(raw("prefix")).append(render(key, placeholders));
    }

    /** Render a message as plain text, for console log lines. */
    public String plain(String key, String... placeholders) {
        return PlainTextComponentSerializer.plainText().serialize(render(key, placeholders));
    }

    /**
     * Render a message as a legacy (ampersand) string.
     *
     * Used when several lines have to be assembled before being sent, e.g. the
     * /mcbridge help and stats output.
     */
    public String string(String key, String... placeholders) {
        return substitute(raw(key), placeholders);
    }

    private static String substitute(String template, String... placeholders) {
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            String value = placeholders[index + 1];
            template = template.replace("{" + placeholders[index] + "}", value == null ? "" : value);
        }

        return template;
    }
}
