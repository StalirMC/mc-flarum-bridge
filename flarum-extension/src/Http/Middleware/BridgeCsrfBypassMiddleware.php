<?php

namespace Stalir\McBridge\Http\Middleware;

use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Psr\Http\Server\MiddlewareInterface;
use Psr\Http\Server\RequestHandlerInterface;

/**
 * Lets signed machine requests through Flarum's CSRF gate.
 *
 * Why this is needed
 * ------------------
 * Flarum applies {@see \Flarum\Http\Middleware\CheckCsrfToken} to the whole
 * `api` middleware stack, and only exempts the `token` and `registration-token`
 * routes. A Minecraft server has no session and no CSRF token, so every POST to
 * /api/mc-bridge/* would otherwise be rejected with a 400 before the HMAC
 * check ever ran.
 *
 * Why this is safe
 * ----------------
 * The bypass is granted only when the request actually carries the bridge
 * signature header, i.e. it claims to be a machine call - and machine calls are
 * authenticated by HMAC instead of by a session. A cross-site attacker cannot
 * add a custom header to a forged request without a successful CORS preflight,
 * so this does not open a CSRF hole for the session-based endpoints:
 *
 *   - POST /api/mc-bridge/link and DELETE /api/mc-bridge/link use the forum
 *     session and therefore still require a valid CSRF token;
 *   - POST /api/mc-bridge/broadcast from a browser session still requires a
 *     valid CSRF token; only the signed machine path is exempted.
 *
 * Must be registered with insertBefore(CheckCsrfToken::class, ...) - appending
 * it with add() would run it after the check and be useless.
 */
class BridgeCsrfBypassMiddleware implements MiddlewareInterface
{
    private const SIGNATURE_HEADER = 'X-MC-Signature';
    private const PREFIX = '/api/mc-bridge/';

    public function process(ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface
    {
        $path = $request->getUri()->getPath();

        if (str_contains($path, self::PREFIX) && $request->hasHeader(self::SIGNATURE_HEADER)) {
            $request = $request->withAttribute('bypassCsrfToken', true);
        }

        return $handler->handle($request);
    }
}
