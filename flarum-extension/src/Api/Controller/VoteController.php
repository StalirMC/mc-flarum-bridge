<?php

namespace Stalir\McBridge\Api\Controller;

use Carbon\Carbon;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McActivity;
use Stalir\McBridge\Model\McVote;

/**
 * POST /api/mc-bridge/vote
 *
 * Records one player's vote in an activity poll. The (activity_id, player_uuid)
 * pair is unique, so a player changing their mind replaces their earlier vote
 * instead of adding a second one.
 */
class VoteController extends AbstractBridgeController
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

        $activityId = isset($body['activity_id']) ? (int) $body['activity_id'] : 0;

        if ($activityId <= 0) {
            return $this->fail('activity_id_required', 422);
        }

        $uuid = $this->sanitizeUuid($body['player_uuid'] ?? $body['uuid'] ?? null);

        if ($uuid === null) {
            return $this->fail('player_uuid_invalid', 422);
        }

        $playerName = isset($body['player_name'])
            ? mb_substr(trim((string) $body['player_name']), 0, 64)
            : null;

        $optionIndex = isset($body['option_index']) ? (int) $body['option_index'] : -1;

        /** @var McActivity|null $activity */
        $activity = McActivity::find($activityId);

        if (! $activity) {
            return $this->fail('activity_not_found', 404);
        }

        $options = is_array($activity->options) ? $activity->options : [];

        if ($optionIndex < 0 || $optionIndex >= count($options)) {
            return $this->fail('activity_option_invalid', 422);
        }

        if (! $activity->isOpen()) {
            return $this->fail('activity_closed', 409);
        }

        $vote = McVote::firstOrNew([
            'activity_id' => $activity->id,
            'player_uuid' => $uuid,
        ]);

        $vote->player_name = $playerName;
        $vote->option_index = $optionIndex;
        $vote->save();

        return $this->json([
            'ok' => true,
            'activity_id' => $activity->id,
            'option_index' => $optionIndex,
            'total_votes' => McVote::where('activity_id', $activity->id)->count(),
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
