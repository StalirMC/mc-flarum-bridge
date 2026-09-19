<?php

namespace Stalir\McBridge\Api\Controller;

use Flarum\Http\RequestUtil;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McOutboxMessage;
use Stalir\McBridge\Service\BridgeCrypto;

/**
 * POST /api/mc-bridge/broadcast
 *
 * Queues a message for one server (or all servers). Two callers are accepted:
 *
 *  - a Flarum administrator, using their normal session cookie (and therefore a
 *    valid CSRF token);
 *  - an external automation client, using the HMAC scheme.
 *
 * The choice is made by the presence of the bridge authentication headers, not
 * by whether authentication happened to succeed: a request that carries them is
 * always evaluated as a machine request, so a missing or bad signature yields
 * 401 instead of silently degrading into a confusing 403.
 */
class BroadcastController extends AbstractBridgeController
{
    private const ALLOWED_TYPES = [
        McOutboxMessage::TYPE_BROADCAST,
        McOutboxMessage::TYPE_COMMAND,
        McOutboxMessage::TYPE_ANNOUNCEMENT,
    ];

    public function handle(ServerRequestInterface $request): ResponseInterface
    {
        $body = $this->body($request);

        $claimsMachine = $request->hasHeader(BridgeCrypto::HEADER_SIGNATURE)
            || $request->hasHeader(BridgeCrypto::HEADER_TIMESTAMP)
            || $request->hasHeader(BridgeCrypto::HEADER_NONCE);

        if ($claimsMachine) {
            if ($error = $this->assertMachine($request)) {
                return $error;
            }
        } else {
            $actor = RequestUtil::getActor($request);

            if ($actor->isGuest()) {
                return $this->fail('broadcast_auth_required', 401);
            }

            if (! $actor->isAdmin()) {
                return $this->fail('broadcast_admin_required', 403);
            }

            // This route is exempted from the global CSRF check so that signed
            // machine calls work, so the session path has to enforce it here.
            // Without this a cross-site form post could broadcast on behalf of a
            // logged-in administrator.
            $session = $request->getAttribute('session');
            $provided = $request->getHeaderLine('X-CSRF-Token');

            if (! $session || $provided === '' || ! hash_equals((string) $session->token(), $provided)) {
                return $this->fail('broadcast_csrf_required', 403);
            }
        }

        $type = $body['type'] ?? McOutboxMessage::TYPE_BROADCAST;

        if (! is_string($type) || ! in_array($type, self::ALLOWED_TYPES, true)) {
            return $this->fail('message_type_unsupported', 422);
        }

        $title = isset($body['title']) ? mb_substr(trim((string) $body['title']), 0, 255) : null;
        $text = isset($body['body']) ? mb_substr(trim((string) $body['body']), 0, 4000) : null;

        if (($title === null || $title === '') && ($text === null || $text === '')) {
            return $this->fail('message_content_required', 422);
        }

        $serverKey = $body['server_key'] ?? null;

        if ($serverKey !== null) {
            $serverKey = $this->resolveServerKey($request, $body);

            if ($serverKey === null) {
                return $this->fail('server_key_invalid', 422);
            }
        }

        $message = new McOutboxMessage();
        $message->server_key = $serverKey;
        $message->type = $type;
        $message->title = $title;
        $message->body = $text;
        $message->url = isset($body['url']) ? mb_substr((string) $body['url'], 0, 255) : null;
        $payload = $body['payload'] ?? null;
        $message->payload = is_array($payload) ? $payload : [];
        // Record who queued this message (for audit). Machine calls leave it null.
        if (! $claimsMachine) {
            $actor = RequestUtil::getActor($request);
            $message->actor_id = $actor->id;
        }
        $message->save();

        return $this->json([
            'ok' => true,
            'message' => $message->toApiPayload(),
        ], 201);
    }
}
