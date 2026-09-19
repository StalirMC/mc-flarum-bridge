package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.BridgeConfig;
import cn.stalir.mcbridge.BridgeCore;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /vote <编号> [编号...]} - votes in the forum poll currently announced
 * in game.
 *
 * The vote is cast as the forum account the player bound with {@code /bind}, so
 * it appears on the forum exactly as if they had used the website. Players who
 * have not linked an account get the forum's own explanation back.
 *
 * Multiple numbers (space or comma separated) support multi-choice polls: the
 * forum decides whether that many selections are allowed.
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

        if (args.length < 1) {
            player.sendMessage(core.messages().prefixed("vote-usage"));
            return true;
        }

        long pollId = core.activePoll();

        if (pollId <= 0L) {
            player.sendMessage(core.messages().prefixed("vote-none"));
            return true;
        }

        BridgeConfig config = core.config();

        if (!config.isUsable()) {
            player.sendMessage(core.messages().prefixed("vote-failed", "reason",
                    String.join("; ", config.problems())));
            return true;
        }

        List<Integer> numbers = new ArrayList<>();

        for (String argument : args) {
            for (String part : argument.split(",")) {
                String trimmed = part.trim();

                if (trimmed.isEmpty()) {
                    continue;
                }

                try {
                    numbers.add(Integer.parseInt(trimmed));
                } catch (NumberFormatException exception) {
                    // Not a number at all: show the usage instead of guessing.
                    player.sendMessage(core.messages().prefixed("vote-usage"));
                    return true;
                }
            }
        }

        if (numbers.isEmpty()) {
            player.sendMessage(core.messages().prefixed("vote-usage"));
            return true;
        }

        // Read the player state before leaving the main thread.
        UUID playerUuid = player.getUniqueId();

        core.platform().runAsync(() -> {
            Component reply = core.pollVoteResult(pollId, playerUuid, numbers);

            core.platform().runSync(() -> player.sendMessage(reply));
        });

        return true;
    }
}
