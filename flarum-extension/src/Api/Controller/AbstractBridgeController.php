<?php

namespace Stalir\McBridge\Api\Controller;

use Flarum\Settings\SettingsRepositoryInterface;
use Illuminate\Cache\Repository as CacheRepository;
use Laminas\Diactoros\Response\JsonResponse;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Psr\Http\Server\RequestHandlerInterface;
use Stalir\McBridge\Service\BridgeCrypto;
use Stalir\McBridge\Service\BridgeMessages;

/**
 * Base class for every bridge controller.
 *
 * Machine endpoints call {@see self::assertMachine()} first; a non-null return
 * value is an error response that must be passed straight back to the client.
 *
 * Every error message is translated through {@see BridgeMessages}, which
 * defaults to Simplified Chinese.
 */
abstract class AbstractBridgeController implements RequestHandlerInterface
{
    public function __construct(
        protected SettingsRepositoryInterface $settings,
        protected CacheRepository $cache,
        protected BridgeMessages $messages
    ) {
    }

    protected function secret(): string
    {
        return (string) $this->settings->get('mc-bridge.secret', '');
    }

    protected function json(array $data, int $status = 200): ResponseInterface
    {
        return new JsonResponse($data, $status);
    }

    /**
     * Respond with a raw, untranslated message.
     */
    protected function error(string $message, int $status = 400, array $extra = []): ResponseInterface
    {
        return new JsonResponse(array_merge(['error' => $message], $extra), $status);
    }

    /**
     * Respond with a translated message.
     *
     * @param  string  $key  Suffix below `api.error.`, e.g. "server_key_required".
     * @param  array<string, string>  $replace  placeholder name => value (ICU {name} syntax; no braces in the key).
     */
    protected function fail(string $key, int $status, array $replace = [], array $extra = []): ResponseInterface
    {
        return $this->error($this->messages->get('api.error.'.$key, $replace), $status, $extra);
    }

    /**
     * Raw request body. Must be byte-identical to what the client signed, so it
     * is read from the stream rather than re-encoded from the parsed body.
     *
     * Flarum's ParseJsonBody middleware runs before the controller and does
     * json_decode($request->getBody(), true), which reads the stream to the end.
     * When the stream is not seekable - php://input reports isSeekable() ===
     * false in PHP-FPM - there is no rewind, so a second read returns an empty
     * string and every signature would fail. Re-opening php://input yields the
     * body again.
     */
    protected function rawBody(ServerRequestInterface $request): string
    {
        $stream = $request->getBody();

        if ($stream->isSeekable()) {
            $stream->rewind();
        }

        $raw = $stream->getContents();

        if ($raw !== '') {
            return $raw;
        }

        if (! $stream->isSeekable()) {
            $reopened = @file_get_contents('php://input');

            if (is_string($reopened) && $reopened !== '') {
                return $reopened;
            }
        }

        return $raw;
    }

    /**
     * Decoded JSON body.
     */
    protected function body(ServerRequestInterface $request): array
    {
        $parsed = $request->getParsedBody();

        if (is_array($parsed) && $parsed !== []) {
            return $parsed;
        }

        $raw = $this->rawBody($request);

        if ($raw === '') {
            return [];
        }

        $decoded = json_decode($raw, true);

        return is_array($decoded) ? $decoded : [];
    }

    /**
     * Validate the HMAC signature, clock skew and nonce of a machine request.
     *
     * @return ResponseInterface|null null when the request is authentic.
     */
    protected function assertMachine(ServerRequestInterface $request): ?ResponseInterface
    {
        $secret = $this->secret();

        if ($secret === '') {
            return $this->fail('secret_missing', 503);
        }

        $timestamp = $request->getHeaderLine(BridgeCrypto::HEADER_TIMESTAMP);
        $nonce = $request->getHeaderLine(BridgeCrypto::HEADER_NONCE);
        $signature = $request->getHeaderLine(BridgeCrypto::HEADER_SIGNATURE);

        if ($timestamp === '' || $nonce === '' || $signature === '') {
            return $this->fail('auth_headers_missing', 401);
        }

        if (! ctype_digit($timestamp)) {
            return $this->fail('timestamp_malformed', 401);
        }

        $skew = abs(time() - (int) $timestamp);

        if ($skew > BridgeCrypto::MAX_SKEW) {
            return $this->fail('timestamp_skew', 401, [], ['skew_seconds' => $skew]);
        }

        $nonceLength = strlen($nonce);

        if ($nonceLength < 8 || $nonceLength > 128) {
            return $this->fail('nonce_malformed', 401);
        }

        $method = $request->getMethod();
        $path = BridgeCrypto::normalizePath($request->getUri()->getPath());
        $rawBody = $this->rawBody($request);

        $valid = BridgeCrypto::verify($secret, $timestamp, $nonce, $method, $path, $rawBody, $signature);

        if (! $valid) {
            // Opt-in diagnostics: everything below is data the caller already
            // supplied (or the server's view of it), never the shared secret.
            // A client sends X-MC-Diagnostic to find out which component of the
            // canonical string does not match.
            if ($request->hasHeader(BridgeCrypto::HEADER_DIAGNOSTIC)) {
                return $this->error($this->messages->get('api.error.signature_invalid'), 401, [
                    'diagnostic' => [
                        'method' => $method,
                        'path' => $path,
                        'body_length' => strlen($rawBody),
                        'body_sha256' => hash('sha256', $rawBody),
                        'body_preview' => mb_substr($rawBody, 0, 200),
                        'timestamp' => $timestamp,
                        'nonce' => $nonce,
                        'stream_seekable' => $request->getBody()->isSeekable(),
                        'canonical' => BridgeCrypto::canonicalString($timestamp, $nonce, $method, $path, $rawBody),
                    ],
                ]);
            }

            return $this->fail('signature_invalid', 401);
        }

        // A nonce may only be used once; keep it for twice the allowed skew.
        $cacheKey = 'mc-bridge:nonce:' . hash('sha256', $nonce);

        if (! $this->cache->add($cacheKey, 1, BridgeCrypto::MAX_SKEW * 2)) {
            return $this->fail('nonce_reused', 401);
        }

        return null;
    }

    /**
     * Read and validate the server key identifying the calling server.
     */
    protected function resolveServerKey(ServerRequestInterface $request, array $body): ?string
    {
        $key = $body['server_key'] ?? $request->getHeaderLine(BridgeCrypto::HEADER_SERVER);

        if (! is_string($key)) {
            return null;
        }

        $key = trim($key);

        if ($key === '' || strlen($key) > 100 || ! preg_match('/^[A-Za-z0-9._-]+$/', $key)) {
            return null;
        }

        return $key;
    }
}
