<?php

namespace Stalir\McBridge\Api\Controller;

use Flarum\Settings\SettingsRepositoryInterface;
use Illuminate\Contracts\Cache\Repository as CacheRepository;
use Laminas\Diactoros\Response\JsonResponse;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Psr\Http\Server\RequestHandlerInterface;
use Stalir\McBridge\Service\BridgeCrypto;

/**
 * Base class for every bridge controller.
 *
 * Machine endpoints call {@see self::assertMachine()} first; a non-null return
 * value is an error response that must be passed straight back to the client.
 */
abstract class AbstractBridgeController implements RequestHandlerInterface
{
    public function __construct(
        protected SettingsRepositoryInterface $settings,
        protected CacheRepository $cache
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

    protected function error(string $message, int $status = 400, array $extra = []): ResponseInterface
    {
        return new JsonResponse(array_merge(['error' => $message], $extra), $status);
    }

    /**
     * Raw request body. Must be byte-identical to what the client signed, so it
     * is read from the stream rather than re-encoded from the parsed body.
     */
    protected function rawBody(ServerRequestInterface $request): string
    {
        return (string) $request->getBody();
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
            return $this->error(
                'The MC Bridge secret is not configured on this forum. Run "php flarum mc-bridge:secret" first.',
                503
            );
        }

        $timestamp = $request->getHeaderLine(BridgeCrypto::HEADER_TIMESTAMP);
        $nonce = $request->getHeaderLine(BridgeCrypto::HEADER_NONCE);
        $signature = $request->getHeaderLine(BridgeCrypto::HEADER_SIGNATURE);

        if ($timestamp === '' || $nonce === '' || $signature === '') {
            return $this->error('Missing bridge authentication headers.', 401);
        }

        if (! ctype_digit($timestamp)) {
            return $this->error('Malformed timestamp header.', 401);
        }

        $skew = abs(time() - (int) $timestamp);

        if ($skew > BridgeCrypto::MAX_SKEW) {
            return $this->error('Request timestamp is outside the allowed window.', 401, [
                'skew_seconds' => $skew,
            ]);
        }

        $nonceLength = strlen($nonce);

        if ($nonceLength < 8 || $nonceLength > 128) {
            return $this->error('Malformed nonce header.', 401);
        }

        $valid = BridgeCrypto::verify(
            $secret,
            $timestamp,
            $nonce,
            $request->getMethod(),
            BridgeCrypto::normalizePath($request->getUri()->getPath()),
            $this->rawBody($request),
            $signature
        );

        if (! $valid) {
            return $this->error('Signature verification failed.', 401);
        }

        // A nonce may only be used once; keep it for twice the allowed skew.
        $cacheKey = 'mc-bridge:nonce:' . hash('sha256', $nonce);

        if (! $this->cache->add($cacheKey, 1, BridgeCrypto::MAX_SKEW * 2)) {
            return $this->error('Duplicate nonce detected (possible replay).', 401);
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
