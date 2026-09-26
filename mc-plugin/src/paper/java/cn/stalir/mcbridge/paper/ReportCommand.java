package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.BridgeConfig;
import cn.stalir.mcbridge.BridgeCore;
import cn.stalir.mcbridge.Message;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * {@code /report <player> <reason>} - reports a player to the forum moderators.
 *
 * {@code /report status} lists what happened to the reports this player has
 * already filed, which is the only feedback the game side can offer: the
 * moderators decide on the forum.
 *
 * Both requests are blocking, so they run on the platform's asynchronous pool
 * and the rendered replies are handed back to the main thread / global region.
 */
public final class ReportCommand implements CommandExecutor {

    private final BridgeCore core;

    public ReportCommand(BridgeCore core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            AdventureMessages.send(sender, core.messages().prefixed("players-only"));
            return true;
        }

        if (!player.hasPermission("mcbridge.report")) {
            AdventureMessages.send(player, core.messages().prefixed("no-permission"));
            return true;
        }

        // Checked before the argument count, because this is the one form that
        // takes no target. Reporting a player who is actually called "status"
        // still works: that needs two arguments and falls through.
        if (args.length == 1 && "status".equalsIgnoreCase(args[0])) {
            fetchStatus(player);
            return true;
        }

        if (args.length < 2) {
            AdventureMessages.send(player, core.messages().prefixed("report-usage"));
            return true;
        }

        BridgeConfig config = core.config();

        // Local check, no I/O: the forum cannot accept a report while the
        // configuration is unusable, so answer immediately.
        if (!config.isUsable()) {
            AdventureMessages.send(player, core.messages().prefixed(
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

        // PlaceholderAPI needs the live player and its expansions carry no
        // threading guarantees, so %placeholders% are expanded here on the main
        // thread. The plugin's own {tokens} are filled in off-thread, together
        // with the rest of the report.
        String titleTemplate = config.reportTitleFormat();

        if (PlaceholderApiHook.available()) {
            titleTemplate = PlaceholderApiHook.resolve(player, titleTemplate);
        }

        String resolvedTemplate = titleTemplate;

        core.platform().runAsync(() -> {
            Message reply = core.reportPlayer(reporterUuid, reporterName, targetName, reason, resolvedTemplate);

            core.platform().runSync(() -> AdventureMessages.send(player, reply));
        });

        return true;
    }

    /** List this player's own reports and what the moderators did with them. */
    private void fetchStatus(Player player) {
        UUID reporterUuid = player.getUniqueId();

        core.platform().runAsync(() -> {
            List<Message> reply = core.reportStatusMessages(reporterUuid);

            core.platform().runSync(() -> {
                for (Message line : reply) {
                    AdventureMessages.send(player, line);
                }
            });
        });
    }
}
