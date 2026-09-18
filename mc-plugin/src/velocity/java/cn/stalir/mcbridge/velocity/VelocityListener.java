package cn.stalir.mcbridge.velocity;

import cn.stalir.mcbridge.BridgeCore;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;

/**
 * Forwards proxy logins and disconnects to the shared core.
 *
 * A proxy only sees connections: deaths and advancements happen on the backend
 * servers, where the Paper/Folia entry point reports them, so no such event is
 * invented here.
 */
public final class VelocityListener {

    private final McBridgeVelocityPlugin plugin;

    public VelocityListener(McBridgeVelocityPlugin plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        BridgeCore core = activeCore();

        if (core == null || !core.config().reportJoins()) {
            return;
        }

        Player player = event.getPlayer();

        core.enqueuePlayerEvent("join", player.getUniqueId(), player.getUsername(), null);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        BridgeCore core = activeCore();

        if (core == null || !core.config().reportQuits()) {
            return;
        }

        Player player = event.getPlayer();

        core.enqueuePlayerEvent("quit", player.getUniqueId(), player.getUsername(), null);
    }

    /**
     * The core, or {@code null} while it cannot buffer events yet.
     *
     * Events can fire before {@code ProxyInitializeEvent} finished loading the
     * configuration, and the event queue only exists once the configuration is
     * known, so both are guarded.
     */
    private BridgeCore activeCore() {
        BridgeCore core = plugin.core();

        if (core == null || core.config() == null || core.eventQueue() == null) {
            return null;
        }

        return core;
    }
}
