import app from 'flarum/forum/app';
import extractText from 'flarum/common/utils/extractText';
import Alert from 'flarum/common/components/Alert';
import Button from 'flarum/common/components/Button';
import Component from 'flarum/common/Component';
import FieldSet from 'flarum/common/components/FieldSet';
import LoadingIndicator from 'flarum/common/components/LoadingIndicator';

// m is the global Mithril hyperscript function that Flarum's own bundle
// exposes (via expose-loader), so JSX/hyperscript calls need no import.

const EXTENSION_ID = 'stalirmc-mc-bridge';

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
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
      .then((response) =>
        response
          .json()
          .catch(() => ({}))
          .then((body) => ({ ok: response.ok, status: response.status, body }))
      )
      .then(({ ok, status, body }) => {
        this.loading = false;

        // Anything the bridge did not produce (a route that is not registered, a
        // Flarum error document, a 500) has to be reported. Reading the body and
        // treating a missing "bound" as false would render the not-linked state
        // instead, which is exactly how an unregistered GET route stayed
        // invisible while the account really was linked.
        if (!ok || body.ok !== true) {
          this.error = this.describeFailure(status, body);
          console.warn('[mc-bridge] GET /mc-bridge/link failed', status, body);
        } else {
          this.error = null;
          this.bound = body.bound === true;
          this.binding = body.binding || null;
        }

        m.redraw();
      })
      .catch((reason) => {
        this.loading = false;
        this.error = this.t('load_error');
        console.warn('[mc-bridge] GET /mc-bridge/link could not be sent', reason);
        m.redraw();
      });
  }

  /**
   * Turn a failed response into something actionable.
   *
   * The bridge's own errors carry a localised "error" string. A Flarum error
   * document uses JSON:API instead ("errors": [{...}]) and has no such field -
   * a 401 from a session that is no longer signed in looks exactly like that.
   * Falling back to the bare "could not read the binding state" message made
   * those two cases indistinguishable, so the HTTP status is always appended.
   */
  describeFailure(status, body) {
    if (body && typeof body.error === 'string' && body.error !== '') {
      return body.error;
    }

    if (body && Array.isArray(body.errors) && body.errors[0]) {
      const detail = body.errors[0].detail || body.errors[0].code;

      if (detail) {
        return `${detail} (HTTP ${status})`;
      }
    }

    return `${this.t('load_error')} (HTTP ${status})`;
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
      credentials: 'same-origin',
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
          .then((body) => ({ ok: response.ok, status: response.status, body }))
      )
      .then(({ ok, status, body }) => {
        if (ok) return body;

        // Same reasoning as describeFailure(): a 500 from an uncaught server
        // error arrives as a Flarum error document, not as our own shape.
        throw this.describeFailure(status, body);
      });
  }

  /**
   * One line of hint text under the section title, describing the current state.
   *
   * FieldSet renders this through its `description` prop, which is how core's own
   * settings sections present supporting text (FieldSet takes `label`/`description`
   * attributes in Flarum 2.x, not a `legend` child).
   */
  description() {
    if (this.loading) return undefined;

    if (this.bound) {
      return this.t('bound_to', { name: (this.binding && this.binding.player_name) || '?' });
    }

    return this.t('enter_code');
  }

  /**
   * The interactive part of the section: status messages and the current action.
   */
  controls() {
    const children = [];

    if (this.error) {
      // dismissible is false because this is inline feedback, not a transient alert.
      children.push(m(Alert, { type: 'error', content: this.error, dismissible: false }));
    }

    if (this.success) {
      children.push(m(Alert, { type: 'success', content: this.success, dismissible: false }));
    }

    if (this.bound) {
      children.push(
        m(
          '.Form-group',
          m(
            Button,
            {
              className: 'Button Button--danger',
              loading: this.busy,
              onclick: () => this.unlink(),
            },
            this.t('unlink_button')
          )
        )
      );
    } else {
      children.push(
        m('.Form-group', [
          m('input.FormControl', {
            type: 'text',
            value: this.code,
            maxlength: 8,
            placeholder: extractText(this.t('code_placeholder')),
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

    return children;
  }

  view() {
    return m(
      FieldSet,
      {
        className: 'Settings-mcBridge FieldSet--min',
        label: extractText(this.t('title')),
        description: this.description(),
      },
      this.loading ? m(LoadingIndicator) : this.controls()
    );
  }
}
