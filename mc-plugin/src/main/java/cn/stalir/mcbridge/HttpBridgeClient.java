package cn.stalir.mcbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Talks to the Flarum MC Bridge API.
 *
 * Every request is signed with HMAC-SHA256 over the canonical string built by
 * {@link Signature}. The signed path deliberately excludes the query string,
 * matching the forum-side implementation.
 */
public final class HttpBridgeClient {

    private final BridgeConfig config;
    private final HttpClient http;

    public HttpBridgeClient(BridgeConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.requestTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ------------------------------------------------------------------
    // Bridge operations
    // ------------------------------------------------------------------

    public JsonObject fetchOutbox(boolean peek) throws BridgeException {
        return get("/outbox", "server_key=" + encode(config.serverKey()) + "&peek=" + peek + "&limit=20");
    }

    public JsonObject bindStart(UUID uuid, String playerName) throws BridgeException {
        JsonObject body = new JsonObject();
        body.addProperty("server_key", config.serverKey());
        body.addProperty("player_uuid", uuid.toString());
        body.addProperty("player_name", playerName);

        return post("/bind/start", body);
    }

    public JsonObject bindStatus(UUID uuid) throws BridgeException {
        return get("/bind/status", "server_key=" + encode(config.serverKey()) + "&uuid=" + encode(uuid.toString()));
    }

    public JsonObject broadcast(String body, String title) throws BridgeException {
        JsonObject payload = new JsonObject();
        payload.addProperty("server_key", config.serverKey());
        payload.addProperty("type", "broadcast");
        payload.addProperty("body", body);

        if (title != null && !title.isBlank()) {
            payload.addProperty("title", title);
        }

        return post("/broadcast", payload);
    }


    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private JsonObject get(String path, String query) throws BridgeException {
        String signedPath = config.apiPath(path);
        String url = config.endpoint(path) + (query == null || query.isEmpty() ? "" : "?" + query);

        HttpRequest request = signedBuilder(url, signedPath, "GET", null, config.requestTimeout())
                .GET()
                .build();

        return execute(request);
    }

    private JsonObject post(String path, JsonObject body) throws BridgeException {
        return post(path, body, config.requestTimeout());
    }

    private JsonObject post(String path, JsonObject body, Duration timeout) throws BridgeException {
        String signedPath = config.apiPath(path);
        String payload = body == null ? "{}" : body.toString();

        HttpRequest request = signedBuilder(config.endpoint(path), signedPath, "POST", payload, timeout)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        return execute(request);
    }

    private HttpRequest.Builder signedBuilder(String url, String signedPath, String method, String body, Duration timeout) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = Signature.newNonce();

        // The canonical path starts at the bridge route prefix: the forum strips
        // its /api frontend prefix before the controller runs, so signing the
        // raw request path would never match.
        String canonicalPath = Signature.normalizePath(signedPath);
        String signature = Signature.sign(config.secret(), timestamp, nonce, method, canonicalPath, body);

        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("User-Agent", userAgent())
                .header(Signature.HEADER_TIMESTAMP, timestamp)
                .header(Signature.HEADER_NONCE, nonce)
                .header(Signature.HEADER_SIGNATURE, signature)
                .header(Signature.HEADER_SERVER, config.serverKey());
    }

    private JsonObject execute(HttpRequest request) throws BridgeException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String payload = response.body();

            if (status < 200 || status >= 300) {
                String detail = extractError(payload);
                throw new BridgeException(
                        "Forum returned HTTP " + status + (detail.isEmpty() ? "" : " (" + detail + ")"),
                        status
                );
            }

            if (payload == null || payload.isBlank()) {
                return new JsonObject();
            }

            JsonElement parsed = JsonParser.parseString(payload);

            if (!parsed.isJsonObject()) {
                throw new BridgeException("Forum returned an unexpected payload shape", status);
            }

            return parsed.getAsJsonObject();
        } catch (JsonSyntaxException exception) {
            throw new BridgeException("Forum returned malformed JSON", -1, exception);
        } catch (IOException exception) {
            throw new BridgeException("Cannot reach the forum: " + exception.getMessage(), -1, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BridgeException("Request was interrupted", -1, exception);
        }
    }

    private String extractError(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }

        try {
            JsonElement parsed = JsonParser.parseString(payload);

            if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();

                if (object.has("error") && object.get("error").isJsonPrimitive()) {
                    return object.get("error").getAsString();
                }
            }
        } catch (JsonSyntaxException ignored) {
            // Not JSON; fall through to the raw excerpt.
        }

        String flat = payload.replaceAll("\\s+", " ").trim();

        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }

    private String userAgent() {
        return "McBridge/" + Version.VERSION + " (+" + config.forumUrl() + ")";
    }

    /**
     * Release the underlying selector/connection pool.
     *
     * Must be called before dropping a client (for example on /mcbridge reload),
     * otherwise each reload leaks an executor thread and a connection pool.
     * {@code HttpClient} became {@link AutoCloseable} in Java 21; the instanceof
     * check keeps this compiling on older runtimes.
     */
    public void close() {
        try {
            if (http instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception ignored) {
            // Nothing useful to do while tearing down.
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
