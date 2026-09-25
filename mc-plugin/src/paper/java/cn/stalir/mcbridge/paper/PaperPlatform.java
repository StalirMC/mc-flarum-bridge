package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.Log;
import cn.stalir.mcbridge.Message;
import cn.stalir.mcbridge.Platform;
import cn.stalir.mcbridge.Yaml;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Paper/Folia implementation of {@link Platform}.
 *
 * One compiled class serves both server families, because the Folia scheduler
 * API ships inside paper-api:
 *
 * <ul>
 *   <li>On a regionised server (Folia) scheduling goes exclusively through
 *       {@code AsyncScheduler} and {@code GlobalRegionScheduler}. The classic
 *       {@code Bukkit.getScheduler()} throws {@link UnsupportedOperationException}
 *       there, so it is never touched.</li>
 *   <li>On plain Paper the classic {@code BukkitScheduler} is used, since the
 *       main thread is the only thread that may touch server state.</li>
 * </ul>
 *
 * Threading: server state is only read from a task started by
 * {@link #runSyncRepeating(Runnable, long, long)}, i.e. from the main thread or
 * from the global region. {@link #logToConsole(Message)} is safe from any
 * thread; {@link #broadcast(Message)} hands each player's copy to that player's
 * own scheduler when the server is regionised.
 *
 * This class is also where core {@link Message}s become Adventure components:
 * Paper ships Adventure, and the shared core deliberately does not depend on it
 * (see {@link Message}).
 *
 * Every repeating task is kept as a handle, so {@link #cancelTasks()} cancels
 * exactly what this plugin scheduled instead of an arbitrary superset.
 */
public final class PaperPlatform implements Platform {

    /**
     * Folia marks itself with {@code RegionizedServer}, a class plain Paper does
     * not ship. Deciding this once at class load keeps the scheduling branches
     * below free of repeated lookups.
     */
    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    /** Bukkit counts delays in ticks of 50 ms. */
    private static final long MILLIS_PER_TICK = 50L;

    private final McBridgePlugin plugin;
    private final Log log;

    // Handles of the tasks this plugin scheduled, one list per scheduler family.
    private final List<BukkitTask> bukkitTasks = new ArrayList<>();
    private final List<ScheduledTask> foliaTasks = new ArrayList<>();

    public PaperPlatform(McBridgePlugin plugin, Log log) {
        this.plugin = plugin;
        this.log = log;
    }

    // ------------------------------------------------------------------
    // Identity and files
    // ------------------------------------------------------------------

    @Override
    public String id() {
        return FOLIA ? "folia" : "paper";
    }

    @Override
    public Log log() {
        return log;
    }

    @Override
    public Path dataFolder() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public void saveResource(String resourcePath) {
        // JavaPlugin#saveResource(path, false) logs its own warning ("Could not
        // save config.yml to ... because config.yml already exists") whenever the
        // target exists, so the call is skipped entirely in that case: an
        // existing file is the normal state after the first start, not something
        // worth warning about on every boot.
        if (new File(plugin.getDataFolder(), resourcePath).exists()) {
            return;
        }

        try {
            // A build that does not bundle this resource must not abort startup,
            // hence the IllegalArgumentException catch.
            plugin.saveResource(resourcePath, false);
        } catch (IllegalArgumentException ignored) {
            // The embedded resource is not part of this jar.
        }
    }

    @Override
    public Yaml readConfig() {
        saveResource("config.yml");

        // Yaml.load already returns an empty document when the file is missing
        // or unreadable, so this never returns null.
        return Yaml.load(dataFolder().resolve("config.yml"));
    }

    // ------------------------------------------------------------------
    // Scheduling
    // ------------------------------------------------------------------

    @Override
    public void runAsync(Runnable task) {
        if (FOLIA) {
            foliaTasks.add(plugin.getServer().getAsyncScheduler().runNow(plugin, scheduled -> task.run()));
            return;
        }

        bukkitTasks.add(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    @Override
    public void runAsyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
        if (FOLIA) {
            // The async scheduler speaks milliseconds, so no tick conversion is
            // needed here.
            foliaTasks.add(plugin.getServer().getAsyncScheduler().runAtFixedRate(
                    plugin,
                    scheduled -> task.run(),
                    initialDelayMillis,
                    periodMillis,
                    TimeUnit.MILLISECONDS));
            return;
        }

        // The task is passed as a Runnable local on purpose: BukkitScheduler
        // also exposes Consumer<BukkitTask> overloads, and an implicitly typed
        // method reference would make the call ambiguous between the two.
        bukkitTasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, task, toTicks(initialDelayMillis), toTicks(periodMillis)));
    }

    @Override
    public void runSyncRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
        if (FOLIA) {
            foliaTasks.add(plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                    plugin,
                    scheduled -> task.run(),
                    toTicks(initialDelayMillis),
                    toTicks(periodMillis)));
            return;
        }

        bukkitTasks.add(Bukkit.getScheduler().runTaskTimer(
                plugin, task, toTicks(initialDelayMillis), toTicks(periodMillis)));
    }

    @Override
    public void runSync(Runnable task) {
        if (FOLIA) {
            // execute() returns void, so the global region scheduler owns the
            // task; cancelTasks() sweeps it through cancelTasks(plugin).
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
            return;
        }

        bukkitTasks.add(Bukkit.getScheduler().runTask(plugin, task));
    }

    @Override
    public void cancelTasks() {
        if (FOLIA) {
            cancelFoliaTasks();

            // The scheduler-wide call also covers anything queued while the
            // handles above were being cancelled.
            plugin.getServer().getAsyncScheduler().cancelTasks(plugin);
            plugin.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
            return;
        }

        for (BukkitTask task : bukkitTasks) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
                // Already cancelled or the scheduler is shutting down.
            }
        }

        bukkitTasks.clear();
    }

    private void cancelFoliaTasks() {
        for (ScheduledTask task : foliaTasks) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
                // Already cancelled or the scheduler is shutting down.
            }
        }

        foliaTasks.clear();
    }

    /** Convert a millisecond delay to ticks, never below the single-tick floor. */
    private static long toTicks(long millis) {
        return Math.max(1L, millis / MILLIS_PER_TICK);
    }


    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    @Override
    public void broadcast(Message message) {
        // Rendered once on the calling thread: the component is immutable, so
        // every player's copy below can share it safely.
        Component rendered = AdventureMessages.render(message);

        if (FOLIA) {
            // A regionised server owns each player in the region that ticks it,
            // so every copy is delivered through that player's own scheduler.
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.getScheduler().run(plugin, scheduled -> player.sendMessage(rendered), null);
            }

            return;
        }

        // Plain Paper: one main-thread task, so the player list is never read
        // from a foreign thread. The body is held in a Runnable local so the
        // BukkitScheduler overloads cannot be ambiguous; the one-shot delivery
        // needs no handle of its own.
        Runnable delivery = () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(rendered);
            }
        };

        Bukkit.getScheduler().runTask(plugin, delivery);
    }

    @Override
    public void sendToPlayer(UUID uuid, Message message) {
        Player player = Bukkit.getPlayer(uuid);

        if (player == null) {
            // The player left between the event and the reply from the forum.
            return;
        }

        Component rendered = AdventureMessages.render(message);

        if (FOLIA) {
            // Regionised: the message must be delivered on the thread that owns
            // the player, so it goes through that player's own scheduler.
            player.getScheduler().run(plugin, scheduled -> player.sendMessage(rendered), null);
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(rendered));
    }

    @Override
    public void logToConsole(Message message) {
        // ConsoleSender#sendMessage is safe from any thread on both families.
        Bukkit.getConsoleSender().sendMessage(AdventureMessages.render(message));
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    /** {@code Class.forName} in a try/catch: the class only exists on Folia. */
    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable ignored) {
            // Plain Paper does not ship the regionised server classes.
            return false;
        }
    }
}
