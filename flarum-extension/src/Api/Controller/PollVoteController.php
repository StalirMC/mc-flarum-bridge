<?php

namespace Stalir\McBridge\Api\Controller;

use Flarum\Foundation\ValidationException;
use Flarum\Settings\SettingsRepositoryInterface;
use Flarum\User\Exception\PermissionDeniedException;
use Flarum\User\User;
use FoF\Polls\Commands\MultipleVotesPoll;
use FoF\Polls\Poll;
use Illuminate\Cache\Repository as CacheRepository;
use Illuminate\Contracts\Bus\Dispatcher;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McBinding;
use Stalir\McBridge\Service\BridgeMessages;

/**
 * POST /api/mc-bridge/polls/vote
 *
 * Records an in-game vote in the forum's own fof/polls poll.
 *
 * The vote is cast *as the forum account the player bound with /bind*, which is
 * what makes the two views agree: the player shows up as a real voter on the
 * forum, the vote counts towards the poll's totals, and fof/polls' own rules
 * (single/multiple choice, max votes, change-vote permission, end date) are
 * enforced because the extension's own MultipleVotesPoll command does the work.
 *
 * A player who has not linked a forum account cannot vote: there is no one to
 * attribute the vote to. That is reported as `poll_binding_required` so the
 * plugin can point at /bind.
 */
class PollVoteController extends AbstractBridgeController
{
    public function __construct(
        SettingsRepositoryInterface $settings,
        CacheRepository $cache,
        BridgeMessages $messages,
        protected Dispatcher $bus
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

        // Polls are an optional companion extension: without it there is
        // nothing to vote in.
        if (! class_exists(Poll::class) || ! class_exists(MultipleVotesPoll::class)) {
            return $this->fail('polls_unavailable', 409);
        }

        $pollId = isset($body['poll_id']) ? (int) $body['poll_id'] : 0;

        if ($pollId <= 0) {
            return $this->fail('poll_id_required', 422);
        }

        $uuid = $this->sanitizeUuid($body['player_uuid'] ?? $body['uuid'] ?? null);

        if ($uuid === null) {
            return $this->fail('player_uuid_invalid', 422);
        }

        $rawOptionIds = $body['option_ids'] ?? null;

        if (! is_array($rawOptionIds) || $rawOptionIds === []) {
            return $this->fail('poll_options_required', 422);
        }

        $optionIds = [];

        foreach ($rawOptionIds as $optionId) {
            $optionId = (int) $optionId;

            if ($optionId > 0) {
                $optionIds[] = $optionId;
            }
        }

        if ($optionIds === []) {
            return $this->fail('poll_options_required', 422);
        }

        /** @var McBinding|null $binding */
        $binding = McBinding::where('player_uuid', $uuid)->first();

        if (! $binding || ! $binding->user_id) {
            return $this->fail('poll_binding_required', 409);
        }

        /** @var User|null $actor */
        $actor = User::find($binding->user_id);

        if (! $actor) {
            return $this->fail('poll_binding_required', 409);
        }

        $poll = Poll::find($pollId);

        if (! $poll) {
            return $this->fail('poll_not_found', 404);
        }

        try {
            $this->bus->dispatch(new MultipleVotesPoll($actor, $pollId, ['optionIds' => $optionIds]));
        } catch (PermissionDeniedException $exception) {
            // Covers "poll already ended" and a missing polls.vote permission;
            // both mean the forum would not accept this vote either.
            return $this->fail('poll_vote_denied', 403);
        } catch (ValidationException $exception) {
            // Bad option ids, or more options than the poll allows.
            return $this->fail('poll_vote_invalid', 422);
        }

        $poll->refresh();

        return $this->json([
            'ok' => true,
            'poll_id' => $pollId,
            'voter' => $actor->username,
            'total_votes' => (int) $poll->vote_count,
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
