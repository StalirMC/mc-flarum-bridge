package cn.stalir.mcbridge.velocity;

import cn.stalir.mcbridge.BridgeCore;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;

/**
 * Tells a player who has not linked a forum account how to do it.
 *
 * The join/quit reporting that used to live here was removed together with the
 * gameplay event feature, and a proxy never saw deaths or advancements, so the
 * login prompt is all that is left.
 */
public final class VelocityListener {

    private final McBridgeVelocityPlugin plugin;

    public VelocityListener(McBridgeVelocityPlugin plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        BridgeCore core = activeCore();

        if (core == null) {
            return;
        }

        Player player = event.getPlayer();

        core.promptBindingIfNeeded(player.getUniqueId(), player.getUsername());
    }

    /**
     * The core, or {@code null} while it cannot answer yet.
     *
     * Events can fire before {@code ProxyInitializeEvent} finished loading the
     * configuration, so the core and its configuration are both guarded.
     */
    private BridgeCore activeCore() {
        BridgeCore core = plugin.core();

        if (core == null || core.config() == null) {
            return null;
        }

        return core;
    }
}
