package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.BridgeConfig;
import cn.stalir.mcbridge.BridgeCore;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.UUID;

/**
 * {@code /report <player> <reason>} - reports a player to the forum moderators.
 *
 * The request is blocking, so it runs on the platform's asynchronous pool and
 * the rendered reply is handed back to the main thread / global region.
 */
public final class ReportCommand implements CommandExecutor {

    private final BridgeCore core;

    public ReportCommand(BridgeCore core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.messages().prefixed("players-only"));
            return true;
        }

        if (!player.hasPermission("mcbridge.report")) {
            player.sendMessage(core.messages().prefixed("no-permission"));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(core.messages().prefixed("report-usage"));
            return true;
        }

        BridgeConfig config = core.config();

        // Local check, no I/O: the forum cannot accept a report while the
        // configuration is unusable, so answer immediately.
        if (!config.isUsable()) {
            player.sendMessage(core.messages().prefixed(
                    "report-failed",
                    "reason",
                    String.join("; ", config.problems())
            ));
            return true;
        }

        String targetName = args[0];
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        // Read the player state before leaving the main thread.
        UUID reporterUuid = player.getUniqueId();
        String reporterName = player.getName();

        core.platform().runAsync(() -> {
            Component reply = core.reportPlayer(reporterUuid, reporterName, targetName, reason);

            core.platform().runSync(() -> player.sendMessage(reply));
        });

        return true;
    }
}
