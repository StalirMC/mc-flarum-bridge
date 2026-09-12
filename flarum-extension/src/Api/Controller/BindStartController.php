<?php

namespace Stalir\McBridge\Api\Controller;

use Carbon\Carbon;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McBindCode;
use Stalir\McBridge\Model\McBinding;

/**
 * POST /api/mc-bridge/bind/start
 *
 * Called from in-game `/bind`. Issues a single-use code the player then enters
 * on the forum to link the game account to their Flarum account.
 */
class BindStartController extends AbstractBridgeController
{
    public function handle(ServerRequestInterface $request): ResponseInterface
    {
        if ($error = $this->assertMachine($request)) {
            return $error;
        }

        $body = $this->body($request);
        $serverKey = $this->resolveServerKey($request, $body);

        if ($serverKey === null) {
            return $this->error('A valid server_key is required.', 422);
        }

        $uuid = $this->sanitizeUuid($body['player_uuid'] ?? $body['uuid'] ?? null);

        if ($uuid === null) {
            return $this->error('A valid player_uuid is required.', 422);
        }

        $username = isset($body['player_name']) || isset($body['username'])
            ? mb_substr(trim((string) ($body['player_name'] ?? $body['username'])), 0, 64)
            : null;

        $existing = McBinding::with('user')->where('player_uuid', $uuid)->first();

        if ($existing) {
            return $this->json([
                'ok' => true,
                'already_bound' => true,
                'binding' => $existing->toApiPayload(),
            ]);
        }

        // Invalidate any previous unused code for this player.
        McBindCode::where('player_uuid', $uuid)->whereNull('used_at')->delete();

        $code = McBindCode::generateCode();

        $record = new McBindCode();
        $record->code = $code;
        $record->player_uuid = $uuid;
        $record->player_name = $username;
        $record->server_key = $serverKey;
        $record->expires_at = Carbon::now()->addMinutes(McBindCode::TTL_MINUTES);
        $record->save();

        return $this->json([
            'ok' => true,
            'already_bound' => false,
            'code' => $code,
            'expires_at' => $record->expires_at->toIso8601String(),
            'expires_in_seconds' => McBindCode::TTL_MINUTES * 60,
            'link_url' => '/settings',
        ], 201);
    }

    private function sanitizeUuid(mixed $uuid): ?string
    {
        if (! is_string($uuid)) {
            return null;
        }

        $uuid = strtolower(trim($uuid));

        return preg_match('/^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/', $uuid)
            ? $uuid
            : null;
    }
}
