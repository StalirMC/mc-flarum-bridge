package cn.stalir.mcbridge;

/**
 * The logging surface the shared core needs.
 *
 * Bukkit's {@link java.util.logging.Logger} is not available on Velocity and
 * Velocity's SLF4J logger is not available on Paper, so each platform adapts its
 * own logger to this interface instead.
 */
public interface Log {

    void info(String message);

    void warn(String message);

    /** Verbose diagnostics; both platforms may drop these at their default level. */
    void fine(String message);

    /** Log a failure together with its stack trace. */
    void error(String message, Throwable throwable);
}
