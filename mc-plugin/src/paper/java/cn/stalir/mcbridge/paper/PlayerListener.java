package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.BridgeConfig;
import cn.stalir.mcbridge.BridgeCore;
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
 * server tick loop. The rendering of the event and the transmission are owned by
 * the shared {@link BridgeCore}, which is why every handler only decides whether
 * the configured switches report this event type.
 *
 * The listener is registered just before the core starts, so the configuration
 * may not be loaded yet when the very first event is dispatched; a handler
 * therefore treats a missing configuration as "report nothing".
 */
public final class PlayerListener implements Listener {

    private final BridgeCore core;

    public PlayerListener(BridgeCore core) {
        this.core = core;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        BridgeConfig config = core.config();

        // Independent of the event switch below: a player who has not linked yet
        // is told how to do it even when join events are not reported.
        core.promptBindingIfNeeded(player.getUniqueId(), player.getName());

        if (config == null || !config.reportJoins()) {
            return;
        }

        core.enqueuePlayerEvent("join", player.getUniqueId(), player.getName(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        BridgeConfig config = core.config();

        if (config == null || !config.reportQuits()) {
            return;
        }

        Player player = event.getPlayer();
        core.enqueuePlayerEvent("quit", player.getUniqueId(), player.getName(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        BridgeConfig config = core.config();

        if (config == null || !config.reportDeaths()) {
            return;
        }

        Player player = event.getEntity();

        String cause = player.getLastDamageCause() == null
                ? "UNKNOWN"
                : player.getLastDamageCause().getCause().name();

        core.enqueuePlayerEvent(
                "death",
                player.getUniqueId(),
                player.getName(),
                "cause=" + cause + " world=" + player.getWorld().getName()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        BridgeConfig config = core.config();

        if (config == null || !config.reportAdvancements()) {
            return;
        }

        String key = event.getAdvancement().getKey().toString();

        // Recipe unlocks fire this event too and are pure noise in a feed.
        if (key.contains("recipes/")) {
            return;
        }

        Player player = event.getPlayer();
        core.enqueuePlayerEvent("advancement", player.getUniqueId(), player.getName(), key);
    }
}
