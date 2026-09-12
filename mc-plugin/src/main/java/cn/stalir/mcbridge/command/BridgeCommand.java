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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /mcbridge <status|outbox|broadcast|stats|reload>} - administration and
 * diagnostics.
 *
 * Every line is rendered from lang/&lt;language&gt;.yml, so the whole command
 * follows the configured language.
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
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadBridge();
                sender.sendMessage(plugin.messages().prefixed("reloaded"));

                if (!plugin.config().isUsable()) {
                    sender.sendMessage(plugin.messages().prefixed(
                            "reload-problems",
                            "problems", String.join("; ", plugin.config().problems())
                    ));
                }
            }

            case "status" -> fetchStatus(sender);

            case "outbox" -> fetchOutbox(sender);

            case "broadcast" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.messages().prefixed("broadcast-usage"));
                    return true;
                }

                String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                sendBroadcast(sender, text);
            }

            case "stats" -> sendStats(sender);

            default -> sender.sendMessage(plugin.messages().prefixed(
                    "unknown-subcommand",
                    "available", String.join(", ", SUBCOMMANDS)
            ));
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        String help = String.join("\n",
                plugin.messages().string("help-header"),
                plugin.messages().string("help-status"),
                plugin.messages().string("help-outbox"),
                plugin.messages().string("help-broadcast"),
                plugin.messages().string("help-stats"),
                plugin.messages().string("help-reload"));

        sender.sendMessage(plugin.messages().legacy(help));
    }

    private void sendStats(CommandSender sender) {
        String state = plugin.config().isUsable()
                ? plugin.messages().string("stats-config-ok")
                : plugin.messages().string("stats-config-bad");

        String stats = String.join("\n",
                plugin.messages().string("stats-header"),
                plugin.messages().string("stats-server-key", "key", plugin.config().serverKey()),
                plugin.messages().string("stats-forum-url", "url", plugin.config().forumUrl()),
                plugin.messages().string("stats-locale", "locale", plugin.messages().language()),
                plugin.messages().string("stats-queue",
                        "queued", String.valueOf(plugin.eventQueue().size()),
                        "dropped", String.valueOf(plugin.eventQueue().droppedCount())),
                plugin.messages().string("stats-delivered", "delivered", String.valueOf(plugin.deliveredEvents())),
                plugin.messages().string("stats-received", "received", String.valueOf(plugin.receivedMessages())),
                plugin.messages().string("stats-config", "state", state));

        sender.sendMessage(plugin.messages().legacy(stats));
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

                    sender.sendMessage(plugin.messages().prefixed(
                            "outbox-header",
                            "count", String.valueOf(messages.size())
                    ));

                    for (JsonElement element : messages) {
                        if (!element.isJsonObject()) {
                            continue;
                        }

                        JsonObject message = element.getAsJsonObject();

                        sender.sendMessage(plugin.messages().render(
                                "outbox-line",
                                "type", readString(message, "type", "?"),
                                "title", readString(message, "title", plugin.messages().raw("outbox-untitled"))
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
