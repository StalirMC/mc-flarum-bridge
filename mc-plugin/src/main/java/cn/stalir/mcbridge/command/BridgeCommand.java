package cn.stalir.mcbridge.command;

import cn.stalir.mcbridge.BridgeException;
import cn.stalir.mcbridge.McBridgePlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /mcbridge <status|outbox|broadcast|stats|reload>} - administration and
 * diagnostics.
 */
public final class BridgeCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("status", "outbox", "broadcast", "stats", "reload");

    private final McBridgePlugin plugin;

    public BridgeCommand(McBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mcbridge.admin")) {
            sender.sendMessage(plugin.messages().prefixed("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(plugin.messages().legacy(
                    "&8&m--------&r &2McBridge &8&m--------\n"
                            + "&7/mcbridge status &8- &f查看论坛记录的服务器状态\n"
                            + "&7/mcbridge outbox &8- &f查看待投递消息\n"
                            + "&7/mcbridge broadcast <内容> &8- &f向所有服务器推送一条消息\n"
                            + "&7/mcbridge stats &8- &f本地统计\n"
                            + "&7/mcbridge reload &8- &f重新加载配置"
            ));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadBridge();
                sender.sendMessage(plugin.messages().prefixed("reloaded"));

                if (!plugin.config().isUsable()) {
                    sender.sendMessage(plugin.messages().legacy("&c配置仍有问题：&7" + String.join("; ", plugin.config().problems())));
                }
            }

            case "status" -> fetchStatus(sender);

            case "outbox" -> fetchOutbox(sender);

            case "broadcast" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.messages().legacy("&c用法: /mcbridge broadcast <内容>"));
                    return true;
                }

                String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                sendBroadcast(sender, text);
            }

            case "stats" -> sender.sendMessage(plugin.messages().legacy(
                    "&8&m--------&r &2McBridge &8&m--------\n"
                            + "&7服务器标识: &f" + plugin.config().serverKey() + "\n"
                            + "&7论坛地址: &f" + plugin.config().forumUrl() + "\n"
                            + "&7待发送事件: &f" + plugin.eventQueue().size()
                            + " &8(丢弃 " + plugin.eventQueue().droppedCount() + ")\n"
                            + "&7已投递事件: &f" + plugin.deliveredEvents() + "\n"
                            + "&7已接收消息: &f" + plugin.receivedMessages() + "\n"
                            + "&7配置状态: " + (plugin.config().isUsable() ? "&a正常" : "&c有问题")
            ));

            default -> sender.sendMessage(plugin.messages().legacy("&c未知子命令。可用: &f" + String.join(", ", SUBCOMMANDS)));
        }

        return true;
    }

    private void fetchStatus(CommandSender sender) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JsonObject response = plugin.client().fetchStatus();

                JsonObject totals = response.has("totals") && response.get("totals").isJsonObject()
                        ? response.getAsJsonObject("totals")
                        : new JsonObject();

                String servers = readString(totals, "servers", "0");
                String serversOnline = readString(totals, "servers_online", "0");
                String players = readString(totals, "players_online", "0");

                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(
                        plugin.messages().prefixed(
                                "status-online",
                                "servers", servers,
                                "servers_online", serversOnline,
                                "players", players
                        )
                ));
            } catch (BridgeException exception) {
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(
                        plugin.messages().prefixed("status-unreachable", "reason", exception.getMessage())
                ));
            }
        });
    }

    private void fetchOutbox(CommandSender sender) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JsonObject response = plugin.client().fetchOutbox(true);

                JsonArray messages = response.has("messages") && response.get("messages").isJsonArray()
                        ? response.getAsJsonArray("messages")
                        : new JsonArray();

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (messages.isEmpty()) {
                        sender.sendMessage(plugin.messages().prefixed("outbox-empty"));
                        return;
                    }

                    sender.sendMessage(plugin.messages().prefixed("outbox-header", "count", String.valueOf(messages.size())));

                    for (JsonElement element : messages) {
                        if (!element.isJsonObject()) {
                            continue;
                        }

                        JsonObject message = element.getAsJsonObject();

                        sender.sendMessage(plugin.messages().render(
                                "outbox-line",
                                "type", readString(message, "type", "?"),
                                "title", readString(message, "title", "(无标题)")
                        ));
                    }
                });
            } catch (BridgeException exception) {
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(
                        plugin.messages().prefixed("status-unreachable", "reason", exception.getMessage())
                ));
            }
        });
    }

    private void sendBroadcast(CommandSender sender, String text) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.client().broadcast(text, null);
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        sender.sendMessage(plugin.messages().prefixed("broadcast-sent")));
            } catch (BridgeException exception) {
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(
                        plugin.messages().prefixed("status-unreachable", "reason", exception.getMessage())
                ));
            }
        });
    }

    private static String readString(JsonObject object, String key, String fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive()) {
            return object.get(key).getAsString();
        }

        return fallback;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mcbridge.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();

            for (String subcommand : SUBCOMMANDS) {
                if (subcommand.startsWith(prefix)) {
                    matches.add(subcommand);
                }
            }

            return matches;
        }

        return List.of();
    }
}
