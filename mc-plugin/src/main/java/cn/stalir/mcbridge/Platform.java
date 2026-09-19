package cn.stalir.mcbridge;

import net.kyori.adventure.text.Component;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Everything the shared core needs from the server it is running on.
 *
 * There are two implementations, one per jar-compatible platform family:
 *
 * <ul>
 *   <li>{@code cn.stalir.mcbridge.paper.PaperPlatform} - Paper and Folia. It
 *       schedules through the Folia scheduler API (<code>AsyncScheduler</code>,
 *       <code>GlobalRegionScheduler</code>) when the server is regionised and
 *       through the classic {@code BukkitScheduler} otherwise, so one jar runs
 *       on both.</li>
 *   <li>{@code cn.stalir.mcbridge.velocity.VelocityPlatform} - the Velocity
 *       proxy, which has no main thread and no game commands.</li>
 * </ul>
 *
 * Threading contract: server state may only be touched on the main thread (or
 * the global region) on Paper/Folia, which is what {@link #runSync(Runnable)}
 * and {@link #runSyncRepeating(Runnable, long, long)} are for. Velocity has no
 * main thread and runs everything on its own scheduler.
 */
public interface Platform {

    /** {@code paper}, {@code folia} or {@code velocity}: used in logs only. */
    String id();

    Log log();

    /** Folder holding config.yml and lang/ for this platform. */
    Path dataFolder();

    /**
     * Copy a resource bundled in the jar into the data folder.
     *
     * Existing files are never overwritten, so server owners can edit the
     * extracted copy.
     */
    void saveResource(String resourcePath);

    /**
     * Parse config.yml from the data folder, extracting the bundled default
     * first when the file does not exist yet.
     *
     * The shared core reads the {@code language} key from this document before
     * anything else, because every log line about configuration problems is
     * rendered in the configured language.
     */
    Yaml readConfig();

    /** Run a task once, off the main thread / on the proxy's async pool. */
    void runAsync(Runnable task);

    /** Run a task repeatedly, off the main thread. */
    void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis);

    /**
     * Run a task repeatedly on the main thread, or on the global region when the
     * server is regionised (Folia).
     */
    void runSyncRepeating(Runnable task, long initialDelayMillis, long periodMillis);

    /**
     * Run a task once on the main thread (global region on Folia), or inline
     * when the platform is already fully asynchronous (Velocity).
     */
    void runSync(Runnable task);

    /** Cancel every task this plugin scheduled, used by reload and shutdown. */
    void cancelTasks();

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    void broadcast(Component message);

    /**
     * Send one message to a single player, or do nothing when they are offline.
     *
     * Safe to call from any thread: the Paper/Folia implementation hops to the
     * thread that owns the player when the server is regionised.
     */
    void sendToPlayer(UUID uuid, Component message);

    void logToConsole(Component message);
}
