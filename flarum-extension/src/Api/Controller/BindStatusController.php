<?php

namespace Stalir\McBridge\Api\Controller;

use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McBindCode;
use Stalir\McBridge\Model\McBinding;

/**
 * GET /api/mc-bridge/bind/status?uuid=<player uuid>
 *
 * Lets the plugin tell a player whether their account is linked yet.
 */
class BindStatusController extends AbstractBridgeController
{
    public function handle(ServerRequestInterface $request): ResponseInterface
    {
        if ($error = $this->assertMachine($request)) {
            return $error;
        }

        $query = $request->getQueryParams();
        $serverKey = $this->resolveServerKey($request, $query);

        if ($serverKey === null) {
            return $this->fail('server_key_required', 422);
        }

        $uuid = $this->sanitizeUuid($query['uuid'] ?? $query['player_uuid'] ?? null);

        if ($uuid === null) {
            return $this->fail('uuid_required', 422);
        }

        $binding = McBinding::with('user')->where('player_uuid', $uuid)->first();

        if ($binding) {
            return $this->json([
                'ok' => true,
                'bound' => true,
                'binding' => $binding->toApiPayload(),
            ]);
        }

        $pending = McBindCode::where('player_uuid', $uuid)
            ->whereNull('used_at')
            ->orderByDesc('id')
            ->first();

        return $this->json([
            'ok' => true,
            'bound' => false,
            'pending_code' => $pending && $pending->isUsable() ? $pending->code : null,
            'expires_at' => $pending && $pending->isUsable() ? $pending->expires_at->toIso8601String() : null,
        ]);
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
