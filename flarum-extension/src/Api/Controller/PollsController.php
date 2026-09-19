<?php

namespace Stalir\McBridge\Api\Controller;

use Carbon\Carbon;
use FoF\Polls\Poll;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;

/**
 * GET /api/mc-bridge/polls
 *
 * Exposes the forum's own fof/polls polls to the game servers.
 *
 * The bridge does not own polls - fof/polls does. This endpoint only reads
 * them, so the forum page and the in-game view can never disagree, and poll
 * administration stays where it belongs: the forum UI.
 *
 * Only *global* polls are exposed (post_id is null). Discussion polls belong to
 * a specific thread and would be noise in game.
 *
 * There is deliberately no "already announced" flag on this side. Every server
 * in a network polls this endpoint and announces what it finds, which is what
 * multi-server installs need; a forum-side flag would let exactly one server
 * announce a poll and leave the others silent. The plugin de-duplicates in
 * memory instead (a restart may therefore repeat the newest announcement once).
 */
class PollsController extends AbstractBridgeController
{
    /** Most recent polls/announcements handed to a server in one response. */
    private const MAX_POLLS = 5;

    /** How far back a closed poll is still offered as a result. */
    private const RECENT_RESULT_HOURS = 24;

    public function handle(ServerRequestInterface $request): ResponseInterface
    {
        if ($error = $this->assertMachine($request)) {
            return $error;
        }

        $serverKey = $this->resolveServerKey($request, $request->getQueryParams());

        if ($serverKey === null) {
            return $this->fail('server_key_required', 422);
        }

        // fof/polls is an optional companion, not a hard dependency. A forum
        // without it reports polls as unavailable instead of erroring.
        if (! class_exists(Poll::class)) {
            return $this->json([
                'ok' => true,
                'available' => false,
                'polls' => [],
                'results' => [],
            ]);
        }

        $open = $this->globalPolls()
            ->where(function ($query) {
                $query->whereNull('end_date')->orWhere('end_date', '>', Carbon::now());
            })
            ->orderByDesc('id')
            ->limit(self::MAX_POLLS)
            ->get();

        $ended = $this->globalPolls()
            ->whereNotNull('end_date')
            ->where('end_date', '<=', Carbon::now())
            ->where('end_date', '>=', Carbon::now()->subHours(self::RECENT_RESULT_HOURS))
            ->orderByDesc('end_date')
            ->limit(self::MAX_POLLS)
            ->get();

        return $this->json([
            'ok' => true,
            'available' => true,
            'polls' => $open->map(fn (Poll $poll) => $this->pollPayload($poll))->values()->all(),
            'results' => $ended->map(fn (Poll $poll) => $this->resultPayload($poll))->values()->all(),
        ]);
    }

    /**
     * A fresh query for global, published polls.
     *
     * Returned as a factory on purpose: an Eloquent builder is mutable, so
     * sharing one instance between the two branches would leak the first
     * branch's constraints into the second.
     */
    private function globalPolls()
    {
        return Poll::query()
            ->whereNull('post_id')
            ->whereNotNull('published_at')
            ->with('options');
    }

    private function pollPayload(Poll $poll): array
    {
        $options = [];
        $number = 1;

        foreach ($poll->options as $option) {
            $options[] = [
                'number' => $number++,
                'id' => (int) $option->id,
                'answer' => (string) $option->answer,
            ];
        }

        return [
            'id' => (int) $poll->id,
            'question' => (string) $poll->question,
            'subtitle' => $poll->subtitle,
            'options' => $options,
            'multiple' => (bool) $poll->allow_multiple_votes,
            'max_votes' => (int) $poll->max_votes,
            'can_change_vote' => (bool) $poll->allow_change_vote,
            'ends_at' => $poll->end_date?->toIso8601String(),
            'url' => '/polls/view/'.$poll->id,
        ];
    }

    private function resultPayload(Poll $poll): array
    {
        $options = [];
        $total = 0;
        $number = 1;

        foreach ($poll->options as $option) {
            $votes = (int) $option->vote_count;
            $total += $votes;

            $options[] = [
                'number' => $number++,
                'answer' => (string) $option->answer,
                'votes' => $votes,
            ];
        }

        $winner = null;
        $best = 0;

        foreach ($options as $index => $option) {
            if ($option['votes'] > $best) {
                $best = $option['votes'];
                $winner = $index + 1;
            }
        }

        return [
            'id' => (int) $poll->id,
            'question' => (string) $poll->question,
            'options' => $options,
            'total_votes' => $total,
            'winner_number' => $winner,
            'url' => '/polls/view/'.$poll->id,
        ];
    }
}
