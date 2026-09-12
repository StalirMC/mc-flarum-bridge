package cn.stalir.mcbridge.task;

import cn.stalir.mcbridge.McBridgePlugin;

/**
 * Periodic status report.
 *
 * Runs on the MAIN thread on purpose: gathering the snapshot reads Bukkit state
 * (online players, MOTD, player limit), which is not thread-safe. Only the HTTP
 * transmission is dispatched to an asynchronous task by
 * {@link McBridgePlugin#heartbeatAsync()}.
 */
public final class HeartbeatTask implements Runnable {

    private final McBridgePlugin plugin;

    public HeartbeatTask(McBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        plugin.heartbeatAsync();
    }
}
