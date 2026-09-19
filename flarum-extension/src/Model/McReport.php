<?php

namespace Stalir\McBridge\Model;

use Flarum\Database\AbstractModel;

/**
 * A player report submitted from the game server.
 *
 * @property int    $id
 * @property string $server_key
 * @property string $reporter_uuid
 * @property string $reporter_name
 * @property string $target_name
 * @property string $reason
 * @property string $status
 * @property \Carbon\Carbon $created_at
 */
class McReport extends AbstractModel
{
    protected $table = 'mc_reports';

    /**
     * Mass assignment must be allowed here: the report controller saves a
     * new report, and Eloquent rejects unguarded attributes.
     */
    protected $fillable = [
        'server_key',
        'reporter_uuid',
        'reporter_name',
        'target_name',
        'reason',
        'status',
    ];

    /** Flarum's AbstractModel disables timestamps by default. */
    public $timestamps = true;

    public function toApiPayload(): array
    {
        return [
            'id' => $this->id,
            'server_key' => $this->server_key,
            'reporter_uuid' => $this->reporter_uuid,
            'reporter_name' => $this->reporter_name,
            'target_name' => $this->target_name,
            'reason' => $this->reason,
            'status' => $this->status,
            'created_at' => $this->created_at?->toIso8601String(),
        ];
    }
}
