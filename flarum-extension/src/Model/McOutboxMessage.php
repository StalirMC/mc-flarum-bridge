<?php

namespace Stalir\McBridge\Model;

use Flarum\Database\AbstractModel;

/**
 * A message queued on the forum side, waiting to be pulled by a game server.
 *
 * @property int    $id
 * @property string $server_key
 * @property string $type
 * @property string $title
 * @property string $body
 * @property string $url
 * @property array  $payload
 * @property string $target_uuid
 * @property int    $actor_id
 * @property \Carbon\Carbon $delivered_at
 */
class McOutboxMessage extends AbstractModel
{
    protected $table = 'mc_outbox';

    /**
     * Mass assignment must be allowed here: the broadcast controller saves a
     * new message, and Eloquent rejects unguarded attributes.
     */
    protected $fillable = [
        'server_key',
        'type',
        'title',
        'body',
        'url',
        'payload',
        'target_uuid',
        'actor_id',
        'delivered_at',
    ];

    /** Flarum's AbstractModel disables timestamps by default. */
    public $timestamps = true;

    protected $casts = [
        'payload' => 'array',
        'delivered_at' => 'datetime',
    ];

    public const TYPE_ANNOUNCEMENT = 'announcement';
    public const TYPE_BROADCAST = 'broadcast';
    public const TYPE_COMMAND = 'command';
    public const TYPE_BIND_SUCCESS = 'bind_success';

    public function toApiPayload(): array
    {
        return [
            'id' => $this->id,
            'type' => $this->type,
            'title' => $this->title,
            'body' => $this->body,
            'url' => $this->url,
            'payload' => $this->payload ?: [],
            'target_uuid' => $this->target_uuid,
            'actor_id' => $this->actor_id,
            'created_at' => $this->created_at?->toIso8601String(),
        ];
    }
}
