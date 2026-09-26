<?php

use Flarum\Api\Resource\UserResource;
use Flarum\Extend;
use Flarum\Post\Event\Posted;
use Flarum\Tags\Event\DiscussionWasTagged;
use Stalir\McBridge\Api\Controller\AnnouncementsController;
use Stalir\McBridge\Api\Controller\BindStartController;
use Stalir\McBridge\Api\Controller\BindStatusController;
use Stalir\McBridge\Api\Controller\BroadcastController;
use Stalir\McBridge\Api\Controller\LinkController;
use Stalir\McBridge\Api\Controller\LinkPageController;
use Stalir\McBridge\Api\Controller\LinkStatusController;
use Stalir\McBridge\Api\Controller\ReportController;
use Stalir\McBridge\Api\Controller\ReportsController;
use Stalir\McBridge\Api\UserResourceFields;
use Stalir\McBridge\Console\ConfigCommand;
use Stalir\McBridge\Console\ReportCommand;
use Stalir\McBridge\Console\SecretCommand;
use Stalir\McBridge\Console\SelfTestCommand;
use Stalir\McBridge\Listener\QueueAnnouncement;
use Stalir\McBridge\Listener\ReportTagListener;
use Stalir\McBridge\Service\BridgeMessages;
use Stalir\McBridge\Service\ReportDiscussion;

