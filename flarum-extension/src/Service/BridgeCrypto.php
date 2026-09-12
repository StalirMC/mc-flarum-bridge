<?php

namespace Stalir\McBridge\Service;

/**
 * Shared HMAC signing/verification helper.
 *
 * The canonical string signed by both sides is:
 *
 *   {timestamp}\n{nonce}\n{METHOD}\n{path}\n{rawBody}
 *
 * where `path` is the request path *without* the query string and
 * `rawBody` is the raw request body (empty string for GET/DELETE).
 */
final class BridgeCrypto
{
    public const HEADER_TIMESTAMP = 'X-MC-Timestamp';
    public const HEADER_NONCE = 'X-MC-Nonce';
    public const HEADER_SIGNATURE = 'X-MC-Signature';
    public const HEADER_SERVER = 'X-MC-Server';

    /**
     * Opt-in request header: when present, a failed signature check returns a
     * breakdown of the canonical string the server built, so a client can see
     * which component differs. Contains no secret material.
     */
    public const HEADER_DIAGNOSTIC = 'X-MC-Diagnostic';

    /**
     * Route prefix every bridge route lives under. The canonical signed path
     * starts here (see {@see self::normalizePath()}).
     */
    public const PATH_MARKER = '/mc-bridge';

    /** Allowed clock skew, in seconds. */
    public const MAX_SKEW = 300;

    public static function canonicalString(
        string|int $timestamp,
        string $nonce,
        string $method,
        string $path,
        string $body = ''
    ): string {
        return implode("\n", [
            (string) $timestamp,
            $nonce,
            strtoupper($method),
            $path,
            $body,
        ]);
    }

    public static function sign(
        string $secret,
        string|int $timestamp,
        string $nonce,
        string $method,
        string $path,
        string $body = ''
    ): string {
        return hash_hmac('sha256', self::canonicalString($timestamp, $nonce, $method, $path, $body), $secret);
    }

    public static function verify(
        string $secret,
        string|int $timestamp,
        string $nonce,
        string $method,
        string $path,
        string $body,
        string $signature
    ): bool {
        if ($secret === '' || $signature === '') {
            return false;
        }

        $expected = self::sign($secret, $timestamp, $nonce, $method, $path, $body);

        return hash_equals($expected, $signature);
    }

    /**
     * Normalises a request path so that both sides always sign the same value.
     *
     * The canonical path starts at the bridge route prefix, i.e. at
     * {@see self::PATH_MARKER}. Everything before it is dropped and the query
     * string is removed. That makes the signature independent of:
     *
     *  - the api frontend prefix: Flarum strips `/api` before the middleware
     *    stack runs, so the controller sees `/mc-bridge/heartbeat` while the
     *    client requested `/api/mc-bridge/heartbeat`;
     *  - a Flarum installation in a sub-directory (`/forum/api/mc-bridge/...`).
     *
     * Signing the raw request path does NOT work: the two sides would disagree
     * on the very first segment.
     */
    public static function normalizePath(string $path): string
    {
        $path = parse_url($path, PHP_URL_PATH) ?: '/';
        $path = '/' . ltrim($path, '/');

        $position = strpos($path, self::PATH_MARKER);

        if ($position !== false) {
            $path = substr($path, $position);
        }

        return $path === '' ? '/' : $path;
    }
}
