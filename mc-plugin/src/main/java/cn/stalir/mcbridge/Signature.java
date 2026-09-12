package cn.stalir.mcbridge;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * HMAC-SHA256 request signing.
 *
 * The canonical string signed by both sides is:
 *
 * <pre>
 *   {timestamp}\n{nonce}\n{METHOD}\n{path}\n{rawBody}
 * </pre>
 *
 * `path` is the request path starting at the bridge API prefix, so the signature
 * is independent of any Flarum sub-directory. This mirrors
 * {@code Stalir\McBridge\Service\BridgeCrypto} on the forum side.
 */
public final class Signature {

    public static final String HEADER_TIMESTAMP = "X-MC-Timestamp";
    public static final String HEADER_NONCE = "X-MC-Nonce";
    public static final String HEADER_SIGNATURE = "X-MC-Signature";
    public static final String HEADER_SERVER = "X-MC-Server";

    private Signature() {
    }

    public static String canonicalString(String timestamp, String nonce, String method, String path, String body) {
        return timestamp + "\n"
                + nonce + "\n"
                + method.toUpperCase(Locale.ROOT) + "\n"
                + path + "\n"
                + (body == null ? "" : body);
    }

    public static String sign(String secret, String timestamp, String nonce, String method, String path, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            byte[] digest = mac.doFinal(
                    canonicalString(timestamp, nonce, method, path, body).getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to compute the HMAC signature", exception);
        }
    }

    /**
     * Route prefix every bridge route lives under. The canonical signed path
     * starts here.
     */
    public static final String PATH_MARKER = "/mc-bridge";

    /**
     * Canonical form of the signed path: the query string is dropped and
     * everything before {@link #PATH_MARKER} is removed.
     *
     * The forum strips its api frontend prefix (`/api`) before the controller
     * runs, so it sees `/mc-bridge/heartbeat` while this client requests
     * `/api/mc-bridge/heartbeat`. Signing the raw path would make the two sides
     * disagree on the very first segment and every request would be rejected
     * with 401. This mirrors BridgeCrypto::normalizePath() on the forum side.
     */
    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }

        String cleaned = path;

        int query = cleaned.indexOf('?');
        if (query >= 0) {
            cleaned = cleaned.substring(0, query);
        }

        if (!cleaned.startsWith("/")) {
            cleaned = "/" + cleaned;
        }

        int marker = cleaned.indexOf(PATH_MARKER);
        if (marker >= 0) {
            cleaned = cleaned.substring(marker);
        }

        return cleaned.isEmpty() ? "/" : cleaned;
    }

    public static String newNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
