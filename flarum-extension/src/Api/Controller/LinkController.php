<?php

namespace Stalir\McBridge\Api\Controller;

use Carbon\Carbon;
use Flarum\Http\RequestUtil;
use Flarum\Settings\SettingsRepositoryInterface;
use Flarum\User\Exception\NotAuthenticatedException;
use Illuminate\Cache\Repository as CacheRepository;
use Illuminate\Database\ConnectionInterface;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McBindCode;
use Stalir\McBridge\Model\McBinding;
use Stalir\McBridge\Model\McOutboxMessage;
use Stalir\McBridge\Service\BridgeMessages;

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
        SettingsRepositoryInterface $settings,
        CacheRepository $cache,
        BridgeMessages $messages,
        protected ConnectionInterface $db
    ) {
        parent::__construct($settings, $cache, $messages);
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
            return $this->fail('link_login_required', 401);
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
            return $this->fail('link_code_required', 422);
        }

        $code = strtoupper(trim($code));

        if (! preg_match('/^[A-Z0-9]{8}$/', $code)) {
            return $this->fail('link_code_malformed', 422);
        }

        /** @var McBindCode|null $record */
        $record = McBindCode::where('code', $code)->first();

        if (! $record) {
            return $this->fail('link_code_unknown', 404);
        }

        if ($record->used_at !== null) {
            return $this->fail('link_code_used', 409);
        }

        if ($record->expires_at === null || $record->expires_at->isPast()) {
            return $this->fail('link_code_expired', 410);
        }

        $existingForUser = McBinding::where('user_id', $actor->id)->first();

        if ($existingForUser && $existingForUser->player_uuid !== $record->player_uuid) {
            return $this->fail('link_user_already_bound', 409, [
                'player' => (string) $existingForUser->player_name,
            ]);
        }

        $existingForPlayer = McBinding::where('player_uuid', $record->player_uuid)->first();

        if ($existingForPlayer && (int) $existingForPlayer->user_id !== (int) $actor->id) {
            return $this->fail('link_player_already_bound', 409);
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

            // Queue an instant feedback message for the player.
            $feedback = new McOutboxMessage();
            $feedback->server_key = $record->server_key;
            $feedback->type = 'bind_success';
            $feedback->title = '账号绑定成功';
            $feedback->body = '你的 Minecraft 账号已绑定到论坛账号 ' . $actor->username;
            $feedback->target_uuid = $record->player_uuid;
            $feedback->save();

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
            return $this->fail('link_not_bound', 404);
        }

        $playerUuid = $binding->player_uuid;
        $binding->delete();

        // Queue an instant feedback message for the player.
        $feedback = new McOutboxMessage();
        $feedback->server_key = null; // deliver to all servers
        $feedback->type = 'bind_unlinked';
        $feedback->title = '账号已解除绑定';
        $feedback->body = '你的 Minecraft 账号已与论坛账号解除绑定';
        $feedback->target_uuid = $playerUuid;
        $feedback->save();

        return $this->json(['ok' => true]);
    }
}
