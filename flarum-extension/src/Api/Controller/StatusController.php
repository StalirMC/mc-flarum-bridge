<?php

namespace Stalir\McBridge\Api\Controller;

use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McEvent;
use Stalir\McBridge\Model\McServer;

/**
 * GET /api/mc-bridge/status
 *
 * Public, read-only snapshot used by the forum front-end to render a server
 * widget. No secrets are exposed: only aggregated state and online names.
 */
class StatusController extends AbstractBridgeController
{
    public function handle(ServerRequestInterface $request): ResponseInterface
    {
        $servers = McServer::query()->orderBy('server_key')->get();

        $totalOnline = 0;

        $payload = $servers->map(function (McServer $server) use (&$totalOnline) {
            $data = $server->toApiPayload();

            if ($data['online']) {
                $totalOnline += $data['players_online'];
            }

            return $data;
        })->all();

        $recent = McEvent::query()
            ->orderByDesc('id')
            ->limit(15)
            ->get()
            ->map(fn (McEvent $event) => $event->toApiPayload())
            ->all();

        return $this->json([
            'ok' => true,
            'totals' => [
                'servers' => count($payload),
                'servers_online' => count(array_filter($payload, fn (array $s) => $s['online'])),
                'players_online' => $totalOnline,
            ],
            'servers' => $payload,
            'recent_events' => $recent,
        ]);
    }
}
