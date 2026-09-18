import app from 'flarum/forum/app';
import Component from 'flarum/common/Component';
import LoadingIndicator from 'flarum/common/components/LoadingIndicator';
import grassBlock from '../grassBlock';

const EXTENSION_ID = 'stalir-mc-bridge';

/** How often the widget re-reads the public status endpoint. */
const REFRESH_MS = 60_000;

/**
 * A compact server status card, meant for the index sidebar.
 *
 * It reads the extension's public status endpoint, so it works for guests as
 * well, and it is deliberately self-contained: the styling uses Flarum's CSS
 * variables and no class of its own needs a LESS build. That also means it looks
 * native in whatever theme renders the sidebar - Flarum's own index sidebar or a
 * theme like avocado that renders the same component.
 */
export default class McBridgeStatus extends Component {
  oninit(vnode) {
    super.oninit(vnode);

    this.loading = true;
    this.failed = false;
    this.totals = null;
    this.servers = [];
    this.timer = null;

    this.load();
  }

  oncreate(vnode) {
    super.oncreate(vnode);

    this.timer = setInterval(() => this.load(), REFRESH_MS);
  }

  onremove(vnode) {
    super.onremove(vnode);

    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  t(key, params) {
    return app.translator.trans(`${EXTENSION_ID}.forum.status.${key}`, params);
  }

  statusUrl() {
    const baseUrl = String(app.forum.attribute('baseUrl') || '').replace(/\/$/, '');

    return `${baseUrl}/mc-bridge/status`;
  }

  load() {
    return fetch(`${app.forum.attribute('apiUrl')}/mc-bridge/status`, {
      headers: { Accept: 'application/json' },
    })
      .then((response) =>
        response
          .json()
          .catch(() => ({}))
          .then((body) => ({ ok: response.ok, body }))
      )
      .then(({ ok, body }) => {
        this.loading = false;

        if (ok && body.ok === true) {
          this.failed = false;
          this.totals = body.totals || null;
          this.servers = Array.isArray(body.servers) ? body.servers : [];
        } else {
          this.failed = true;
        }

        m.redraw();
      })
      .catch(() => {
        // A forum that cannot reach itself would be a surprise, but a broken
        // sidebar is worse than a line of text.
        this.loading = false;
        this.failed = true;
        m.redraw();
      });
  }

  view() {
    const body = [];

    if (this.loading && !this.totals) {
      body.push(m('div', m(LoadingIndicator)));
    } else if (this.failed) {
      body.push(m('div', { style: 'opacity:.7' }, this.t('error')));
    } else {
      const totals = this.totals || {};
      const online = Number(totals.servers_online || 0);
      const total = Number(totals.servers || 0);
      const players = Number(totals.players_online || 0);

      body.push(
        m('div', { style: 'font-weight:600' }, this.t('summary', { online, total, players }))
      );

      if (this.servers.length === 0) {
        body.push(m('div', { style: 'opacity:.7' }, this.t('empty')));
      } else {
        this.servers.slice(0, 5).forEach((server) => {
          const dot = server.online
            ? m('span', { style: 'color:#1e7e34' }, '\u25cf')
            : m('span', { style: 'color:#b3261e' }, '\u25cf');

          const details = [`${server.players_online}/${server.players_max}`];

          if (server.tps !== null && server.tps !== undefined) {
            details.push(`TPS ${Number(server.tps).toFixed(1)}`);
          }

          body.push(
            m('div', { style: 'display:flex;gap:6px;align-items:baseline;margin-top:4px' }, [
              dot,
              m('span', { style: 'font-weight:600' }, server.server_key),
              m('span', { style: 'opacity:.75' }, details.join(' \u00b7 ')),
            ])
          );
        });
      }

      body.push(
        m(
          'a',
          { href: this.statusUrl(), style: 'display:inline-block;margin-top:8px;font-weight:600' },
          this.t('detail')
        )
      );
    }

    return m(
      'div.McBridge-statusWidget',
      {
        style:
          'margin-top:12px;padding:12px 14px;border-radius:var(--border-radius,8px);' +
          'background:var(--control-bg,#f4f4f4);color:var(--text-color,#333);font-size:13px;line-height:1.5',
      },
      [m('div', { style: 'display:flex;align-items:center;gap:6px;margin-bottom:6px;font-weight:600' }, [grassBlock(16), m('span', this.t('heading'))]), ...body]
    );
  }
}
