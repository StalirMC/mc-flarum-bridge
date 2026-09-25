package cn.stalir.mcbridge.neoforge;

import cn.stalir.mcbridge.Log;
import org.slf4j.Logger;

/**
 * Adapts the SLF4J logger Minecraft uses to the shared core's logging interface.
 *
 * The core cannot log through a platform logger directly because the same core
 * also runs on Paper, where the server hands out a {@code java.util.logging}
 * logger instead.
 */
public final class NeoForgeLog implements Log {

    private final Logger logger;

    public NeoForgeLog(Logger logger) {
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

    @Override
    public void fine(String message) {
        // SLF4J has no "fine"; debug is the closest and is off by default, which
        // is exactly what the core expects from a fine line.
        logger.debug(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.error(message, throwable);
    }
}
