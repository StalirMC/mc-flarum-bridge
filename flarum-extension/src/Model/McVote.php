<?php

namespace Stalir\McBridge\Model;

use Flarum\Database\AbstractModel;

/**
 * One player's vote in an {@see McActivity}.
 *
 * The (activity_id, player_uuid) pair is unique, so re-voting updates the row
 * instead of adding a second one.
 *
 * @property int    $id
 * @property int    $activity_id
 * @property string $player_uuid
 * @property string $player_name
 * @property int    $option_index
 */
class McVote extends AbstractModel
{
    protected $table = 'mc_votes';

    protected $fillable = [
        'activity_id',
        'player_uuid',
        'player_name',
        'option_index',
    ];

    /** Flarum's AbstractModel disables timestamps by default. */
    public $timestamps = true;
}
