<?php

namespace Stalir\McBridge\Api\Controller;

use Flarum\Settings\SettingsRepositoryInterface;
use Illuminate\Cache\Repository as CacheRepository;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McReport;
use Stalir\McBridge\Service\BridgeMessages;

/**
 * GET /api/mc-bridge/reports?server_key=survival&reporter_uuid=...&limit=5
 *
 * The reports one player has filed, newest first, which is what the game side
 * needs to answer {@code /report status}.
 *
 * Scoped to the reporter's own UUID and to the calling server: a player learns
 * the fate of their own reports and nothing else - not the outcome of anybody
 * else's, and not the identity of anyone who reported them.
 */
class ReportsController extends AbstractBridgeController
{
    private const DEFAULT_LIMIT = 5;
    private const MAX_LIMIT = 20;

    public function __construct(
        SettingsRepositoryInterface $settings,
        CacheRepository $cache,
        BridgeMessages $messages
    ) {
        parent::__construct($settings, $cache, $messages);
    }

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

        $reporterUuid = $this->sanitizeUuid($query['reporter_uuid'] ?? null);

        if ($reporterUuid === null) {
            return $this->fail('reporter_uuid_invalid', 422);
        }

        $limit = (int) ($query['limit'] ?? self::DEFAULT_LIMIT);
        $limit = max(1, min(self::MAX_LIMIT, $limit));

        // Newest first: a player asking about their reports wants the last one,
        // not the first one.
        $reports = McReport::query()
            ->where('server_key', $serverKey)
            ->where('reporter_uuid', $reporterUuid)
            ->orderByDesc('id')
            ->limit($limit)
            ->get();

        return $this->json([
            'ok' => true,
            'server_key' => $serverKey,
            'reports' => $reports->map(fn (McReport $report) => $report->toApiPayload())->all(),
        ]);
    }
}
