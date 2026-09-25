package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.BridgeConfig;
import cn.stalir.mcbridge.BridgeCore;
import cn.stalir.mcbridge.Message;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * {@code /bind} - asks the forum for a one-time code that links this Minecraft
 * account to a Flarum account.
 *
 * The request is blocking, so it runs on the platform's asynchronous pool and
 * the rendered reply is handed back to the main thread / global region. Every
 * line comes from the shared core, so Paper, Folia and NeoForge answer with
 * identical wording.
 */
public final class BindCommand implements CommandExecutor {

    private final BridgeCore core;

    public BindCommand(BridgeCore core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            AdventureMessages.send(sender, core.messages().prefixed("players-only"));
            return true;
        }

        if (!player.hasPermission("mcbridge.bind")) {
            AdventureMessages.send(player, core.messages().prefixed("no-permission"));
            return true;
        }

        BridgeConfig config = core.config();

        // Local check, no I/O: the forum cannot hand out a code while the
        // configuration is unusable, so answer immediately.
        if (!config.isUsable()) {
            AdventureMessages.send(player, core.messages().prefixed(
                    "bind-failed",
                    "reason",
                    String.join("; ", config.problems())
            ));
            return true;
        }

        AdventureMessages.send(player, core.messages().prefixed("bind-requesting"));

        // Read the player state before leaving the main thread.
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();

        core.platform().runAsync(() -> {
            List<Message> reply = core.bindMessages(uuid, playerName);

            core.platform().runSync(() -> {
                for (Message line : reply) {
                    AdventureMessages.send(player, line);
                }
            });
        });

        return true;
    }
}
