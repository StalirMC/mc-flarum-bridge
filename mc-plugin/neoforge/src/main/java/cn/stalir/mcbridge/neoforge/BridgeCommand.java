package cn.stalir.mcbridge.neoforge;

import cn.stalir.mcbridge.BridgeConfig;
import cn.stalir.mcbridge.BridgeCore;
import cn.stalir.mcbridge.Message;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /mcbridge <news|outbox|broadcast|stats|reload>} - administration and
 * diagnostics.
 *
 * Every reply is rendered by the shared {@link BridgeCore}, so the whole command
 * follows the configured language on every platform. Only {@code stats},
 * {@code reload} and the permission replies are answered locally: the
 * subcommands that talk to the forum run on the plugin's asynchronous pool and
 * hand their messages back to the server thread.
 *
 * Permission levels stand in for the Bukkit permission nodes the Paper module
 * uses: {@code news} is open to everyone, everything else needs operator level 2.
 */
final class BridgeCommand {

    private BridgeCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<BridgeCore> core) {
        dispatcher.register(
                Commands.literal("mcbridge")
                        .then(Commands.literal("news")
                                .executes(context -> executeNews(context, core)))
                        .then(Commands.literal("outbox")
                                .requires(BridgeCommand::isAdmin)
                                .executes(context -> executeOutbox(context, core)))
                        .then(Commands.literal("stats")
                                .requires(BridgeCommand::isAdmin)
                                .executes(context -> executeStats(context, core)))
                        .then(Commands.literal("reload")
                                .requires(BridgeCommand::isAdmin)
                                .executes(context -> executeReload(context, core)))
                        .then(Commands.literal("broadcast")
                                .requires(BridgeCommand::isAdmin)
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(context -> executeBroadcast(context, core))))
                        .executes(context -> executeHelp(context, core)));
    }

    private static boolean isAdmin(CommandSourceStack source) {
        return source.hasPermission(2);
    }

    /** Help and statistics arrive as pre-rendered legacy lines, sent one by one. */
    private static void sendLines(CommandSourceStack source, List<String> lines, BridgeCore core) {
        for (String line : lines) {
            NeoForgeMessages.send(source, core.messages().legacy(line));
        }
    }

    private static int executeHelp(CommandContext<CommandSourceStack> context, Supplier<BridgeCore> supplier) {
        BridgeCore core = supplier.get();

        if (core == null) {
            return 0;
        }

        if (!isAdmin(context.getSource())) {
            NeoForgeMessages.send(context.getSource(), core.messages().prefixed("no-permission"));
            return 0;
        }

        sendLines(context.getSource(), core.helpLines(), core);

        return 1;
    }

    private static int executeStats(CommandContext<CommandSourceStack> context, Supplier<BridgeCore> supplier) {
        BridgeCore core = supplier.get();

        if (core == null) {
            return 0;
        }

        // Local counters, no I/O: safe to answer on the server thread.
        sendLines(context.getSource(), core.statsLines(), core);

        return 1;
    }

    private static int executeReload(CommandContext<CommandSourceStack> context, Supplier<BridgeCore> supplier) {
        BridgeCore core = supplier.get();

        if (core == null) {
            return 0;
        }

        CommandSourceStack source = context.getSource();

        core.reload();

        BridgeConfig config = core.config();
        NeoForgeMessages.send(source, core.messages().prefixed("reloaded"));

        if (!config.isUsable()) {
            NeoForgeMessages.send(source, core.messages().prefixed(
                    "reload-problems",
                    "problems", String.join("; ", config.problems())
            ));
        }

        return 1;
    }

    private static int executeNews(CommandContext<CommandSourceStack> context, Supplier<BridgeCore> supplier) {
        BridgeCore core = supplier.get();

        if (core == null) {
            return 0;
        }

        CommandSourceStack source = context.getSource();

        core.platform().runAsync(() -> {
            List<Message> reply = core.newsMessages(5);

            core.platform().runSync(() -> {
                for (Message line : reply) {
                    NeoForgeMessages.send(source, line);
                }
            });
        });

        return 1;
    }

    private static int executeOutbox(CommandContext<CommandSourceStack> context, Supplier<BridgeCore> supplier) {
        BridgeCore core = supplier.get();

        if (core == null) {
            return 0;
        }

        CommandSourceStack source = context.getSource();

        core.platform().runAsync(() -> {
            List<Message> reply = core.outboxMessages();

            core.platform().runSync(() -> {
                for (Message line : reply) {
                    NeoForgeMessages.send(source, line);
                }
            });
        });

        return 1;
    }

    private static int executeBroadcast(CommandContext<CommandSourceStack> context, Supplier<BridgeCore> supplier) {
        BridgeCore core = supplier.get();

        if (core == null) {
            return 0;
        }

        String text = StringArgumentType.getString(context, "text");
        CommandSourceStack source = context.getSource();

        core.platform().runAsync(() -> {
            // No title: the forum derives one from the body.
            Message reply = core.broadcastResult(text, null);

            core.platform().runSync(() -> NeoForgeMessages.send(source, reply));
        });

        return 1;
    }
}
