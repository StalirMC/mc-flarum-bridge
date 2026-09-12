package cn.stalir.mcbridge.listener;

import cn.stalir.mcbridge.McBridgePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Forwards gameplay events to the forum.
 *
 * Everything is queued locally first, so a forum outage never blocks the
 * server tick loop.
 */
public final class PlayerListener implements Listener {

    private final McBridgePlugin plugin;

    public PlayerListener(McBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.config().reportJoins()) {
            return;
        }

        plugin.enqueuePlayerEvent("join", event.getPlayer(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.config().reportQuits()) {
            return;
        }

        plugin.enqueuePlayerEvent("quit", event.getPlayer(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.config().reportDeaths()) {
            return;
        }

        Player player = event.getEntity();

        String cause = player.getLastDamageCause() == null
                ? "UNKNOWN"
                : player.getLastDamageCause().getCause().name();

        plugin.enqueuePlayerEvent(
                "death",
                player,
                "cause=" + cause + " world=" + player.getWorld().getName()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!plugin.config().reportAdvancements()) {
            return;
        }

        String key = event.getAdvancement().getKey().toString();

        // Recipe unlocks fire this event too and are pure noise in a feed.
        if (key.contains("recipes/")) {
            return;
        }

        plugin.enqueuePlayerEvent("advancement", event.getPlayer(), key);
    }
}
