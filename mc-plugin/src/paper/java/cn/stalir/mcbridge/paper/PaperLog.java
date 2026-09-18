package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.Log;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adapts the Bukkit {@link Logger} to the shared core's logging interface.
 *
 * The core cannot log through {@link Logger} directly because the same jar also
 * runs on Velocity, where the platform logger is SLF4J. {@code info} and
 * {@code warn} therefore map onto the JUL levels the server already prints, and
 * {@link #error(String, Throwable)} keeps the stack trace attached.
 */
public final class PaperLog implements Log {

    private final Logger logger;

    public PaperLog(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warning(message);
    }

    @Override
    public void fine(String message) {
        logger.fine(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.log(Level.WARNING, message, throwable);
    }
}
