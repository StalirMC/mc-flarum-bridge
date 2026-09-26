package cn.stalir.mcbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * How an announcement is presented in game.
 *
 * Several may be active at once, so a server can show the same announcement as
 * a chat line and as a boss bar. The order in the configuration file is the
 * order they are delivered in, which matters for the chat line: it also carries
 * the body and the link, while the other channels show the headline alone.
 */
public enum DisplayChannel {

    /** A normal chat line. The default, and the only channel that is unbounded. */
    CHAT("chat"),

    /** The line just above the hotbar. One line only; extra lines are dropped. */
    ACTION_BAR("actionbar"),

    /** A large centred title, with the announcement body as its subtitle. */
    TITLE("title"),

    /** A bar across the top of the screen, shown for a configured number of seconds. */
    BOSS_BAR("bossbar");

    private final String configName;

    DisplayChannel(String configName) {
        this.configName = configName;
    }

    /** The spelling used in config.yml. */
    public String configName() {
        return configName;
    }

    /**
     * Parse the comma separated {@code game.announce-display} value.
     *
     * Unknown entries are reported through {@code onUnknown} and skipped rather
     * than treated as a configuration problem: a typo here must not be able to
     * leave the bridge unusable, and an announcement still has to be delivered
     * somewhere. When nothing usable is left the caller gets {@link #CHAT}.
     *
     * @param raw       the configured value; null and blank mean {@code chat}
     * @param onUnknown called once per unrecognised entry; may be null
     */
    public static List<DisplayChannel> parse(String raw, Consumer<String> onUnknown) {
        List<DisplayChannel> channels = new ArrayList<>();

        for (String part : (raw == null ? "" : raw).split(",")) {
            String token = part.trim().toLowerCase(Locale.ROOT);

            if (token.isEmpty()) {
                continue;
            }

            DisplayChannel match = byConfigName(token);

            if (match == null) {
                if (onUnknown != null) {
                    onUnknown.accept(token);
                }

                continue;
            }

            // A channel listed twice would otherwise deliver twice.
            if (!channels.contains(match)) {
                channels.add(match);
            }
        }

        if (channels.isEmpty()) {
            channels.add(CHAT);
        }

        return List.copyOf(channels);
    }

    private static DisplayChannel byConfigName(String token) {
        for (DisplayChannel channel : values()) {
            if (channel.configName.equals(token)) {
                return channel;
            }
        }

        return null;
    }
}
