<?php

namespace Stalir\McBridge\Model;

use Carbon\Carbon;
use Flarum\Database\AbstractModel;

/**
 * Last known state of one Minecraft server.
 *
 * @property int    $id
 * @property string $server_key
 * @property bool   $online
 * @property int    $players_online
 * @property int    $players_max
 * @property float  $tps
 * @property float  $mspt
 * @property string $version
 * @property string $motd
 * @property array  $player_names
 * @property \Carbon\Carbon $last_heartbeat_at
 */
class McServer extends AbstractModel
{
    protected $table = 'mc_servers';

    /**
     * Flarum's AbstractModel turns timestamps off by default; this model has
     * created_at/updated_at columns and relies on them.
     */
    public $timestamps = true;

    protected $casts = [
        'online' => 'boolean',
        'players_online' => 'integer',
        'players_max' => 'integer',
        'tps' => 'float',
        'mspt' => 'float',
        'player_names' => 'array',
        'last_heartbeat_at' => 'datetime',
    ];

    /**
     * How long (seconds) a heartbeat keeps the server considered "online".
     */
    public const STALE_AFTER = 120;

    public function isFresh(): bool
    {
        if (! $this->last_heartbeat_at) {
            return false;
        }

        return $this->last_heartbeat_at->greaterThan(Carbon::now()->subSeconds(self::STALE_AFTER));
    }

    public function toApiPayload(): array
    {
        return [
            'server_key' => $this->server_key,
            'online' => $this->online && $this->isFresh(),
            'players_online' => $this->players_online,
            'players_max' => $this->players_max,
            'tps' => $this->tps,
            'mspt' => $this->mspt,
            'version' => $this->version,
            'motd' => $this->motd,
            'player_names' => $this->player_names ?: [],
            'last_heartbeat_at' => $this->last_heartbeat_at?->toIso8601String(),
        ];
    }
}
