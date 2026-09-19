<?php

namespace Stalir\McBridge\Api\Controller;

use Flarum\Http\RequestUtil;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McReport;

/**
 * POST /api/mc-bridge/report
 *
 * Accepts a player report from the game server and stores it for forum
 * moderators to review. Only machine-authenticated requests are accepted
 * (HMAC signature required).
 */
class ReportController extends AbstractBridgeController
{
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

        return $this->json([
            'ok' => true,
            'report_id' => $report->id,
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
