package cn.stalir.mcbridge.velocity;

import cn.stalir.mcbridge.Log;
import cn.stalir.mcbridge.Platform;
import cn.stalir.mcbridge.Yaml;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.scheduler.TaskStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link Platform} for the Velocity proxy.
 *
 * Velocity has no main thread and no tick loop, so every task - including the
 * ones the shared core asks to run "on the main thread" - is handed to the
 * proxy's asynchronous scheduler. That is safe because the proxy's state
 * getters (player count, player list, configuration) are thread-safe, unlike
 * Bukkit's.
 */
public final class VelocityPlatform implements Platform {

    private final McBridgeVelocityPlugin plugin;
    private final ProxyServer proxy;
    private final VelocityLog log;
    private final Path dataFolder;

    /** Every task this plugin scheduled, so {@link #cancelTasks()} can stop them all. */
    private final List<ScheduledTask> tasks = new CopyOnWriteArrayList<>();

    public VelocityPlatform(McBridgeVelocityPlugin plugin, ProxyServer proxy, VelocityLog log, Path dataFolder) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.log = log;
        this.dataFolder = dataFolder;
    }

    @Override
    public String id() {
        return "velocity";
    }

    @Override
    public Log log() {
        return log;
    }

    @Override
    public Path dataFolder() {
        return dataFolder;
    }

    @Override
    public void saveResource(String resourcePath) {
        Path target = dataFolder.resolve(resourcePath);

        // The extracted copy is the one server owners edit, so never overwrite it.
        if (Files.exists(target)) {
            return;
        }

        // The whole plugin is a single jar, so the plugin's own class loader
        // also serves config.yml and the bundled language files.
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (resource == null) {
                log.warn("Resource " + resourcePath + " is missing from the jar; " + target + " was not written");
                return;
            }

            Path parent = target.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.copy(resource, target);
        } catch (FileAlreadyExistsException exception) {
            // Another thread extracted the same file first; the content is identical.
        } catch (IOException exception) {
            log.warn("Could not write " + target + ": " + exception.getMessage());
        }
    }

    @Override
    public Yaml readConfig() {
        saveResource("config.yml");

        return Yaml.load(dataFolder.resolve("config.yml"));
    }

    // ------------------------------------------------------------------
    // Scheduling
    // ------------------------------------------------------------------

    @Override
    public void runAsync(Runnable task) {
        schedule(task, null, null);
    }

    @Override
    public void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
        schedule(task, Duration.ofMillis(initialDelayMillis), Duration.ofMillis(periodMillis));
    }

    @Override
    public void runSyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
        // There is no main thread to hop to: the work the core would do there
        // only reads thread-safe proxy state, so it stays on the same scheduler.
        schedule(task, Duration.ofMillis(initialDelayMillis), Duration.ofMillis(periodMillis));
    }

    @Override
    public void runSync(Runnable task) {
        // Scheduled instead of run inline: the core hands work over from the
        // outbox poller, and never blocking the caller keeps a command or an
        // HTTP thread free while the task runs.
        schedule(task, null, null);
    }

    /**
     * Schedule through Velocity's own scheduler and remember the task.
     *
     * @param delay  {@code null} or zero to start as soon as the scheduler picks the task up
     * @param period {@code null} for a one-shot task; configured intervals are always positive
     */
    private void schedule(Runnable task, Duration delay, Duration period) {
        Scheduler.TaskBuilder builder = proxy.getScheduler().buildTask(plugin, task);

        if (delay != null && !delay.isZero() && !delay.isNegative()) {
            builder = builder.delay(delay);
        }

        if (period != null && !period.isZero() && !period.isNegative()) {
            builder = builder.repeat(period);
        }

        // Drop one-shot tasks that already ran, so the list only grows by the
        // tasks that are actually still pending.
        tasks.removeIf(scheduled -> scheduled.status() != TaskStatus.SCHEDULED);
        tasks.add(builder.schedule());
    }

    @Override
    public void cancelTasks() {
        for (ScheduledTask task : tasks) {
            try {
                task.cancel();
            } catch (IllegalStateException exception) {
                // Already cancelled or finished: there is nothing left to stop.
            }
        }

        tasks.clear();
    }


    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    @Override
    public void broadcast(Component message) {
        // Thread-safe, so the outbox poller can broadcast without a thread hop.
        proxy.getAllPlayers().forEach(player -> player.sendMessage(message));
    }

    @Override
    public void sendToPlayer(UUID uuid, Component message) {
        // Thread-safe, and empty when the player already disconnected.
        proxy.getPlayer(uuid).ifPresent(player -> player.sendMessage(message));
    }

    @Override
    public void logToConsole(Component message) {
        log.info(PlainTextComponentSerializer.plainText().serialize(message));
    }

}
