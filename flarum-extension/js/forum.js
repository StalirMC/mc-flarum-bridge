import app from 'flarum/forum/app';
import { extend } from 'flarum/common/extend';
import McBridgeSection from './src/forum/components/McBridgeSection';
import grassBlock from './src/forum/grassBlock';

const EXTENSION_ID = 'stalirmc-mc-bridge';

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
        m('label', app.translator.trans('stalirmc-mc-bridge.forum.profile.heading')),
        m('p', app.translator.trans('stalirmc-mc-bridge.forum.profile.bound_to', { name: playerName })),
        serverKey
          ? m(
              'p.helpText',
              app.translator.trans('stalirmc-mc-bridge.forum.profile.server', { key: serverKey })
            )
          : null,
      ]),
      40
    );
  });

  // A grass-block badge for every linked account, added to the user's badge list
  // rather than to one component.
  //
  // User#badges() is what every place that shows badges reads (the author area of
  // a post, the user card, the profile sidebar), so hooking it here makes the
  // marker appear everywhere at once and lets the theme style it together with
  // the group badges - which is what the brown box in the screenshot was missing.
  //
  // The grass block carries the player name as a tooltip; the profile page and
  // the settings section spell it out in full.
  extend('flarum/common/models/User', 'badges', function (items) {
    const playerName = this.attribute('mcBridgePlayerName');

    if (!playerName) {
      return;
    }

    items.add(
      'mcBridgeAccount',
      m(
        'span.Badge.McBridge-badge',
        {
          // The tooltip and the accessible label spell the account out; the badge
          // itself shows the grass block followed by the player name, without an
          // "MC:" prefix.
          title: app.translator.trans('stalirmc-mc-bridge.forum.badge.title', { name: playerName }),
          'aria-label': app.translator.trans('stalirmc-mc-bridge.forum.badge.title', { name: playerName }),
        },
        [
          grassBlock(14),
          ' ',
          app.translator.trans('stalirmc-mc-bridge.forum.badge.label', { name: playerName }),
        ]
      ),
      -5
    );
  });
});
