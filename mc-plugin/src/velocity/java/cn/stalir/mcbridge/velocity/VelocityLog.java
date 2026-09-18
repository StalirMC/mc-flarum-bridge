package cn.stalir.mcbridge.velocity;

import cn.stalir.mcbridge.Log;
import org.slf4j.Logger;

/**
 * {@link Log} backed by the SLF4J logger Velocity injects into the plugin.
 *
 * The shared core only knows {@link Log}, because Paper and Folia log through
 * {@code java.util.logging} while the proxy logs through SLF4J.
 */
public final class VelocityLog implements Log {

    private final Logger logger;

    public VelocityLog(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warn(message);
    }

    /** Diagnostics only: Velocity's default level hides debug output. */
    @Override
    public void fine(String message) {
        logger.debug(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.error(message, throwable);
    }
}
