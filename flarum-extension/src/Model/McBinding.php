<?php

namespace Stalir\McBridge\Model;

use Flarum\Database\AbstractModel;
use Flarum\User\User;

/**
 * A confirmed link between a Flarum account and a Minecraft player.
 *
 * @property int    $id
 * @property int    $user_id
 * @property string $player_uuid
 * @property string $player_name
 * @property string $server_key
 */
class McBinding extends AbstractModel
{
    protected $table = 'mc_bindings';

    /**
     * Mass assignment must be allowed here: the link controller uses
     * firstOrNew()/fill(), and Eloquent rejects unguarded attributes.
     */
    protected $fillable = [
        'user_id',
        'player_uuid',
        'player_name',
        'server_key',
    ];

    /** Flarum's AbstractModel disables timestamps by default. */
    public $timestamps = true;

    public function user()
    {
        return $this->belongsTo(User::class, 'user_id');
    }

    public function toApiPayload(): array
    {
        return [
            'user_id' => $this->user_id,
            'username' => $this->user?->username,
            'player_uuid' => $this->player_uuid,
            'player_name' => $this->player_name,
            'server_key' => $this->server_key,
            'linked_at' => $this->created_at?->toIso8601String(),
        ];
    }
}
