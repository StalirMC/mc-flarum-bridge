<?php

namespace Stalir\McBridge\Model;

use Flarum\Database\AbstractModel;

/**
 * A single gameplay event reported by a server (join, quit, death, ...).
 *
 * @property int    $id
 * @property string $server_key
 * @property string $type
 * @property string $player_uuid
 * @property string $player_name
 * @property string $message
 * @property \Carbon\Carbon $happened_at
 */
class McEvent extends AbstractModel
{
    protected $table = 'mc_events';

    /**
     * Mass assignment must be allowed here: the events controller uses
     * firstOrNew()/fill(), and Eloquent rejects unguarded attributes.
     */
    protected $fillable = [
        'server_key',
        'type',
        'player_uuid',
        'player_name',
        'message',
        'happened_at',
    ];

    /** Flarum's AbstractModel disables timestamps by default. */
    public $timestamps = true;

    protected $casts = [
        'happened_at' => 'datetime',
    ];

    /** Event types accepted from the plugin. */
    public const ALLOWED_TYPES = [
        'join',
        'quit',
        'death',
        'advancement',
        'chat',
        'command',
        'start',
        'stop',
        'custom',
    ];

    public function toApiPayload(): array
    {
        return [
            'id' => $this->id,
            'server_key' => $this->server_key,
            'type' => $this->type,
            'player_uuid' => $this->player_uuid,
            'player_name' => $this->player_name,
            'message' => $this->message,
            'happened_at' => $this->happened_at?->toIso8601String(),
        ];
    }
}
