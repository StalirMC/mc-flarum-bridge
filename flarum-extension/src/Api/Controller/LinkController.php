<?php

namespace Stalir\McBridge\Api\Controller;

use Carbon\Carbon;
use Flarum\Http\RequestUtil;
use Flarum\User\Exception\NotAuthenticatedException;
use Illuminate\Database\ConnectionInterface;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McBindCode;
use Stalir\McBridge\Model\McBinding;

/**
 * POST   /api/mc-bridge/link   { "code": "ABCD2345" }   link a game account
 * DELETE /api/mc-bridge/link                            remove the link
 *
 * Both require a logged-in Flarum account; the code proves ownership of the
 * Minecraft account.
 */
class LinkController extends AbstractBridgeController
{
    public function __construct(
        \Flarum\Settings\SettingsRepositoryInterface $settings,
        \Illuminate\Contracts\Cache\Repository $cache,
        protected ConnectionInterface $db
    ) {
        parent::__construct($settings, $cache);
    }

    public function handle(ServerRequestInterface $request): ResponseInterface
    {
        $actor = RequestUtil::getActor($request);

        // Answer with this extension's own error shape rather than letting
        // assertRegistered() bubble up Flarum's JSON:API error envelope, which
        // would make a single endpoint reply in a different format.
        try {
            $actor->assertRegistered();
        } catch (NotAuthenticatedException) {
            return $this->error('You must be logged in to manage a Minecraft account link.', 401);
        }

        return strtoupper($request->getMethod()) === 'DELETE'
            ? $this->unlink($actor)
            : $this->link($request, $actor);
    }

    private function link(ServerRequestInterface $request, $actor): ResponseInterface
    {
        $body = $this->body($request);
        $code = $body['code'] ?? null;

        if (! is_string($code) || trim($code) === '') {
            return $this->error('A binding code is required.', 422);
        }

        $code = strtoupper(trim($code));

        if (! preg_match('/^[A-Z0-9]{8}$/', $code)) {
            return $this->error('That does not look like a binding code.', 422);
        }

        /** @var McBindCode|null $record */
        $record = McBindCode::where('code', $code)->first();

        if (! $record) {
            return $this->error('Unknown or already used binding code.', 404);
        }

        if ($record->used_at !== null) {
            return $this->error('This binding code has already been used.', 409);
        }

        if ($record->expires_at === null || $record->expires_at->isPast()) {
            return $this->error('This binding code has expired. Run /bind again in game.', 410);
        }

        $existingForUser = McBinding::where('user_id', $actor->id)->first();

        if ($existingForUser && $existingForUser->player_uuid !== $record->player_uuid) {
            return $this->error(
                'Your forum account is already linked to ' . $existingForUser->player_name . '. Unlink it first.',
                409
            );
        }

        $existingForPlayer = McBinding::where('player_uuid', $record->player_uuid)->first();

        if ($existingForPlayer && (int) $existingForPlayer->user_id !== (int) $actor->id) {
            return $this->error('That Minecraft account is already linked to another forum account.', 409);
        }

        $binding = $this->db->transaction(function () use ($record, $actor) {
            $record->used_at = Carbon::now();
            $record->user_id = $actor->id;
            $record->save();

            $binding = McBinding::firstOrNew(['player_uuid' => $record->player_uuid]);
            $binding->user_id = $actor->id;
            $binding->player_name = $record->player_name;
            $binding->server_key = $record->server_key;
            $binding->save();

            return $binding;
        });

        return $this->json([
            'ok' => true,
            'binding' => $binding->toApiPayload(),
        ], 201);
    }

    private function unlink($actor): ResponseInterface
    {
        $binding = McBinding::where('user_id', $actor->id)->first();

        if (! $binding) {
            return $this->error('Your account is not linked to a Minecraft account.', 404);
        }

        $binding->delete();

        return $this->json(['ok' => true]);
    }
}
