<?php

namespace Stalir\McBridge\Api\Controller;

use Carbon\Carbon;
use Flarum\Http\RequestUtil;
use Flarum\Settings\SettingsRepositoryInterface;
use Illuminate\Cache\Repository as CacheRepository;
use Illuminate\Database\ConnectionInterface;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McActivity;
use Stalir\McBridge\Service\BridgeCrypto;
use Stalir\McBridge\Service\BridgeMessages;

/**
 * POST /api/mc-bridge/activity   create an activity poll
 * GET  /api/mc-bridge/activity   the open poll, or the results of the one that
 *                                just closed
 *
 * The forum side deliberately runs no scheduler. Closing a poll and handing its
 * results back to the game happens on read: whenever a server asks, any poll
 * whose deadline has passed is closed, and the first server to arrive after that
 * receives the final tally (once - `announced_at` is stamped).
 *
 * POST accepts the same two callers as the broadcast endpoint: an administrator
 * with a session (and therefore a CSRF token), or an HMAC-signed machine call.
 */
class ActivityController extends AbstractBridgeController
{
    private const MIN_OPTIONS = 2;
    private const MAX_OPTIONS = 10;
    private const DEFAULT_MINUTES = 60;
    private const MIN_MINUTES = 5;
    private const MAX_MINUTES = 10080; // seven days
    private const MAX_OPTION_LENGTH = 100;

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
        return strtoupper($request->getMethod()) === 'GET'
            ? $this->current($request)
            : $this->create($request);
    }

    // ------------------------------------------------------------------
    // GET
    // ------------------------------------------------------------------

    private function current(ServerRequestInterface $request): ResponseInterface
    {
        if ($error = $this->assertMachine($request)) {
            return $error;
        }

        $query = $request->getQueryParams();
        $serverKey = $this->resolveServerKey($request, $query);

        if ($serverKey === null) {
            return $this->fail('server_key_required', 422);
        }

        $this->closeExpired($serverKey);

        $open = McActivity::where('closed', false)
            ->where('closes_at', '>', Carbon::now())
            ->where(function ($query) use ($serverKey) {
                $query->whereNull('server_key')->orWhere('server_key', $serverKey);
            })
            ->orderByDesc('id')
            ->first();

        // A poll that has closed but whose result nobody collected yet.
        $pending = McActivity::where('closed', true)
            ->whereNull('announced_at')
            ->where(function ($query) use ($serverKey) {
                $query->whereNull('server_key')->orWhere('server_key', $serverKey);
            })
            ->orderBy('id')
            ->first();

        $results = null;

        if ($pending) {
            $pending->announced_at = Carbon::now();
            $pending->save();

            $results = $this->resultsPayload($pending);
        }

        return $this->json([
            'ok' => true,
            'open' => $open ? $open->toApiPayload() : null,
            'results' => $results,
        ]);
    }

    /** Close every poll for this server whose deadline has passed. */
    private function closeExpired(string $serverKey): void
    {
        McActivity::where('closed', false)
            ->where('closes_at', '<=', Carbon::now())
            ->where(function ($query) use ($serverKey) {
                $query->whereNull('server_key')->orWhere('server_key', $serverKey);
            })
            ->update(['closed' => true]);
    }

    private function resultsPayload(McActivity $activity): array
    {
        $payload = $activity->toApiPayload();
        $tally = $payload['tally'];
        $best = 0;

        foreach ($tally as $index => $count) {
            if ($count > $tally[$best]) {
                $best = $index;
            }
        }

        $payload['winner'] = $tally[$best] > 0 ? $best : null;

        return $payload;
    }

    // ------------------------------------------------------------------
    // POST
    // ------------------------------------------------------------------

    private function create(ServerRequestInterface $request): ResponseInterface
    {
        $body = $this->body($request);

        $claimsMachine = $request->hasHeader(BridgeCrypto::HEADER_SIGNATURE)
            || $request->hasHeader(BridgeCrypto::HEADER_TIMESTAMP)
            || $request->hasHeader(BridgeCrypto::HEADER_NONCE);

        if ($claimsMachine) {
            if ($error = $this->assertMachine($request)) {
                return $error;
            }
        } else {
            $actor = RequestUtil::getActor($request);

            if ($actor->isGuest()) {
                return $this->fail('activity_auth_required', 401);
            }

            if (! $actor->isAdmin()) {
                return $this->fail('activity_admin_required', 403);
            }

            // This route is CSRF-exempt so signed machine calls work, so the
            // session path has to enforce the token here.
            $session = $request->getAttribute('session');
            $provided = $request->getHeaderLine('X-CSRF-Token');

            if (! $session || $provided === '' || ! hash_equals((string) $session->token(), $provided)) {
                return $this->fail('activity_csrf_required', 403);
            }
        }

        $title = isset($body['title']) ? mb_substr(trim((string) $body['title']), 0, 255) : '';

        if ($title === '') {
            return $this->fail('activity_title_required', 422);
        }

        $rawOptions = $body['options'] ?? null;

        if (! is_array($rawOptions)) {
            return $this->fail('activity_options_required', 422);
        }

        $options = [];

        foreach ($rawOptions as $option) {
            if (! is_string($option)) {
                return $this->fail('activity_options_invalid', 422);
            }

            $option = mb_substr(trim($option), 0, self::MAX_OPTION_LENGTH);

            if ($option === '') {
                return $this->fail('activity_options_invalid', 422);
            }

            $options[] = $option;
        }

        if (count($options) < self::MIN_OPTIONS || count($options) > self::MAX_OPTIONS) {
            return $this->fail('activity_options_count', 422, [
                'min' => (string) self::MIN_OPTIONS,
                'max' => (string) self::MAX_OPTIONS,
            ]);
        }

        $minutes = isset($body['closes_in_minutes'])
            ? (int) $body['closes_in_minutes']
            : self::DEFAULT_MINUTES;

        $minutes = max(self::MIN_MINUTES, min(self::MAX_MINUTES, $minutes));

        $serverKey = null;

        if (($body['server_key'] ?? null) !== null) {
            $serverKey = $this->resolveServerKey($request, $body);

            if ($serverKey === null) {
                return $this->fail('server_key_invalid', 422);
            }
        }

        $activity = new McActivity();
        $activity->server_key = $serverKey;
        $activity->title = $title;
        $activity->options = $options;
        $activity->closes_at = Carbon::now()->addMinutes($minutes);
        $activity->closed = false;
        $activity->save();

        return $this->json([
            'ok' => true,
            'activity' => $activity->toApiPayload(),
        ], 201);
    }
}
