package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.BridgeCore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Tells a player who has not linked a forum account how to do it.
 *
 * The join/quit/death/advancement reporting that used to live here was removed
 * together with the gameplay event feature, so the one-off prompt is all that is
 * left. It is independent of any config switch: it is the feature.
 */
public final class PlayerListener implements Listener {

    private final BridgeCore core;

    public PlayerListener(BridgeCore core) {
        this.core = core;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        core.promptBindingIfNeeded(player.getUniqueId(), player.getName());
    }
}
