<?php

namespace Stalir\McBridge\Api\Controller;

use Carbon\Carbon;
use Laminas\Diactoros\Response\HtmlResponse;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Stalir\McBridge\Model\McEvent;
use Stalir\McBridge\Model\McServer;

/**
 * GET /mc-bridge/status
 *
 * A public page listing every server the plugins have reported: online players,
 * TPS, version, MOTD and the last heartbeat, plus the most recent gameplay
 * events. This is the "forum shows the live server" half of the bridge - it reads
 * exactly the rows the heartbeat fills in, and it is safe to show to guests
 * because the public status endpoint exposes the same aggregate data.
 *
 * Like the binding page this is a plain server-rendered document: no npm build,
 * no JavaScript, and therefore no dependency on the frontend toolchain. The
 * sidebar entry that links here is added by the forum bundle.
 */
class StatusPageController extends AbstractBridgeController
{
    /** How many gameplay events to show under the server list. */
    private const RECENT_EVENTS = 12;

    public function handle(ServerRequestInterface $request): ResponseInterface
    {
        $servers = McServer::query()->orderByDesc('last_heartbeat_at')->get();
        $events = McEvent::query()
            ->orderByDesc('happened_at')
            ->limit(self::RECENT_EVENTS)
            ->get();

        return new HtmlResponse($this->html($servers, $events));
    }

