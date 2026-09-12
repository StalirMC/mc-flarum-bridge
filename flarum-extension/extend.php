<?php

use Flarum\Extend;
use Flarum\Http\Middleware\CheckCsrfToken;
use Flarum\Post\Event\Posted;
use Stalir\McBridge\Api\Controller\AnnouncementsController;
use Stalir\McBridge\Api\Controller\BindStartController;
use Stalir\McBridge\Api\Controller\BindStatusController;
use Stalir\McBridge\Api\Controller\BroadcastController;
use Stalir\McBridge\Api\Controller\EventController;
use Stalir\McBridge\Api\Controller\HeartbeatController;
use Stalir\McBridge\Api\Controller\LinkController;
use Stalir\McBridge\Api\Controller\StatusController;
use Stalir\McBridge\Console\ConfigCommand;
use Stalir\McBridge\Console\SecretCommand;
use Stalir\McBridge\Console\SelfTestCommand;
use Stalir\McBridge\Http\Middleware\BridgeCsrfBypassMiddleware;
use Stalir\McBridge\Listener\QueueAnnouncement;

return [
    // ---------------------------------------------------------------------
    // Flarum applies CSRF verification to the whole `api` stack, which a
    // session-less Minecraft server cannot satisfy. This middleware marks
    // signature-bearing bridge requests as exempt *before* the CSRF check runs.
    // It must be inserted (not appended) to take effect.
    // ---------------------------------------------------------------------
    (new Extend\Middleware('api'))
        ->insertBefore(CheckCsrfToken::class, BridgeCsrfBypassMiddleware::class),

    // ---------------------------------------------------------------------
    // Machine-to-machine endpoints. Every request here is authenticated with
    // the HMAC scheme implemented in Api\Controller\AbstractBridgeController.
    // ---------------------------------------------------------------------
    (new Extend\Routes('api'))
        ->post('/mc-bridge/heartbeat', 'mc-bridge.heartbeat', HeartbeatController::class)
        ->post('/mc-bridge/events', 'mc-bridge.events', EventController::class)
        ->get('/mc-bridge/outbox', 'mc-bridge.outbox', AnnouncementsController::class)
        // Alias kept for the documented "announcements" endpoint name.
        ->get('/mc-bridge/announcements', 'mc-bridge.announcements', AnnouncementsController::class)
        ->post('/mc-bridge/bind/start', 'mc-bridge.bind.start', BindStartController::class)
        ->get('/mc-bridge/bind/status', 'mc-bridge.bind.status', BindStatusController::class)
        ->post('/mc-bridge/broadcast', 'mc-bridge.broadcast', BroadcastController::class),

    // ---------------------------------------------------------------------
    // Forum-facing endpoints. These use the normal Flarum session/actor and
    // are safe to call from the browser.
    // ---------------------------------------------------------------------
    (new Extend\Routes('api'))
        ->get('/mc-bridge/status', 'mc-bridge.status', StatusController::class)
        ->post('/mc-bridge/link', 'mc-bridge.link', LinkController::class)
        ->delete('/mc-bridge/link', 'mc-bridge.unlink', LinkController::class),

    // ---------------------------------------------------------------------
    // Queue forum activity for delivery to the game servers.
    // ---------------------------------------------------------------------
    (new Extend\Event())
        ->listen(Posted::class, QueueAnnouncement::class),

    // ---------------------------------------------------------------------
    // Console helpers:
    //   php flarum mc-bridge:secret
    //   php flarum mc-bridge:config --tags=1,3
    //   php flarum mc-bridge:selftest --url=https://your.forum
    // ---------------------------------------------------------------------
    (new Extend\Console())
        ->command(SecretCommand::class)
        ->command(ConfigCommand::class)
        ->command(SelfTestCommand::class),

    // ---------------------------------------------------------------------
    // Defaults.
    // ---------------------------------------------------------------------
    (new Extend\Settings())
        ->default('mc-bridge.secret', '')
        ->default('mc-bridge.announcement_tag_ids', '')
        ->default('mc-bridge.sync_replies', '0')
        ->default('mc-bridge.max_announcement_age_days', '30'),
];
