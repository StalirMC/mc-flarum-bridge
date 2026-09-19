package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.BridgeConfig;
import cn.stalir.mcbridge.BridgeCore;
import net.kyori.adventure.text.Component;
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
 * Every reply is rendered by the shared {@link BridgeCore}, so the whole command
 * follows the configured language on every platform. Only {@code stats},
 * {@code reload} and the permission/usage replies are answered locally: the
 * subcommands that talk to the forum run on the platform's asynchronous pool and
 * hand their components back to the main thread / global region.
 */
public final class BridgeCommand implements CommandExecutor, TabCompleter {

    private final BridgeCore core;

    public BridgeCommand(BridgeCore core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mcbridge.admin")) {
            sender.sendMessage(core.messages().prefixed("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendLines(sender, core.helpLines());
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);


            case "outbox" -> fetchOutbox(sender);

            case "broadcast" -> sendBroadcast(sender, args);

            case "stats" -> sendLines(sender, core.statsLines());

            default -> sender.sendMessage(core.messages().prefixed(
                    "unknown-subcommand",
                    "available", String.join(", ", BridgeCore.subcommands())
            ));
        }

        return true;
    }

    /**
     * Help and statistics arrive from the core as pre-rendered legacy
     * (ampersand) lines, so each one is sent on its own.
     */
    private void sendLines(CommandSender sender, List<String> lines) {
        for (String line : lines) {
            sender.sendMessage(core.messages().legacy(line));
        }
    }

    private void reload(CommandSender sender) {
        core.reload();

        BridgeConfig config = core.config();
        sender.sendMessage(core.messages().prefixed("reloaded"));

        if (!config.isUsable()) {
            sender.sendMessage(core.messages().prefixed(
                    "reload-problems",
                    "problems", String.join("; ", config.problems())
            ));
        }
    }


    private void fetchOutbox(CommandSender sender) {
        core.platform().runAsync(() -> {
            List<Component> reply = core.outboxMessages();

            core.platform().runSync(() -> {
                for (Component line : reply) {
                    sender.sendMessage(line);
                }
            });
        });
    }

    private void sendBroadcast(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(core.messages().prefixed("broadcast-usage"));
            return;
        }

        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        core.platform().runAsync(() -> {
            // No title: the forum derives one from the body.
            Component reply = core.broadcastResult(text, null);

            core.platform().runSync(() -> sender.sendMessage(reply));
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mcbridge.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);

            // A fresh mutable list on purpose: the core hands out an immutable
            // one and Bukkit is free to reuse what a completer returns.
            List<String> matches = new ArrayList<>();

            for (String subcommand : BridgeCore.subcommands()) {
                if (subcommand.startsWith(prefix)) {
                    matches.add(subcommand);
                }
            }

            return matches;
        }

        return List.of();
    }
}
