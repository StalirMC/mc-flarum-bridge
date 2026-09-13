import app from 'flarum/forum/app';
import { extend } from 'flarum/common/extend';
import SettingsPage from 'flarum/forum/components/SettingsPage';
import McBridgeSection from './src/forum/components/McBridgeSection';

app.initializers.add('stalir-mc-bridge', () => {
  // Adds a "Minecraft account" section to the user's settings page, where the
  // binding code shown by /bind in game can be entered.
  extend(SettingsPage.prototype, 'settingsItems', function (items) {
    // m is the global Mithril hyperscript function that Flarum's own bundle
    // exposes (via expose-loader); JSX compiles to it as well.
    items.add('mc-bridge', m(McBridgeSection), 12);
  });
});
