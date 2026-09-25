package cn.stalir.mcbridge.neoforge;

import cn.stalir.mcbridge.BridgeCore;
import cn.stalir.mcbridge.Message;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /bind} - asks the forum for a one-time code that links this Minecraft
 * account to a Flarum account.
 *
 * The request blocks on HTTP, so it runs on the plugin's asynchronous pool and
 * the rendered reply is handed back to the server thread. Every line comes from
 * the shared core, so Paper, Folia and NeoForge answer identically.
 */
final class BindCommand {

    private BindCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<BridgeCore> core) {
        dispatcher.register(Commands.literal("bind").executes(context -> execute(context, core)));
    }

    private static int execute(CommandContext<CommandSourceStack> context, Supplier<BridgeCore> supplier) {
        // Resolved per invocation: commands are registered before the bridge is
        // started, so capturing the core here would capture null.
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

        // Read the player state before leaving the server thread.
        CommandSourceStack source = context.getSource();
        UUID uuid = player.getUUID();
        String playerName = player.getGameProfile().getName();

        NeoForgeMessages.send(source, core.messages().prefixed("bind-requesting"));

        core.platform().runAsync(() -> {
            List<Message> reply = core.bindMessages(uuid, playerName);

            core.platform().runSync(() -> {
                for (Message line : reply) {
                    NeoForgeMessages.send(source, line);
                }
            });
        });

        return 1;
    }
}
