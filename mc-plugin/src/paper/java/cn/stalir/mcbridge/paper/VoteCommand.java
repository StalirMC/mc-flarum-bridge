package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.BridgeConfig;
import cn.stalir.mcbridge.BridgeCore;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * {@code /vote <编号>} - votes in the activity poll currently announced in
 * game.
 *
 * The request is blocking, so it runs on the platform's asynchronous pool and
 * the rendered reply is handed back to the main thread / global region.
 */
public final class VoteCommand implements CommandExecutor {

    private final BridgeCore core;

    public VoteCommand(BridgeCore core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.messages().prefixed("players-only"));
            return true;
        }

        if (!player.hasPermission("mcbridge.vote")) {
            player.sendMessage(core.messages().prefixed("no-permission"));
            return true;
        }

        long activityId = core.activeActivity();

        if (activityId <= 0L) {
            player.sendMessage(core.messages().prefixed("vote-none"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(core.messages().prefixed("vote-usage"));
            return true;
        }

        BridgeConfig config = core.config();

        if (!config.isUsable()) {
            player.sendMessage(core.messages().prefixed("vote-failed", "reason",
                    String.join("; ", config.problems())));
            return true;
        }

        int optionIndex;

        try {
            optionIndex = Integer.parseInt(args[0]) - 1;
        } catch (NumberFormatException exception) {
            player.sendMessage(core.messages().prefixed("vote-usage"));
            return true;
        }

        if (optionIndex < 0) {
            player.sendMessage(core.messages().prefixed("vote-usage"));
            return true;
        }

        // Read the player state before leaving the main thread.
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        core.platform().runAsync(() -> {
            Component reply = core.voteResult(activityId, playerUuid, playerName, optionIndex);

            core.platform().runSync(() -> player.sendMessage(reply));
        });

        return true;
    }
}