<?php

namespace Stalir\McBridge\Api\Controller;

use Carbon\Carbon;
use Flarum\Http\RequestUtil;
use Flarum\Settings\SettingsRepositoryInterface;
use Illuminate\Contracts\Cache\Repository as CacheRepository;
use Illuminate\Database\ConnectionInterface;
use Laminas\Diactoros\Response\HtmlResponse;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McBindCode;
use Stalir\McBridge\Model\McBinding;
use Stalir\McBridge\Service\BridgeMessages;

/**
 * GET  /mc-bridge/link   — the page where a player enters the binding code
 * POST /mc-bridge/link   — processes "bind" or "unlink"
 *
 * This lives on the forum frontend so the session cookie identifies the user
 * and the framework enforces the CSRF token for the POST; no JavaScript and no
 * npm build is involved.
 *
 * A build-free page was chosen over a JS settings-page section because the
 * npm toolchain is not available in every environment; the sources for a
 * settings-page section still ship in js/src for when a build is possible.
 */
class LinkPageController extends AbstractBridgeController
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
        try {
            $actor = RequestUtil::getActor($request);
            $actor->assertRegistered();
        } catch (\Throwable) {
            return $this->render($request, null, [], $this->messages->get('page.not_logged_in'));
        }

        if (strtoupper($request->getMethod()) === 'POST') {
            return $this->submit($request, $actor);
        }

        return $this->render($request, $actor);
    }

    // ------------------------------------------------------------------
    // Processing
    // ------------------------------------------------------------------

    private function submit(ServerRequestInterface $request, $actor): ResponseInterface
    {
        $form = $this->body($request);
        $state = [];

        if (($form['action'] ?? '') === 'unlink') {
            $binding = McBinding::where('user_id', $actor->id)->first();

            if ($binding) {
                $binding->delete();
            }

            $state['success'] = $this->messages->get('page.success_unbound');

            return $this->render($request, $actor, $state);
        }

        $code = strtoupper(trim((string) ($form['code'] ?? '')));

        if ($code === '') {
            $state['error'] = $this->messages->get('api.error.link_code_required');

            return $this->render($request, $actor, $state);
        }

        if (! preg_match('/^[A-Z0-9]{8}$/', $code)) {
            $state['error'] = $this->messages->get('api.error.link_code_malformed');

            return $this->render($request, $actor, $state);
        }

        /** @var McBindCode|null $record */
        $record = McBindCode::where('code', $code)->first();

        if (! $record || $record->used_at !== null) {
            $state['error'] = $this->messages->get('api.error.link_code_unknown');

            return $this->render($request, $actor, $state);
        }

        if ($record->expires_at === null || $record->expires_at->isPast()) {
            $state['error'] = $this->messages->get('api.error.link_code_expired');

            return $this->render($request, $actor, $state);
        }

        $existingForUser = McBinding::where('user_id', $actor->id)->first();

        if ($existingForUser && $existingForUser->player_uuid !== $record->player_uuid) {
            $state['error'] = $this->messages->get('api.error.link_user_already_bound', [
                'name' => (string) $existingForUser->player_name,
            ]);

            return $this->render($request, $actor, $state);
        }

        $existingForPlayer = McBinding::where('player_uuid', $record->player_uuid)->first();

        if ($existingForPlayer && (int) $existingForPlayer->user_id !== (int) $actor->id) {
            $state['error'] = $this->messages->get('api.error.link_player_already_bound');

            return $this->render($request, $actor, $state);
        }

        $this->db->transaction(function () use ($record, $actor) {
            $record->used_at = Carbon::now();
            $record->user_id = $actor->id;
            $record->save();

            $binding = McBinding::firstOrNew(['player_uuid' => $record->player_uuid]);
            $binding->user_id = $actor->id;
            $binding->player_name = $record->player_name;
            $binding->server_key = $record->server_key;
            $binding->save();
        });

        $state['success'] = $this->messages->get('page.success_bound');

        return $this->render($request, $actor, $state);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private function render(ServerRequestInterface $request, ?object $actor, array $state = [], ?string $extraError = null): HtmlResponse
    {
        $binding = $actor ? McBinding::with('user')->where('user_id', $actor->id)->first() : null;

        $session = $request->getAttribute('session');
        $csrf = $session ? (string) $session->token() : '';

        $forumUrl = (string) $this->settings->get('url', '/');

        $html = $this->html($binding, $state, $extraError, $csrf, $forumUrl);

        return new HtmlResponse($html);
    }

    private function html(
        ?McBinding $binding,
        array $state,
        ?string $extraError,
        string $csrf,
        string $forumUrl
    ): string {
        $e = static fn ($value): string => htmlspecialchars((string) $value, ENT_QUOTES, 'UTF-8');

        $alerts = '';

        if ($extraError !== null && $extraError !== '') {
            $alerts .= '<div class="alert alert-error">'.$e($extraError).'</div>';
        }

        foreach (['error', 'success'] as $kind) {
            if (! empty($state[$kind])) {
                $class = $kind === 'error' ? 'alert-error' : 'alert-success';
                $alerts .= '<div class="alert '.$class.'">'.$e($state[$kind]).'</div>';
            }
        }

        $body = '';

        if ($binding) {
            $body .= '<p class="bound">'.sprintf(
                $e($this->messages->get('page.bound_to')),
                '<strong>'.$e($binding->player_name).'</strong>'
            ).'</p>';
            $body .= '<form method="post" action="">'
                .'<input type="hidden" name="csrfToken" value="'.$e($csrf).'">'
                .'<input type="hidden" name="action" value="unlink">'
                .'<button type="submit" class="btn btn-danger">'.$e($this->messages->get('page.unlink_submit')).'</button>'
                .'</form>';
        } else {
            $body .= '<p class="intro">'.$e($this->messages->get('page.intro')).'</p>';
            $body .= '<form method="post" action="">'
                .'<input type="hidden" name="csrfToken" value="'.$e($csrf).'">'
                .'<label for="code">'.$e($this->messages->get('page.code_label')).'</label>'
                .'<input id="code" class="input" type="text" name="code" maxlength="8" '
                .'placeholder="'.$e($this->messages->get('page.code_placeholder')).'" autocomplete="off" required>'
                .'<button type="submit" class="btn btn-primary">'.$e($this->messages->get('page.submit')).'</button>'
                .'</form>';
        }

        $title = $e($this->messages->get('page.title'));
        $intro = $e($this->messages->get('page.intro'));
        $notBound = $e($this->messages->get('page.not_bound'));
        $back = $e($this->messages->get('page.back'));

        return <<<HTML
<!doctype html>
<html lang="zh-Hans">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$title</title>
<style>
:root { color-scheme: light; }
* { box-sizing: border-box; }
body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
       background: #f2f4f7; font-family: system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
       color: #1f2933; padding: 24px; }
.card { background: #fff; border-radius: 10px; box-shadow: 0 6px 24px rgba(16,24,40,.08);
        padding: 32px; width: 100%; max-width: 460px; }
h1 { font-size: 20px; margin: 0 0 6px; }
.intro { color: #52606d; font-size: 14px; line-height: 1.6; margin: 0 0 18px; }
.bound { font-size: 15px; margin: 0 0 18px; }
label { display: block; font-size: 13px; font-weight: 600; margin-bottom: 6px; }
.input { width: 100%; padding: 10px 12px; border: 1px solid #cbd2d9; border-radius: 8px;
         font-size: 16px; letter-spacing: 3px; text-transform: uppercase; margin-bottom: 14px; }
.input:focus { outline: 2px solid #2f6f4e; border-color: #2f6f4e; }
.btn { display: inline-block; padding: 10px 16px; border: 0; border-radius: 8px;
       font-size: 14px; font-weight: 600; cursor: pointer; }
.btn-primary { background: #2f6f4e; color: #fff; width: 100%; }
.btn-danger { background: #fdecea; color: #b3261e; }
.alert { padding: 10px 12px; border-radius: 8px; font-size: 14px; margin-bottom: 14px; }
.alert-error { background: #fdecea; color: #b3261e; }
.alert-success { background: #e6f4ea; color: #1e7e34; }
.back { margin: 18px 0 0; font-size: 13px; }
.back a { color: #2f6f4e; text-decoration: none; }
</style>
</head>
<body>
<div class="card">
  <h1>$title</h1>
  <p class="intro">$intro</p>
  $alerts
  $body
  <p class="back"><a href="$e($forumUrl)">&larr; $back</a></p>
</div>
</body>
</html>
HTML;
    }
}
