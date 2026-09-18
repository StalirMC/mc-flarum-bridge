package cn.stalir.mcbridge.velocity;

import cn.stalir.mcbridge.BridgeCore;
import cn.stalir.mcbridge.Version;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Velocity entry point of the universal jar.
 *
 * It only wires the shared {@link BridgeCore} to the proxy: the data folder,
 * the logger and the scheduler come from Velocity, the commands and the
 * join/quit listener from this package. Paper and Folia load their own entry
 * point out of the same file.
 */
@Plugin(
    id = "mc-bridge",
    name = "McBridge",
    version = Version.VERSION,
    description = "Reports proxy status to Flarum and relays forum announcements into the game.",
    authors = {"Stalir"},
    url = "https://forum.kxkl2024.cn"
)
public final class McBridgeVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    // Built in onProxyInitialize and read by the two command classes.
    private VelocityPlatform platform;
    private BridgeCore core;

    @Inject
    public McBridgeVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        VelocityLog log = new VelocityLog(logger);

        this.platform = new VelocityPlatform(this, proxy, log, dataDirectory);
        this.core = new BridgeCore(platform);

        // Velocity resolves the owner of a command, a listener and a scheduled
        // task through the plugin instance it was registered with, so all of
        // them are handed this object.
        CommandManager commands = proxy.getCommandManager();

        CommandMeta bindMeta = commands.metaBuilder("bind").plugin(this).build();
        commands.register(bindMeta, new VelocityBindCommand(this));

        CommandMeta bridgeMeta = commands.metaBuilder("mcbridge").plugin(this).build();
        commands.register(bridgeMeta, new VelocityBridgeCommand(this));

        proxy.getEventManager().register(this, new VelocityListener(this));

        core.start();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (core != null) {
            core.stop();
        }
    }

    /**
     * The shared core, or {@code null} until {@link #onProxyInitialize} has run.
     */
    public BridgeCore core() {
        return core;
    }

    /** The Velocity adapter, or {@code null} until the proxy initialised the plugin. */
    public VelocityPlatform platform() {
        return platform;
    }
}
