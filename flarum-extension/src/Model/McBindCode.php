<?php

namespace Stalir\McBridge\Model;

use Flarum\Database\AbstractModel;

/**
 * A short lived, single-use code that proves ownership of a Minecraft account.
 *
 * @property int    $id
 * @property string $code
 * @property string $player_uuid
 * @property string $player_name
 * @property string $server_key
 * @property int    $user_id
 * @property \Carbon\Carbon $expires_at
 * @property \Carbon\Carbon $used_at
 */
class McBindCode extends AbstractModel
{
    protected $table = 'mc_bind_codes';

    /**
     * Mass assignment must be allowed here: bind/start saves a new code, and
     * Eloquent rejects unguarded attributes.
     */
    protected $fillable = [
        'code',
        'player_uuid',
        'player_name',
        'server_key',
        'user_id',
        'expires_at',
        'used_at',
    ];

    /** Flarum's AbstractModel disables timestamps by default. */
    public $timestamps = true;

    protected $casts = [
        'expires_at' => 'datetime',
        'used_at' => 'datetime',
    ];

    /** Lifetime of a freshly issued code, in minutes. */
    public const TTL_MINUTES = 10;

    public static function generateCode(): string
    {
        // Unambiguous alphabet (no 0/O/1/I) to make the code easy to type in chat.
        $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
        $code = '';

        for ($i = 0; $i < 8; $i++) {
            $code .= $alphabet[random_int(0, strlen($alphabet) - 1)];
        }

        return $code;
    }

    public function isUsable(): bool
    {
        return $this->used_at === null && $this->expires_at !== null && $this->expires_at->isFuture();
    }
}
