package cn.stalir.mcbridge.paper;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The one class in this jar that mentions PlaceholderAPI.
 *
 * It is deliberately on its own. PlaceholderAPI is optional, and the JVM loads a
 * class only when something actually uses it, so isolating the reference here
 * means a server without PlaceholderAPI never loads this class and the missing
 * plugin can never surface as a NoClassDefFoundError. Calling PlaceholderAPI
 * straight from {@link ReportCommand} would put that reference on a class the
 * plugin always loads.
 *
 * Only the paper module may use this: the Velocity proxy has no PlaceholderAPI,
 * and the shared core has to stay free of platform references.
 */
final class PlaceholderApiHook {

    private PlaceholderApiHook() {
    }

    /** Whether PlaceholderAPI is installed and enabled on this server. */
    static boolean available() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");

        return plugin != null && plugin.isEnabled();
    }

    /**
     * Expand {@code %placeholders%} in a string for one player.
     *
     * Call it on the thread that owns the player: expansions are ordinary plugin
     * code with no threading guarantees. A misbehaving expansion leaves the text
     * untouched rather than costing the player their report.
     */
    static String resolve(Player player, String text) {
        try {
            return PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable throwable) {
            return text;
        }
    }
}
