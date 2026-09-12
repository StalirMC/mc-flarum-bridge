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
import java.util.logging.Logger;

/**
 * Talks to the Flarum MC Bridge API.
 *
 * Every request is signed with HMAC-SHA256 over the canonical string built by
 * {@link Signature}. The signed path deliberately excludes the query string,
 * matching the forum-side implementation.
 */
public final class HttpBridgeClient {

    private final BridgeConfig config;
    private final Logger logger;
    private final HttpClient http;

    public HttpBridgeClient(BridgeConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.requestTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ------------------------------------------------------------------
    // Bridge operations
    // ------------------------------------------------------------------

    public JsonObject heartbeat(JsonObject payload) throws BridgeException {
        return post("/heartbeat", payload, config.requestTimeout());
    }

    /**
     * Variant with an explicit timeout, used during shutdown where blocking for
     * the full request timeout would delay the server stop.
     */
    public JsonObject heartbeat(JsonObject payload, Duration timeout) throws BridgeException {
        return post("/heartbeat", payload, timeout);
    }

    public JsonObject sendEvents(JsonArray events) throws BridgeException {
        return sendEvents(events, config.requestTimeout());
    }

    public JsonObject sendEvents(JsonArray events, Duration timeout) throws BridgeException {
        JsonObject body = new JsonObject();
        body.addProperty("server_key", config.serverKey());
        body.add("events", events);

        return post("/events", body, timeout);
    }

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

    /** Public, unsigned snapshot of the servers known to the forum. */
    public JsonObject fetchStatus() throws BridgeException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.endpoint("/status")))
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .header("User-Agent", userAgent())
                .GET()
                .build();

        return execute(request);
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
        String signature = Signature.sign(config.secret(), timestamp, nonce, method, signedPath, body);

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
        return "McBridge/1.0 (+" + config.forumUrl() + ")";
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
