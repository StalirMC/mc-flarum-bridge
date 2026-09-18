import app from 'flarum/forum/app';
import { extend } from 'flarum/common/extend';
import McBridgeSection from './src/forum/components/McBridgeSection';

const EXTENSION_ID = 'stalir-mc-bridge';

app.initializers.add(EXTENSION_ID, () => {
  // Adds a "Minecraft account" section to the user's settings page, where the
  // binding code shown by /bind in game can be entered.
  //
  // The target is referenced by module *path string*, not by a static import:
  // core registers the settings page as a lazily loaded chunk
  // (`settings: { path: '/settings', component: () => import('./components/SettingsPage') }`
  // in core's routes.ts), so `import SettingsPage from
  // 'flarum/forum/components/SettingsPage'` would evaluate to `undefined` when
  // this bundle is evaluated, and touching `.prototype` would then throw
  // "Cannot read properties of undefined (reading 'prototype')".
  //
  // Passing the string makes extend() resolve the module through
  // flarum.reg.onLoad(), which applies the callback immediately when the module
  // is already registered and otherwise waits for the chunk to load. extend()
  // adds `.prototype` itself for the string form.
  extend('flarum/forum/components/SettingsPage', 'settingsItems', function (items) {
    // m is the global Mithril hyperscript function that Flarum's own bundle
    // exposes (via expose-loader); JSX compiles to it as well.
    items.add('mc-bridge', m(McBridgeSection), 12);
  });

  // The linked Minecraft account on the profile page.
  //
  // mcBridgePlayerName arrives with the user resource (see
  // Api\UserResourceFields) and is only present for signed-in viewers, so a
  // guest sees nothing here at all.
  extend('flarum/forum/components/UserPage', 'sidebarItems', function (items) {
    const user = this.user;
    const playerName = user && user.attribute('mcBridgePlayerName');

    if (!playerName) {
      return;
    }

    const serverKey = user.attribute('mcBridgeServerKey');

    items.add(
      'mcBridgeAccount',
      m('div.McBridge-profileAccount', [
        m('label', app.translator.trans('stalir-mc-bridge.forum.profile.heading')),
        m('p', app.translator.trans('stalir-mc-bridge.forum.profile.bound_to', { name: playerName })),
        serverKey
          ? m(
              'p.helpText',
              app.translator.trans('stalir-mc-bridge.forum.profile.server', { key: serverKey })
            )
          : null,
      ]),
      40
    );
  });

  // A badge next to the author of every post and reply.
  //
  // userViewItems() is the item list PostUser builds for a post that has an
  // author; 95 puts the badge right after the name (100) and before the group
  // badges (90).
  extend('flarum/forum/components/PostUser', 'userViewItems', function (items, user) {
    const playerName = user && user.attribute('mcBridgePlayerName');

    if (!playerName) {
      return;
    }

    items.add(
      'mcBridgeName',
      m(
        'span.Badge.McBridge-badge',
        { title: app.translator.trans('stalir-mc-bridge.forum.badge.title', { name: playerName }) },
        app.translator.trans('stalir-mc-bridge.forum.badge.label', { name: playerName })
      ),
      95
    );
  });

  // The sidebar entry linking to the public server status page.
  //
  // That page is rendered by PHP (StatusPageController), so this is a plain
  // anchor on purpose: a Mithril Link would intercept the click and ask the
  // frontend router for a component that does not exist.
  extend('flarum/forum/components/IndexSidebar', 'navItems', function (items) {
    const baseUrl = String(app.forum.attribute('baseUrl') || '').replace(/\/$/, '');

    items.add(
      'mcBridgeStatus',
      m('a.Button.Button--link', { href: `${baseUrl}/mc-bridge/status` }, [
        m('i.icon.fas.fa-server'),
        ' ',
        app.translator.trans('stalir-mc-bridge.forum.status.nav'),
      ]),
      50
    );
  });
});
