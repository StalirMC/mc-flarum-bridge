package cn.stalir.mcbridge.task;

import cn.stalir.mcbridge.BridgeException;
import cn.stalir.mcbridge.McBridgePlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Polls the forum outbox and hands each message to the main thread.
 *
 * Messages are only marked as delivered on the forum when they are returned
 * without {@code peek}, so a crash mid-handling can at worst duplicate a
 * broadcast rather than lose it.
 */
public final class OutboxTask implements Runnable {

    private final McBridgePlugin plugin;
    private final AtomicLong consecutiveFailures = new AtomicLong();

    public OutboxTask(McBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!plugin.config().isUsable()) {
            return;
        }

        try {
            JsonObject response = plugin.client().fetchOutbox(false);
            consecutiveFailures.set(0);

            JsonArray messages = response.has("messages") && response.get("messages").isJsonArray()
                    ? response.getAsJsonArray("messages")
                    : new JsonArray();

            if (messages.isEmpty()) {
                return;
            }

            for (JsonElement element : messages) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject message = element.getAsJsonObject();

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        plugin.handleOutboxMessage(message);
                    } catch (Throwable throwable) {
                        plugin.getLogger().log(Level.WARNING, plugin.logText("log.outbox-handle-failed"), throwable);
                    }
                });
            }
        } catch (BridgeException exception) {
            long failures = consecutiveFailures.incrementAndGet();

            if (failures == 1 || failures % 10 == 0) {
                plugin.getLogger().warning(plugin.logText("log.outbox-failed",
                        "count", String.valueOf(failures),
                        "reason", exception.getMessage()));
            }
        }
    }
}
