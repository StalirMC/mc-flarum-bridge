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
    /**
     * How long a report_uid stays claimed, in seconds.
     *
     * Matches the signature nonce window (BridgeCrypto::MAX_SKEW * 2), which is
     * as long as a client-side retry can realistically arrive after the original
     * request timed out.
     */
    private const IDEMPOTENCY_SECONDS = 600;

    /**
     * Longest transcript accepted from a game server, in characters.
     *
     * The plugin caps the same thing at 50 lines of 256 characters; this is the
     * server-side half of that agreement.
     */
    private const MAX_CONTEXT_LENGTH = 12000;

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

        // Idempotency. The plugin retries a report when the forum does not answer
        // in time, and a client-side timeout says nothing about whether the first
        // request was processed - in practice it usually was. The uid it sends
        // makes that retry harmless: the second request reuses the first result
        // instead of filing the report twice.
        $reportUid = $this->sanitizeUuid($body['report_uid'] ?? null);
        $seenKey = $reportUid === null
            ? null
            : 'mc-bridge:report:' . hash('sha256', $reportUid);

        if ($seenKey !== null) {
            $seen = $this->cache->get($seenKey);

            if (is_array($seen)) {
                return $this->json([
                    'ok' => true,
                    'report_id' => $seen['report_id'] ?? null,
                    'discussion_id' => $seen['discussion_id'] ?? null,
                    'duplicate' => true,
                ]);
            }
        }

        $report = new McReport();
        $report->server_key = $serverKey;
        $report->reporter_uuid = $reporterUuid;
        $report->reporter_name = $reporterName;
        $report->target_name = $targetName;
        $report->reason = $reason;
        $report->status = McReport::STATUS_PENDING;
        // Only present when the game server sends one. The transcript is capped
        // here as well as on the client, so a modified client cannot fill the
        // column with something else entirely.
        $report->context = $this->optionalText($body, 'context', self::MAX_CONTEXT_LENGTH);
        $report->save();

        if ($seenKey !== null) {
            // Claimed before the slow half: a retry that arrives while the
            // discussion is still being created must not file a second report.
            $this->cache->add($seenKey, [
                'report_id' => $report->id,
                'discussion_id' => null,
            ], self::IDEMPOTENCY_SECONDS);
        }

        // File it where moderators actually work. The report is already on
        // record, so this is best-effort: a problem here is logged and reported
        // as a null discussion_id rather than failing the player's /report.
        $discussionId = $this->discussions->create($report, [
            'title' => $this->optionalText($body, 'title', 255),
            'tags' => $this->optionalTokenList($body, 'tags', 100, 10),
            'actor' => $this->optionalToken($body, 'actor', 64),
        ]);

        if ($discussionId !== null) {
            // Remember which discussion this report became. Without it, a
            // moderator resolving that discussion could not be traced back to the
            // player who filed the report, which is what the outcome notification
            // needs - so this is not merely bookkeeping.
            $report->discussion_id = $discussionId;
            $report->save();
        }

        if ($seenKey !== null) {
            $this->cache->put($seenKey, [
                'report_id' => $report->id,
                'discussion_id' => $discussionId,
            ], self::IDEMPOTENCY_SECONDS);
        }

        return $this->json([
            'ok' => true,
            'report_id' => $report->id,
            'discussion_id' => $discussionId,
        ], 201);
    }

    /**
     * Optional free-text field from the game server, trimmed.
     *
     * Returns null for anything absent, blank or not a string, which is how the
     * layout hints stay optional: a malformed one degrades the discussion layout
     * instead of costing the player their report.
     */
    private function optionalText(array $body, string $key, int $maxLength): ?string
    {
        $value = $body[$key] ?? null;

        if (! is_string($value)) {
            return null;
        }

        $value = trim($value);

        return $value === '' ? null : mb_substr($value, 0, $maxLength);
    }

    /**
     * Optional list of identifiers: the tag slugs or ids the report is filed
     * under.
     *
     * Anything that is not a string, or that contains a character a slug or a tag
     * name cannot, is dropped here rather than handed to the resolver.
     *
     * @return array<int, string>
     */
    private function optionalTokenList(array $body, string $key, int $maxLength, int $maxItems): array
    {
        $value = $body[$key] ?? null;

        if (! is_array($value)) {
            return [];
        }

        $items = [];

        foreach ($value as $entry) {
            if (! is_string($entry)) {
                continue;
            }

            $entry = trim($entry);

            if ($entry === '' || ! $this->isToken($entry)) {
                continue;
            }

            $items[] = mb_substr($entry, 0, $maxLength);

            if (count($items) === $maxItems) {
                break;
            }
        }

        return $items;
    }

    /**
     * Optional identifier field: a tag slug, a username or a numeric id.
     *
     * Anything that cannot be one of those shapes is dropped here rather than
     * handed to the resolver, so a stray quote or newline never reaches a
     * database query.
     */
    private function optionalToken(array $body, string $key, int $maxLength): ?string
    {
        $value = $this->optionalText($body, $key, $maxLength);

        return $value !== null && $this->isToken($value) ? $value : null;
    }

    /**
     * A tag slug, a tag name, a username or a numeric id.
     *
     * Letters, digits, dots, dashes, underscores and spaces only - which is every
     * shape a slug, a Flarum username or an id can take, and nothing that would
     * need escaping anywhere it is used.
     */
    private function isToken(string $value): bool
    {
        return preg_match('/^[\p{L}\p{N}._\- ]+$/u', $value) === 1;
    }
}
