<?php

namespace Stalir\McBridge\Api\Controller;

use Carbon\Carbon;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McEvent;

/**
 * POST /api/mc-bridge/events
 *
 * Accepts one event object or a batch:
 *   { "server_key": "survival", "events": [ { "type": "join", ... } ] }
 */
class EventController extends AbstractBridgeController
{
    private const MAX_BATCH = 100;

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

        $events = $body['events'] ?? null;

        if ($events === null) {
            $events = [$body];
        }

        if (! is_array($events) || $events === []) {
            return $this->fail('events_empty', 422);
        }

        $events = array_slice(array_values($events), 0, self::MAX_BATCH);
        $stored = 0;
        $rejected = [];

        foreach ($events as $index => $event) {
            if (! is_array($event)) {
                $rejected[] = ['index' => $index, 'reason' => $this->messages->get('api.error.event_not_object')];
                continue;
            }

            $type = $event['type'] ?? null;

            if (! is_string($type) || ! in_array($type, McEvent::ALLOWED_TYPES, true)) {
                $rejected[] = ['index' => $index, 'reason' => $this->messages->get('api.error.event_type_unsupported')];
                continue;
            }

            $record = new McEvent();
            $record->server_key = $serverKey;
            $record->type = $type;
            $record->player_uuid = $this->sanitizeUuid($event['player_uuid'] ?? $event['uuid'] ?? null);
            $record->player_name = isset($event['player_name']) || isset($event['username'])
                ? mb_substr((string) ($event['player_name'] ?? $event['username']), 0, 64)
                : null;
            $record->message = isset($event['message']) ? mb_substr((string) $event['message'], 0, 4000) : null;
            $record->happened_at = $this->parseDate($event['happened_at'] ?? null);
            $record->save();

            $stored++;
        }

        return $this->json([
            'ok' => true,
            'stored' => $stored,
            'rejected' => $rejected,
        ], $stored > 0 ? 201 : 422);
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

    private function parseDate(mixed $value): Carbon
    {
        if (is_string($value) && $value !== '') {
            try {
                return Carbon::parse($value);
            } catch (\Throwable) {
                // Fall through to "now".
            }
        }

        return Carbon::now();
    }
}
