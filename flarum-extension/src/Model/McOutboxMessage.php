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
 * @property \Carbon\Carbon $delivered_at
 */
class McOutboxMessage extends AbstractModel
{
    protected $table = 'mc_outbox';

    /** Flarum's AbstractModel disables timestamps by default. */
    public $timestamps = true;

    protected $casts = [
        'payload' => 'array',
        'delivered_at' => 'datetime',
    ];

    public const TYPE_ANNOUNCEMENT = 'announcement';
    public const TYPE_BROADCAST = 'broadcast';
    public const TYPE_COMMAND = 'command';

    public function toApiPayload(): array
    {
        return [
            'id' => $this->id,
            'type' => $this->type,
            'title' => $this->title,
            'body' => $this->body,
            'url' => $this->url,
            'payload' => $this->payload ?: [],
            'created_at' => $this->created_at?->toIso8601String(),
        ];
    }
}
