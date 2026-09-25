<?php

namespace Stalir\McBridge\Api\Controller;

use Flarum\Http\RequestUtil;
use Flarum\Settings\SettingsRepositoryInterface;
use Illuminate\Cache\Repository as CacheRepository;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McReport;
use Stalir\McBridge\Service\BridgeMessages;
use Stalir\McBridge\Service\ReportDiscussion;

/**
 * POST /api/mc-bridge/report
 *
 * Accepts a player report from the game server, stores it for the record and
 * files it as a discussion in the forum's report tag so moderators actually see
 * it. Only machine-authenticated requests are accepted (HMAC signature
 * required).
 */
class ReportController extends AbstractBridgeController
{
    public function __construct(
        SettingsRepositoryInterface $settings,
        CacheRepository $cache,
        BridgeMessages $messages,
        protected ReportDiscussion $discussions
    ) {
        parent::__construct($settings, $cache, $messages);
    }

    public function handle(ServerRequestInterface $request): ResponseInterface
    {
        if ($error = $this->assertMachine($request)) {
            return $error;
        }

        $body = $this->body($request);
        $serverKey = $this->resolveServerKey($request, $body);

        if ($serverKey === null) {
            return $this->fail('server_key_required', 422);
        }

        $reporterUuid = $this->sanitizeUuid($body['reporter_uuid'] ?? null);

        if ($reporterUuid === null) {
            return $this->fail('reporter_uuid_invalid', 422);
        }

        $reporterName = isset($body['reporter_name'])
            ? mb_substr(trim((string) $body['reporter_name']), 0, 64)
            : null;

        $targetName = isset($body['target_name'])
            ? mb_substr(trim((string) $body['target_name']), 0, 64)
            : null;

        if ($targetName === null || $targetName === '') {
            return $this->fail('target_name_required', 422);
        }

        $reason = isset($body['reason'])
            ? mb_substr(trim((string) $body['reason']), 0, 1000)
            : null;

        if ($reason === null || $reason === '') {
            return $this->fail('reason_required', 422);
        }

        $report = new McReport();
        $report->server_key = $serverKey;
        $report->reporter_uuid = $reporterUuid;
        $report->reporter_name = $reporterName;
        $report->target_name = $targetName;
        $report->reason = $reason;
        $report->status = 'pending';
        $report->save();

        // File it where moderators actually work. The report is already on
        // record, so this is best-effort: a problem here is logged and reported
        // as a null discussion_id rather than failing the player's /report.
        $discussionId = $this->discussions->create($report);

        return $this->json([
            'ok' => true,
            'report_id' => $report->id,
            'discussion_id' => $discussionId,
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
