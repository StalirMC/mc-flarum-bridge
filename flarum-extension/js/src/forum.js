import app from 'flarum/forum/app';
import { extend } from 'flarum/common/extend';
import SettingsPage from 'flarum/forum/components/SettingsPage';
import m from 'mithril';
import McBridgeSection from './components/McBridgeSection';

app.initializers.add('stalir-mc-bridge', () => {
  // Adds a "Minecraft account" section to the user's settings page, where the
  // binding code shown by /bind in game can be entered.
  extend(SettingsPage.prototype, 'settingsItems', function (items) {
    items.add('mc-bridge', m(McBridgeSection), 12);
  });
});
