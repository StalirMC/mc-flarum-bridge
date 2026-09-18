<?php

namespace Stalir\McBridge\Api;

use Flarum\Api\Context;
use Flarum\Api\Schema;
use Flarum\User\User;
use Stalir\McBridge\Model\McBinding;

/**
 * Exposes the Minecraft binding on the user resource.
 *
 * The forum frontend needs this in two places - the profile page and next to the
 * author of every post - and both read the same payload. Putting the value on the
 * user resource means it travels with the posts that already include their
 * author, instead of one extra request per author.
 *
 * Visibility is the documented policy: only signed-in users receive the fields,
 * guests get them stripped by the schema.
 *
 * Cost: one indexed lookup per distinct user in the payload. That is deliberate.
 * Loading every binding of the request would scan a table that grows with the
 * number of linked accounts, and caching one across requests would risk serving
 * a stale value after an unlink; a page carries on the order of twenty authors.
 */
class UserResourceFields
{
    public function __invoke(): array
    {
        $visibleToMembers = fn (User $user, Context $context) => $context->getActor()->isRegistered();

        return [
            Schema\Str::make('mcBridgePlayerName')
                ->visible($visibleToMembers)
                ->nullable()
                ->get(fn (User $user) => $this->binding($user)?->player_name),

            Schema\Str::make('mcBridgeServerKey')
                ->visible($visibleToMembers)
                ->nullable()
                ->get(fn (User $user) => $this->binding($user)?->server_key),

            Schema\DateTime::make('mcBridgeLinkedAt')
                ->visible($visibleToMembers)
                ->nullable()
                ->get(fn (User $user) => $this->binding($user)?->created_at),
        ];
    }

    /**
     * The binding of one user, or null.
     *
     * The link controller keeps at most one row per Flarum account, so taking the
     * first match is the whole lookup.
     */
    private function binding(User $user): ?McBinding
    {
        if (! $user->exists) {
            return null;
        }

        return McBinding::where('user_id', $user->id)->first();
    }
}
