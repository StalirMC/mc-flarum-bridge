package cn.stalir.mcbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Renders the configurable {@code messages} section into Adventure components.
 */
public final class Messages {

    private final BridgeConfig config;

    public Messages(BridgeConfig config) {
        this.config = config;
    }

    /** Convert an ampersand colour code string into a component. */
    public Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text == null ? "" : text);
    }

    /** Render a message template, substituting {name} placeholders. */
    public Component render(String key, String... placeholders) {
        String template = config.rawMessage(key);

        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            String value = placeholders[index + 1];
            template = template.replace("{" + placeholders[index] + "}", value == null ? "" : value);
        }

        return legacy(template);
    }

    /** Render a message and prefix it with the configured plugin prefix. */
    public Component prefixed(String key, String... placeholders) {
        return legacy(config.rawMessage("prefix")).append(render(key, placeholders));
    }
}
