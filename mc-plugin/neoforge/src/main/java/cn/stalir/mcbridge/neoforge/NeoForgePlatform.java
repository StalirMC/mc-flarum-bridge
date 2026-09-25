package cn.stalir.mcbridge.neoforge;

import cn.stalir.mcbridge.Log;
import cn.stalir.mcbridge.Message;
import cn.stalir.mcbridge.Platform;
import cn.stalir.mcbridge.Yaml;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NeoForge implementation of {@link Platform}.
 *
 * A dedicated server has exactly one thread that may touch server state, so the
 * contract maps onto it directly:
 *
 * <ul>
 *   <li>{@link #runSync(Runnable)} submits to {@link MinecraftServer#execute},
 *       which is the server thread (or the global region tick on Folia-style
 *       regionised servers - not a thing on NeoForge, but the same idea).</li>
 *   <li>{@link #runAsync(Runnable)} and the repeating variants use this plugin's
 *       own scheduled pool, because a blocking forum request must never run on
 *       the server thread.</li>
 *   <li>A repeating sync task is scheduled on that pool and submits to the server
 *       on every tick it fires, so the timing stays independent of the tick
 *       loop.</li>
 * </ul>
 *
 * Files live in {@code config/mc-bridge/}, the conventional place for a mod's
 * configuration; the bundled language files are extracted there on first use.
 */
public final class NeoForgePlatform implements Platform {

    /** Folder below the mod config directory. */
    private static final String FOLDER = "mc-bridge";

    /** How long a shutdown waits for scheduled tasks to notice. */
    private static final long SHUTDOWN_GRACE_MILLIS = 250L;

    private final Log log;
    private final ScheduledExecutorService scheduler;
    private final List<ScheduledFuture<?>> repeating = new ArrayList<>();

    /** Set when the server starts, cleared when it stops. */
    private volatile MinecraftServer server;

    public NeoForgePlatform(Log log, MinecraftServer server) {
        this.log = log;
        this.server = server;
        this.scheduler = Executors.newScheduledThreadPool(2, new BridgeThreads());
    }

    // ------------------------------------------------------------------
    // Identity and files
    // ------------------------------------------------------------------

    @Override
    public String id() {
        return "neoforge";
    }

    @Override
    public Log log() {
        return log;
    }

    @Override
    public Path dataFolder() {
        return FMLPaths.CONFIGDIR.get().resolve(FOLDER);
    }

    @Override
    public void saveResource(String resourcePath) {
        Path target = dataFolder().resolve(resourcePath);

        // An existing file is the normal state after the first start, and the
        // server owner may have edited it.
        if (Files.exists(target)) {
            return;
        }

        try (InputStream in = NeoForgePlatform.class.getClassLoader().getResourceAsStream(resourcePath)) {
            // A build that does not bundle this resource must not abort startup.
            if (in == null) {
                return;
            }

            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        } catch (IOException exception) {
            log.warn("Could not extract " + resourcePath + ": " + exception.getMessage());
        }
    }

    @Override
    public Yaml readConfig() {
        saveResource("config.yml");

        // Yaml.load already returns an empty document when the file is missing or
        // unreadable, so this never returns null.
        return Yaml.load(dataFolder().resolve("config.yml"));
    }

    // ------------------------------------------------------------------
    // Scheduling
    // ------------------------------------------------------------------

    @Override
    public void runAsync(Runnable task) {
        try {
            scheduler.execute(task);
        } catch (Throwable ignored) {
            // The pool was shut down while the server was stopping.
        }
    }

    @Override
    public void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
        track(scheduler.scheduleAtFixedRate(task, initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS));
    }

    @Override
    public void runSyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
        // Scheduled on the plugin's own pool, delivered to the server thread: the
        // period stays wall-clock based instead of depending on tick timing.
        track(scheduler.scheduleAtFixedRate(
                () -> runSync(task), initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS));
    }

    @Override
    public void runSync(Runnable task) {
        MinecraftServer current = server;

        if (current == null) {
            return;
        }

        try {
            current.execute(task);
        } catch (Throwable ignored) {
            // The server is stopping and no longer accepts tasks.
        }
    }

    @Override
    public void cancelTasks() {
        synchronized (repeating) {
            for (ScheduledFuture<?> future : repeating) {
                future.cancel(false);
            }

            repeating.clear();
        }
    }

    /** Detach from a server that is stopping; the mod is never enabled again. */
    public void shutdown() {
        server = null;
        cancelTasks();

        scheduler.shutdownNow();

        try {
            scheduler.awaitTermination(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void track(ScheduledFuture<?> future) {
        synchronized (repeating) {
            repeating.add(future);
        }
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    @Override
    public void broadcast(Message message) {
        // Rendered once on the calling thread: the component is immutable, so the
        // single hop to the server thread can share it with every player.
        Component rendered = NeoForgeMessages.render(message);

        runSync(() -> {
            MinecraftServer current = server;

            if (current != null) {
                current.getPlayerList().broadcastSystemMessage(rendered, false);
            }
        });
    }

    @Override
    public void sendToPlayer(UUID uuid, Message message) {
        Component rendered = NeoForgeMessages.render(message);

        runSync(() -> {
            MinecraftServer current = server;

            if (current == null) {
                return;
            }

            ServerPlayer player = current.getPlayerList().getPlayer(uuid);

            // The player may have left between the event and the reply.
            if (player != null) {
                player.sendSystemMessage(rendered);
            }
        });
    }

    @Override
    public void logToConsole(Message message) {
        // A log file has no use for colour codes, so the plain form is logged;
        // this is safe from any thread.
        log.info(message.plain());
    }

    // ------------------------------------------------------------------
    // Threads
    // ------------------------------------------------------------------

    /**
     * Daemon threads, named after the mod.
     *
     * Daemon on purpose: a request that outlives the server must never keep the
     * JVM alive after {@code /stop}.
     */
    private static final class BridgeThreads implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "McBridge-" + counter.incrementAndGet());
            thread.setDaemon(true);

            return thread;
        }
    }
}
