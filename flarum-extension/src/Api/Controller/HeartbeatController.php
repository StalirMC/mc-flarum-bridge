<?php

namespace Stalir\McBridge\Api\Controller;

use Carbon\Carbon;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McOutboxMessage;
use Stalir\McBridge\Model\McServer;

/**
 * POST /api/mc-bridge/heartbeat
 *
 * Periodic status report: upserts the server row and acknowledges how many
 * messages are waiting to be pulled.
 */
class HeartbeatController extends AbstractBridgeController
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

        $playerNames = $this->sanitizePlayerNames($body['player_names'] ?? []);

        $server = McServer::firstOrNew(['server_key' => $serverKey]);
        $server->online = (bool) ($body['online'] ?? true);
        $server->players_online = max(0, (int) ($body['players_online'] ?? count($playerNames)));
        $server->players_max = max(0, (int) ($body['players_max'] ?? 0));
        $server->tps = isset($body['tps']) && is_numeric($body['tps']) ? (float) $body['tps'] : null;
        $server->mspt = isset($body['mspt']) && is_numeric($body['mspt']) ? (float) $body['mspt'] : null;
        $server->version = isset($body['version']) ? mb_substr((string) $body['version'], 0, 64) : $server->version;
        $server->motd = isset($body['motd']) ? mb_substr((string) $body['motd'], 0, 255) : $server->motd;
        $server->player_names = $playerNames;
        $server->last_heartbeat_at = Carbon::now();
        $server->save();

        $pending = McOutboxMessage::query()
            ->whereNull('delivered_at')
            ->where(function ($query) use ($serverKey) {
                $query->whereNull('server_key')->orWhere('server_key', $serverKey);
            })
            ->count();

        return $this->json([
            'ok' => true,
            'server' => $server->toApiPayload(),
            'pending_messages' => $pending,
            'server_time' => Carbon::now()->toIso8601String(),
        ]);
    }

    /**
     * @param  mixed $names
     * @return string[]
     */
    private function sanitizePlayerNames(mixed $names): array
    {
        if (! is_array($names)) {
            return [];
        }

        $clean = [];

        foreach ($names as $name) {
            if (! is_string($name)) {
                continue;
            }

            $name = trim($name);

            if ($name === '') {
                continue;
            }

            $clean[] = mb_substr($name, 0, 64);
        }

        return array_slice(array_values(array_unique($clean)), 0, 500);
    }
}
