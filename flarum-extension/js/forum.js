import app from 'flarum/forum/app';
import { extend } from 'flarum/common/extend';
import McBridgeSection from './src/forum/components/McBridgeSection';

app.initializers.add('stalir-mc-bridge', () => {
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
});