return [
    // ---------------------------------------------------------------------
    // Translations. Flarum does not discover an extension's locale files on
    // its own: every *.yml in this directory is registered, the file name
    // being the locale. Without this line none of the translations load.
    // ---------------------------------------------------------------------
    new Extend\Locales(__DIR__.'/locale'),

    // ---------------------------------------------------------------------
    // CSRF exemptions.
    //
    // Flarum runs CheckCsrfToken across the whole `api` stack and exempts
    // routes BY NAME through this extender (Extend\Csrf -> the
    // flarum.http.csrfExemptPaths binding). A session-less Minecraft server
    // cannot produce a CSRF token, so every machine endpoint has to be listed
    // here; otherwise it is rejected with a 400 before the HMAC check runs.
    //
    // Only machine endpoints are listed. mc-bridge.link and mc-bridge.unlink
    // act on the forum session and MUST keep their CSRF protection, so they are
    // deliberately absent. mc-bridge.broadcast is dual-auth: it is listed so
    // signed machine calls work, and BroadcastController re-enforces a CSRF
    // token by hand for the session path.
    // ---------------------------------------------------------------------
    (new Extend\Csrf())
        ->exemptRoute('mc-bridge.outbox')
        ->exemptRoute('mc-bridge.announcements')
        ->exemptRoute('mc-bridge.bind.start')
        ->exemptRoute('mc-bridge.bind.status')
        ->exemptRoute('mc-bridge.broadcast')
        ->exemptRoute('mc-bridge.report')
        ->exemptRoute('mc-bridge.reports'),

    // ---------------------------------------------------------------------
    // The Minecraft binding of a forum account, exposed on the user resource.
    // The profile page and the badge next to every post author read it from the
    // payload they already load, so no extra request is needed per author. Only
    // signed-in users receive the fields (see Api\UserResourceFields).
    // ---------------------------------------------------------------------
    (new Extend\ApiResource(UserResource::class))
        ->fields(UserResourceFields::class),

    // ---------------------------------------------------------------------
    // Machine-to-machine endpoints. Every request here is authenticated with
    // the HMAC scheme implemented in Api\Controller\AbstractBridgeController.
    // ---------------------------------------------------------------------
    (new Extend\Routes('api'))
        ->get('/mc-bridge/outbox', 'mc-bridge.outbox', AnnouncementsController::class)
        // Alias kept for the documented "announcements" endpoint name.
        ->get('/mc-bridge/announcements', 'mc-bridge.announcements', AnnouncementsController::class)
        ->post('/mc-bridge/bind/start', 'mc-bridge.bind.start', BindStartController::class)
        ->get('/mc-bridge/bind/status', 'mc-bridge.bind.status', BindStatusController::class)
        ->post('/mc-bridge/broadcast', 'mc-bridge.broadcast', BroadcastController::class)
        ->post('/mc-bridge/report', 'mc-bridge.report', ReportController::class)
        // What /report status reads: the reports one player filed, and only that
        // player's. Pure read, so a GET.
        ->get('/mc-bridge/reports', 'mc-bridge.reports', ReportsController::class),

    // ---------------------------------------------------------------------
    // Forum-facing endpoints. These use the normal Flarum session/actor and
    // are safe to call from the browser.
    // ---------------------------------------------------------------------
    (new Extend\Routes('api'))
        // GET is what the forum's settings section reads to show whether the
        // account is already linked. Without it the frontend asked for a route
        // that did not exist, received a 404, and rendered "not linked" even
        // after a successful binding.
        ->get('/mc-bridge/link', 'mc-bridge.linkStatus', LinkStatusController::class)
        ->post('/mc-bridge/link', 'mc-bridge.link', LinkController::class)
        ->delete('/mc-bridge/link', 'mc-bridge.unlink', LinkController::class),

    // ---------------------------------------------------------------------
    // Build-free binding page. A player opens this URL (logged in), enters the
    // code printed by /bind, and the link is made. The forum stack enforces the
    // CSRF token for the POST, and the session carries the identity.
    //
    // The routes use the forum frontend, so the session cookie and the actor
    // are available without any API token.
    // ---------------------------------------------------------------------
    (new Extend\Routes('forum'))
        ->get('/mc-bridge/link', 'mc-bridge.linkPage', LinkPageController::class)
        ->post('/mc-bridge/link', 'mc-bridge.linkPage.submit', LinkPageController::class),

    // ---------------------------------------------------------------------
    // Frontend JS. Only registered when the bundle has actually been built
    // (npm install && npm run build inside flarum-extension/js); otherwise
    // Flarum would try to load a file that does not exist on every page view.
    //
    // The binding flow works without it through the page above. The sources
    // live in js/src and build to js/dist.
    // ---------------------------------------------------------------------
    ...(file_exists(__DIR__.'/js/dist/forum.js')
        ? [(new Extend\Frontend('forum'))->js(__DIR__.'/js/dist/forum.js')]
        : []),
    ...(file_exists(__DIR__.'/js/dist/admin.js')
        ? [(new Extend\Frontend('admin'))->js(__DIR__.'/js/dist/admin.js')]
        : []),

    // ---------------------------------------------------------------------
    // Queue forum activity for delivery to the game servers, and turn a
    // moderator's tag edit on a report discussion into an outcome.
    // ---------------------------------------------------------------------
    (new Extend\Event())
        ->listen(Posted::class, QueueAnnouncement::class)
        ->listen(DiscussionWasTagged::class, ReportTagListener::class),

    // ---------------------------------------------------------------------
    // Console helpers:
    //   php flarum mc-bridge:secret
    //   php flarum mc-bridge:config --tags=1,3
    //   php flarum mc-bridge:report --list
    //   php flarum mc-bridge:report 12 --status=resolved
    //   php flarum mc-bridge:selftest --url=https://your.forum
    // ---------------------------------------------------------------------
    (new Extend\Console())
        ->command(SecretCommand::class)
        ->command(ConfigCommand::class)
        ->command(ReportCommand::class)
        ->command(SelfTestCommand::class),

    // ---------------------------------------------------------------------
    // Defaults.
    // ---------------------------------------------------------------------
    (new Extend\Settings())
        ->default('mc-bridge.secret', '')
        ->default('mc-bridge.locale', BridgeMessages::DEFAULT_LOCALE)
        ->default('mc-bridge.announcement_tag_ids', '')
        ->default('mc-bridge.sync_replies', '0')
        ->default('mc-bridge.max_announcement_age_days', '30')
        // Where a player report is filed, who it is filed as, and the title used
        // when the game server does not send one of its own. All three are
        // resolved automatically on first use (see Service\ReportDiscussion) and
        // written back, so they are declared here to keep every mc-bridge.* key
        // discoverable in one place.
        ->default('mc-bridge.report_tag_ids', '')
        ->default('mc-bridge.report_actor_id', '')
        ->default('mc-bridge.report_title_format', ReportDiscussion::DEFAULT_TITLE_FORMAT)
        // Which tags mean "this report was dealt with" / "dismissed". Empty by
        // default: the automatic outcome needs the moderator's own tag names,
        // which no extension can guess. Listener\ReportTagListener reads them.
        ->default('mc-bridge.report_resolved_tag_ids', '')
        ->default('mc-bridge.report_rejected_tag_ids', ''),
];