    /**
     * @param  \Illuminate\Support\Collection<int, McServer> $servers
     * @param  \Illuminate\Support\Collection<int, McEvent>  $events
     */
    private function html($servers, $events): string
    {
        $e = static fn ($value): string => htmlspecialchars((string) $value, ENT_QUOTES, 'UTF-8');
        $t = fn (string $key): string => $this->messages->get('page.status.'.$key);

        $online = $servers->filter(fn (McServer $server) => $server->isFresh());
        $players = (int) $online->sum('players_online');

        $body = '';

        if ($servers->isEmpty()) {
            $body .= '<p class="empty">'.$e($t('empty')).'</p>';
        } else {
            $body .= $this->serverList($servers, $e, $t);
        }

        $body .= $this->eventList($events, $e, $t);

        $title = $e($t('title'));
        $intro = $e($t('intro'));
        $back = $e($t('back'));
        $hint = $e($t('refresh_hint'));
        $forumUrl = $e((string) $this->settings->get('url', '/'));
        $summary = $e(
            $t('online').' '.$online->count().' / '.$servers->count()
            .' · '.$t('players').' '.$players
        );

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
body { margin: 0; background: #f2f4f7; color: #1f2933; padding: 32px 16px;
       font-family: system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
.wrap { max-width: 960px; margin: 0 auto; }
h1 { font-size: 22px; margin: 0 0 6px; }
.intro { color: #52606d; font-size: 14px; line-height: 1.6; margin: 0 0 4px; }
.summary { color: #2f6f4e; font-size: 14px; font-weight: 600; margin: 0 0 20px; }
.card { background: #fff; border-radius: 10px; box-shadow: 0 6px 24px rgba(16,24,40,.08); padding: 20px 22px; margin-bottom: 18px; }
.server-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.server-name { font-size: 17px; font-weight: 600; }
.pill { font-size: 12px; font-weight: 600; padding: 3px 10px; border-radius: 999px; }
.pill-online { background: #e6f4ea; color: #1e7e34; }
.pill-offline { background: #fdecea; color: #b3261e; }
dl { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px 18px; margin: 16px 0 0; }
dt { font-size: 12px; color: #7b8794; margin: 0; }
dd { margin: 2px 0 0; font-size: 14px; }
.names { color: #52606d; font-size: 13px; line-height: 1.6; margin: 14px 0 0; word-break: break-word; }
h2 { font-size: 16px; margin: 0 0 12px; }
ul.events { list-style: none; margin: 0; padding: 0; font-size: 14px; }
ul.events li { padding: 7px 0; border-bottom: 1px solid #eef1f4; }
ul.events li:last-child { border-bottom: 0; }
.event-type { display: inline-block; min-width: 84px; font-size: 12px; font-weight: 600; color: #2f6f4e; }
.event-time { color: #7b8794; font-size: 12px; margin-left: 8px; }
.empty { color: #52606d; font-size: 14px; margin: 0; }
.back { font-size: 13px; margin: 22px 0 0; }
.back a { color: #2f6f4e; text-decoration: none; }
.hint { color: #7b8794; font-size: 12px; margin: 8px 0 0; }
</style>
</head>
<body>
<div class="wrap">
  <h1>$title</h1>
  <p class="intro">$intro</p>
  <p class="summary">$summary</p>
  $body
  <p class="hint">$hint</p>
  <p class="back"><a href="$forumUrl">&larr; $back</a></p>
</div>
</body>
</html>
HTML;
    }

    /**
     * @param  \Illuminate\Support\Collection<int, McServer> $servers
     */
    private function serverList($servers, callable $e, callable $t): string
    {
        $html = '';

        foreach ($servers as $server) {
            $fresh = $server->isFresh();
            $pill = $fresh
                ? '<span class="pill pill-online">'.$e($t('online')).'</span>'
                : '<span class="pill pill-offline">'.$e($t('offline')).'</span>';

            $stats = [
                $t('players') => $server->players_online.' / '.$server->players_max,
                $t('version') => (string) ($server->version ?? ''),
                $t('last_heartbeat') => $this->ago($server->last_heartbeat_at, $e, $t),
            ];

            if ($server->tps !== null) {
                $stats[$t('tps')] = number_format((float) $server->tps, 2);
            }

            if ($server->mspt !== null) {
                $stats[$t('mspt')] = number_format((float) $server->mspt, 1).' ms';
            }

            if (! empty($server->motd)) {
                $stats[$t('motd')] = (string) $server->motd;
            }

            $rows = '';

            foreach ($stats as $label => $value) {
                $rows .= '<div><dt>'.$e($label).'</dt><dd>'.$e($value).'</dd></div>';
            }

            $names = is_array($server->player_names) ? $server->player_names : [];
            $namesText = $names === []
                ? $e($t('names_none'))
                : $e(implode('、', array_slice($names, 0, 200)));

            $html .= '<div class="card">'
                .'<div class="server-head">'
                .'<span class="server-name">'.$e($server->server_key).'</span>'
                .$pill
                .'</div>'
                .'<dl>'.$rows.'</dl>'
                .'<p class="names">'.$e($t('names')).'：'.$namesText.'</p>'
                .'</div>';
        }

        return $html;
    }

    /**
     * @param  \Illuminate\Support\Collection<int, McEvent> $events
     */
    private function eventList($events, callable $e, callable $t): string
    {
        $html = '<div class="card"><h2>'.$e($t('recent_events')).'</h2>';

        if ($events->isEmpty()) {
            return $html.'<p class="empty">'.$e($t('events_none')).'</p></div>';
        }

        $html .= '<ul class="events">';

        foreach ($events as $event) {
            $who = $event->player_name !== null && $event->player_name !== ''
                ? $event->player_name
                : $event->server_key;

            $detail = trim((string) $event->message);
            $line = $e($who);

            if ($detail !== '') {
                $line .= ' &middot; '.$e($detail);
            }

            $html .= '<li>'
                .'<span class="event-type">'.$e($event->type).'</span>'
                .$line
                .'<span class="event-time">'.$this->ago($event->happened_at, $e, $t).'</span>'
                .'</li>';
        }

        return $html.'</ul></div>';
    }

    /** "12 秒前" / "3 分钟前" / "2 小时前". */
    private function ago(?Carbon $moment, callable $e, callable $t): string
    {
        if ($moment === null) {
            return $e($t('never'));
        }

        $seconds = max(0, $moment->diffInSeconds(Carbon::now()));

        if ($seconds < 60) {
            return $e(sprintf($t('seconds_ago'), $seconds));
        }

        if ($seconds < 3600) {
            return $e(sprintf($t('minutes_ago'), intdiv($seconds, 60)));
        }

        return $e(sprintf($t('hours_ago'), intdiv($seconds, 3600)));
    }
}
