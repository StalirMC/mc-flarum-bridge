package cn.stalir.mcbridge;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Everything the shared core needs from the server it is running on.
 *
 * There are two implementations, one per supported platform family:
 *
 * <ul>
 *   <li>{@code cn.stalir.mcbridge.paper.PaperPlatform} - Paper and Folia. It
 *       schedules through the Folia scheduler API (<code>AsyncScheduler</code>,
 *       <code>GlobalRegionScheduler</code>) when the server is regionised and
 *       through the classic {@code BukkitScheduler} otherwise, so one jar runs
 *       on both.</li>
 *   <li>{@code cn.stalir.mcbridge.neoforge.NeoForgePlatform} - the NeoForge mod,
 *       which schedules on the server thread through
 *       {@code MinecraftServer#execute} and delivers
 *       {@code net.minecraft.network.chat.Component}.</li>
 * </ul>
 *
 * Messages cross this boundary as {@link Message}, never as a platform component:
 * Paper ships Adventure and NeoForge does not, so the conversion belongs to the
 * platform module (see {@link Message}).
 *
 * Threading contract: server state may only be touched on the main thread (or
 * the global region) on Paper/Folia, and on the server thread on NeoForge, which
 * is what {@link #runSync(Runnable)} and
 * {@link #runSyncRepeating(Runnable, long, long)} are for.
 */
public interface Platform {

    /** {@code paper}, {@code folia} or {@code neoforge}: used in logs only. */
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

    /** Run a task once, off the main thread / on a platform async pool. */
    void runAsync(Runnable task);

    /** Run a task repeatedly, off the main thread. */
    void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis);

    /**
     * Run a task repeatedly on the main thread, or on the global region when the
     * server is regionised (Folia).
     */
    void runSyncRepeating(Runnable task, long initialDelayMillis, long periodMillis);

    /**
     * Run a task once on the main thread (global region on Folia; the server
     * thread on NeoForge).
     */
    void runSync(Runnable task);

    /** Cancel every task this plugin scheduled, used by reload and shutdown. */
    void cancelTasks();

    // ------------------------------------------------------------------
    // Output
    //
    // An announcement reaches players through whichever of these the
    // configuration selects, so the same text may be delivered more than once
    // through different channels on purpose.
    // ------------------------------------------------------------------

    void broadcast(Message message);

    /**
     * Show one line just above the hotbar.
     *
     * The action bar holds a single line, so a message with line breaks should be
     * reduced to its headline before it gets here.
     */
    void showActionBar(Message message);

    /**
     * Show a large centred title.
     *
     * @param subtitle   shown below the title; an empty message means "none"
     * @param fadeInTicks  time the title spends fading in
     * @param stayTicks    time the title stays fully visible
     * @param fadeOutTicks time the title spends fading out
     */
    void showTitle(Message title, Message subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks);

    /**
     * Show a bar across the top of the screen and take it away again.
     *
     * The removal is the platform's job: each one knows how to schedule it, and
     * the core has no way to hold on to a bar.
     *
     * @param seconds how long the bar stays visible
     */
    void showBossBar(Message message, int seconds);

    /**
     * Send one message to a single player, or do nothing when they are offline.
     *
     * Safe to call from any thread: the Paper/Folia implementation hops to the
     * thread that owns the player when the server is regionised.
     */
    void sendToPlayer(UUID uuid, Message message);

    void logToConsole(Message message);
}
