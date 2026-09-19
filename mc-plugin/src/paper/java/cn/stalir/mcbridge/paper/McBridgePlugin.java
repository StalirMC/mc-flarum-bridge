package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.BridgeCore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point of the Minecraft side of the bridge on Paper and Folia.
 *
 * The plugin stays thin on purpose: everything that talks to the forum lives in
 * the shared {@link BridgeCore} and everything that touches the server lives in
 * {@link PaperPlatform}, so the same compiled classes run on plain Paper and on
 * regionised Folia.
 *
 * Threading contract
 * ------------------
 * Server state must only be read on the main thread, or on the global region
 * when the server is regionised. Loading the configuration, scheduling the
 * heartbeat and transmitting the events are therefore all owned by the core,
 * which reaches the server exclusively through {@link PaperPlatform}.
 */
public final class McBridgePlugin extends JavaPlugin {

    private BridgeCore core;

    @Override
    public void onEnable() {
        PaperLog log = new PaperLog(getLogger());
        PaperPlatform platform = new PaperPlatform(this, log);

        this.core = new BridgeCore(platform);

        getServer().getPluginManager().registerEvents(new PlayerListener(core), this);
        registerCommands();

        // Reads config.yml, extracts the bundled language files, schedules the
        // periodic tasks and reports the start event.
        core.start();
    }

    @Override
    public void onDisable() {
        if (core != null) {
            core.stop();
        }
    }

    /** Wire the three plugin.yml commands, tolerating a missing declaration. */
    private void registerCommands() {
        PluginCommand bind = getCommand("bind");

        if (bind != null) {
            bind.setExecutor(new BindCommand(core));
        }

        PluginCommand report = getCommand("report");

        if (report != null) {
            report.setExecutor(new ReportCommand(core));
        }

        PluginCommand mcbridge = getCommand("mcbridge");

        if (mcbridge != null) {
            BridgeCommand executor = new BridgeCommand(core);

            mcbridge.setExecutor(executor);
            mcbridge.setTabCompleter(executor);
        }
    }
}
