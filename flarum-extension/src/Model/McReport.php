<?php

namespace Stalir\McBridge\Model;

use Flarum\Database\AbstractModel;

/**
 * A player report submitted from the game server.
 *
 * @property int         $id
 * @property string      $server_key
 * @property string      $reporter_uuid
 * @property string|null $reporter_name
 * @property string      $target_name
 * @property string      $reason
 * @property string      $status
 * @property int|null    $discussion_id
 * @property string|null $context
 * @property \Carbon\Carbon $created_at
 */
class McReport extends AbstractModel
{
    protected $table = 'mc_reports';

    /** Filed, waiting for a moderator. */
    public const STATUS_PENDING = 'pending';

    /** A moderator dealt with it. */
    public const STATUS_RESOLVED = 'resolved';

    /** A moderator dismissed it. */
    public const STATUS_REJECTED = 'rejected';

    /**
     * Mass assignment must be allowed here: the report controller fills these on
     * a new report, and Eloquent rejects unguarded attributes.
     */
    protected $fillable = [
        'server_key',
        'reporter_uuid',
        'reporter_name',
        'target_name',
        'reason',
        'status',
        'discussion_id',
        'context',
    ];

    /** Flarum's AbstractModel disables timestamps by default. */
    public $timestamps = true;

    /**
     * True once a moderator has decided this report.
     *
     * Used to make the outcome notification fire exactly once, no matter how many
     * times the tags are edited afterwards.
     */
    public function isClosed(): bool
    {
        return $this->status === self::STATUS_RESOLVED || $this->status === self::STATUS_REJECTED;
    }

    /**
     * What the game server is told about a report it filed.
     *
     * Deliberately narrow: this is the payload behind {@code /report status},
     * which a player reads about their own reports, so it carries what they
     * wrote and what became of it - and nothing about anybody else.
     */
    public function toApiPayload(): array
    {
        return [
            'id' => $this->id,
            'target_name' => $this->target_name,
            'reason' => $this->reason,
            'status' => $this->status,
            'created_at' => $this->created_at?->toIso8601String(),
        ];
    }
}
