package cn.stalir.mcbridge.command;

import cn.stalir.mcbridge.BridgeException;
import cn.stalir.mcbridge.McBridgePlugin;
import com.google.gson.JsonObject;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * {@code /bind} - asks the forum for a one-time code that links this Minecraft
 * account to a Flarum account.
 */
public final class BindCommand implements CommandExecutor {

    private final McBridgePlugin plugin;

    public BindCommand(McBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().prefixed("players-only"));
            return true;
        }

        if (!player.hasPermission("mcbridge.bind")) {
            player.sendMessage(plugin.messages().prefixed("no-permission"));
            return true;
        }

        if (!plugin.config().isUsable()) {
            player.sendMessage(plugin.messages().prefixed(
                    "bind-failed",
                    "reason",
                    String.join("; ", plugin.config().problems())
            ));
            return true;
        }

        player.sendMessage(plugin.messages().prefixed("bind-requesting"));

        UUID uuid = player.getUniqueId();
        String playerName = player.getName();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JsonObject response = plugin.client().bindStart(uuid, playerName);

                plugin.getServer().getScheduler().runTask(plugin, () -> report(player, response));
            } catch (BridgeException exception) {
                plugin.getServer().getScheduler().runTask(plugin, () -> player.sendMessage(
                        plugin.messages().prefixed("bind-failed", "reason", exception.getMessage())
                ));
            }
        });

        return true;
    }

    private void report(Player player, JsonObject response) {
        if (response.has("already_bound") && response.get("already_bound").getAsBoolean()) {
            player.sendMessage(plugin.messages().prefixed("bind-already", "user", boundUsername(response)));
            return;
        }

        if (!response.has("code") || !response.get("code").isJsonPrimitive()) {
            player.sendMessage(plugin.messages().prefixed("bind-failed", "reason", "the forum did not return a code"));
            return;
        }

        int minutes = response.has("expires_in_seconds")
                ? Math.max(1, response.get("expires_in_seconds").getAsInt() / 60)
                : 10;

        player.sendMessage(plugin.messages().prefixed(
                "bind-code",
                "code",
                response.get("code").getAsString(),
                "minutes",
                String.valueOf(minutes)
        ));

        player.sendMessage(plugin.messages().prefixed("bind-hint"));
    }

    private String boundUsername(JsonObject response) {
        if (response.has("binding") && response.get("binding").isJsonObject()) {
            JsonObject binding = response.getAsJsonObject("binding");

            if (binding.has("username") && binding.get("username").isJsonPrimitive()) {
                return binding.get("username").getAsString();
            }
        }

        return "?";
    }
}
