<?php

namespace Stalir\McBridge\Api\Controller;

use Carbon\Carbon;
use Flarum\Settings\SettingsRepositoryInterface;
use Illuminate\Cache\Repository as CacheRepository;
use Illuminate\Database\ConnectionInterface;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McOutboxMessage;
use Stalir\McBridge\Service\BridgeMessages;

/**
 * GET /api/mc-bridge/outbox?server_key=survival&limit=20
 *
 * Returns messages queued for this server and marks them as delivered so the
 * next poll does not repeat them. Pass ?peek=1 to inspect without consuming.
 */
class AnnouncementsController extends AbstractBridgeController
{
    private const DEFAULT_LIMIT = 20;
    private const MAX_LIMIT = 100;

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
        if ($error = $this->assertMachine($request)) {
            return $error;
        }

        $query = $request->getQueryParams();
        $serverKey = $this->resolveServerKey($request, $query);

        if ($serverKey === null) {
            return $this->fail('server_key_required', 422);
        }

        $limit = (int) ($query['limit'] ?? self::DEFAULT_LIMIT);
        $limit = max(1, min(self::MAX_LIMIT, $limit));

        $peek = filter_var($query['peek'] ?? false, FILTER_VALIDATE_BOOLEAN);

        $messages = $this->claim($serverKey, $limit, $peek);

        return $this->json([
            'ok' => true,
            'server_key' => $serverKey,
            'peek' => $peek,
            'messages' => $messages->map(fn (McOutboxMessage $message) => $message->toApiPayload())->all(),
        ]);
    }

    /**
     * Select the pending messages and, unless peeking, mark them delivered.
     *
     * The SELECT ... FOR UPDATE and the UPDATE share one transaction so that two
     * server instances (or a retried request) polling concurrently cannot both
     * claim the same message. The second `whereNull('delivered_at')` guard keeps
     * the update idempotent even if the row lock is not honoured by the storage
     * engine.
     */
    private function claim(string $serverKey, int $limit, bool $peek)
    {
        $pending = fn () => McOutboxMessage::query()
            ->whereNull('delivered_at')
            ->where(function ($builder) use ($serverKey) {
                $builder->whereNull('server_key')->orWhere('server_key', $serverKey);
            })
            ->orderBy('id')
            ->limit($limit);

        if ($peek) {
            return $pending()->get();
        }

        return $this->db->transaction(function () use ($pending) {
            $messages = $pending()->lockForUpdate()->get();

            if ($messages->isNotEmpty()) {
                McOutboxMessage::query()
                    ->whereIn('id', $messages->pluck('id')->all())
                    ->whereNull('delivered_at')
                    ->update(['delivered_at' => Carbon::now()]);
            }

            return $messages;
        });
    }
}
