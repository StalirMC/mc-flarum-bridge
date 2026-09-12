package cn.stalir.mcbridge;

/**
 * Thrown when the forum rejects a bridge request or cannot be reached.
 */
public class BridgeException extends Exception {

    private final int statusCode;

    public BridgeException(String message) {
        this(message, -1, null);
    }

    public BridgeException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public BridgeException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    public BridgeException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
