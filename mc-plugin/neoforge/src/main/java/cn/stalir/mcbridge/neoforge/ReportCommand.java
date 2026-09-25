package cn.stalir.mcbridge.neoforge;

import cn.stalir.mcbridge.BridgeConfig;
import cn.stalir.mcbridge.BridgeCore;
import cn.stalir.mcbridge.Message;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /report <player> <reason>} - reports a player to the forum moderators,
 * where the report becomes a discussion in the report tag.
 *
 * Minecraft has no server-side {@code /report} of its own - the vanilla one is
 * client-side chat reporting - so this literal cannot collide with it.
 */
final class ReportCommand {

    private ReportCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<BridgeCore> core) {
        dispatcher.register(
                Commands.literal("report")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> execute(context, core)))));
    }

    private static int execute(CommandContext<CommandSourceStack> context, Supplier<BridgeCore> supplier) {
        BridgeCore core = supplier.get();

        if (core == null) {
            return 0;
        }

        ServerPlayer player;

        try {
            player = context.getSource().getPlayerOrException();
        } catch (CommandSyntaxException exception) {
            NeoForgeMessages.send(context.getSource(), core.messages().prefixed("players-only"));
            return 0;
        }

        BridgeConfig config = core.config();

        // Local check, no I/O: the forum cannot accept a report while the
        // configuration is unusable, so answer immediately.
        if (!config.isUsable()) {
            NeoForgeMessages.send(context.getSource(), core.messages().prefixed(
                    "report-failed",
                    "reason", String.join("; ", config.problems())
            ));

            return 0;
        }

        String targetName = StringArgumentType.getString(context, "player");
        String reason = StringArgumentType.getString(context, "reason");
        CommandSourceStack source = context.getSource();

        // Read the player state before leaving the server thread.
        UUID reporterUuid = player.getUUID();
        String reporterName = player.getGameProfile().getName();

        // PlaceholderAPI has no equivalent here, so the template is passed through
        // untouched and the core fills in its own {tokens}.
        String titleTemplate = config.reportTitleFormat();

        core.platform().runAsync(() -> {
            Message reply = core.reportPlayer(reporterUuid, reporterName, targetName, reason, titleTemplate);

            core.platform().runSync(() -> NeoForgeMessages.send(source, reply));
        });

        return 1;
    }
}
