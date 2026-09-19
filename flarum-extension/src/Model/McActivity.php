<?php

namespace Stalir\McBridge\Model;

use Carbon\Carbon;
use Flarum\Database\AbstractModel;

/**
 * An activity poll announced to the game servers.
 *
 * Players vote in game; the votes land in {@see McVote}. The results are queued
 * back to the game as an outbox message once the poll closes.
 *
 * @property int    $id
 * @property string $server_key
 * @property string $title
 * @property array  $options
 * @property \Carbon\Carbon $closes_at
 * @property bool   $closed
 * @property \Carbon\Carbon $announced_at
 */
class McActivity extends AbstractModel
{
    protected $table = 'mc_activities';

    /**
     * Mass assignment must be allowed here: the activity controller saves a
     * new poll, and Eloquent rejects unguarded attributes.
     */
    protected $fillable = [
        'server_key',
        'title',
        'options',
        'closes_at',
        'closed',
        'announced_at',
    ];

    /** Flarum's AbstractModel disables timestamps by default. */
    public $timestamps = true;

    protected $casts = [
        'options' => 'array',
        'closes_at' => 'datetime',
        'announced_at' => 'datetime',
        'closed' => 'boolean',
    ];

    public function isOpen(): bool
    {
        return ! $this->closed && $this->closes_at !== null && $this->closes_at->isFuture();
    }

    /**
     * Count the votes per option, in option order.
     *
     * @return int[] one entry per option, zero-filled
     */
    public function tally(): array
    {
        $options = is_array($this->options) ? $this->options : [];
        $counts = array_fill(0, count($options), 0);

        foreach (McVote::where('activity_id', $this->id)->get() as $vote) {
            $index = (int) $vote->option_index;

            if (array_key_exists($index, $counts)) {
                $counts[$index]++;
            }
        }

        return $counts;
    }

    public function toApiPayload(): array
    {
        $options = is_array($this->options) ? $this->options : [];

        return [
            'id' => $this->id,
            'title' => $this->title,
            'options' => array_values($options),
            'closes_at' => $this->closes_at?->toIso8601String(),
            'closed' => (bool) $this->closed,
            'open' => $this->isOpen(),
            'total_votes' => McVote::where('activity_id', $this->id)->count(),
            'tally' => $this->tally(),
        ];
    }
}
