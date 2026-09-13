import app from 'flarum/forum/app';
import Component from 'flarum/common/Component';
import FieldSet from 'flarum/common/components/FieldSet';
import Button from 'flarum/common/components/Button';
import LoadingIndicator from 'flarum/common/components/LoadingIndicator';
import m from 'mithril';

const EXTENSION_ID = 'stalir-mc-bridge';

/**
 * The "Minecraft account" section on the user's settings page.
 *
 * Shows the current link state and accepts the one-time binding code printed by
 * /bind in game. Talks to the session-authenticated /mc-bridge/link endpoints.
 */
export default class McBridgeSection extends Component {
  oninit(vnode) {
    super.oninit(vnode);

    this.loading = true;
    this.busy = false;
    this.bound = false;
    this.binding = null;
    this.code = '';
    this.error = null;
    this.success = null;

    this.refresh();
  }

  apiUrl() {
    return app.forum.attribute('apiUrl');
  }

  t(key, params) {
    return app.translator.trans(`${EXTENSION_ID}.forum.settings.${key}`, params);
  }

  refresh() {
    this.loading = true;

    return fetch(`${this.apiUrl()}/mc-bridge/link`, {
      headers: { Accept: 'application/json' },
    })
      .then((response) => response.json())
      .then((body) => {
        this.loading = false;
        this.bound = body.bound === true;
        this.binding = body.binding || null;
        m.redraw();
      })
      .catch(() => {
        this.loading = false;
        this.error = this.t('load_error');
        m.redraw();
      });
  }

  bind() {
    if (this.busy) return;

    this.busy = true;
    this.error = null;
    this.success = null;

    this.request('POST', '/mc-bridge/link', { code: this.code })
      .then((body) => {
        this.busy = false;
        this.code = '';
        this.success = this.t('bind_ok');
        this.error = null;
        this.refresh();
        m.redraw();
      })
      .catch((message) => {
        this.busy = false;
        this.error = message;
        m.redraw();
      });
  }

  unlink() {
    if (this.busy) return;

    this.busy = true;
    this.error = null;
    this.success = null;

    this.request('DELETE', '/mc-bridge/link')
      .then(() => {
        this.busy = false;
        this.success = this.t('unlink_ok');
        this.bound = false;
        this.binding = null;
        m.redraw();
      })
      .catch((message) => {
        this.busy = false;
        this.error = message;
        m.redraw();
      });
  }

  /**
   * POST/DELETE to the bridge API as the logged-in user.
   *
   * Resolves with the parsed body and rejects with the forum's error string, so
   * the localised messages from the API surface directly in the UI.
   */
  request(method, path, payload) {
    return fetch(`${this.apiUrl()}${path}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        'X-CSRF-Token': app.session.csrfToken,
      },
      body: payload ? JSON.stringify(payload) : undefined,
    })
      .then((response) =>
        response
          .json()
          .catch(() => ({}))
          .then((body) => ({ ok: response.ok, body }))
      )
      .then(({ ok, body }) => {
        if (ok) return body;

        throw body.error || this.t('load_error');
      });
  }

  view() {
    const children = [];

    if (this.loading) {
      return m(
        FieldSet,
        { className: 'McBridgeSection' },
        m('legend', this.t('title')),
        m(LoadingIndicator)
      );
    }

    if (this.error) {
      children.push(m('.Alert.Alert--error', m('li', this.error)));
    }

    if (this.success) {
      children.push(m('.Alert.Alert--success', m('li', this.success)));
    }

    if (this.bound) {
      children.push(
        m(
          'p.helpText',
          this.t('bound_to', { name: (this.binding && this.binding.player_name) || '?' })
        )
      );
      children.push(
        m(
          '.Form-group',
          m(
            Button,
            { className: 'Button Button--danger', onclick: () => this.unlink() },
            this.t('unlink_button')
          )
        )
      );
    } else {
      children.push(m('p.helpText', this.t('enter_code')));
      children.push(
        m('.Form-group', [
          m('input.FormControl', {
            type: 'text',
            value: this.code,
            maxlength: 8,
            placeholder: this.t('code_placeholder'),
            oninput: (event) => {
              this.code = event.target.value;
            },
          }),
          m(
            Button,
            { className: 'Button Button--primary', loading: this.busy, onclick: () => this.bind() },
            this.t('bind_button')
          ),
        ])
      );
    }

    return m(FieldSet, { className: 'McBridgeSection' }, [m('legend', this.t('title')), children]);
  }
}
