package cn.stalir.mcbridge.velocity;

import cn.stalir.mcbridge.BridgeConfig;
import cn.stalir.mcbridge.BridgeCore;
import cn.stalir.mcbridge.Messages;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import java.util.UUID;

/**
 * {@code /bind} - asks the forum for a one-time code that links this Minecraft
 * account to a Flarum account.
 *
 * The request blocks on HTTP, so it runs on the proxy scheduler. The reply is
 * sent from there as well: Velocity message sending is thread-safe.
 */
public final class VelocityBindCommand implements SimpleCommand {

    private static final String PERMISSION = "mcbridge.bind";

    private final McBridgeVelocityPlugin plugin;

    public VelocityBindCommand(McBridgeVelocityPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        BridgeCore core = plugin.core();

        // The commands are registered a few statements before core.start() reads
        // config.yml and the language files, so a command arriving in that
        // window is ignored rather than answered from a half-built core.
        if (core == null || core.config() == null || core.messages() == null) {
            return;
        }

        if (!(source instanceof Player player)) {
            source.sendMessage(core.messages().prefixed("players-only"));
            return;
        }

        Messages messages = core.messages();
        BridgeConfig config = core.config();

        if (!config.isUsable()) {
            player.sendMessage(messages.prefixed(
                    "bind-failed",
                    "reason",
                    String.join("; ", config.problems())
            ));
            return;
        }

        player.sendMessage(messages.prefixed("bind-requesting"));

        UUID uuid = player.getUniqueId();
        String playerName = player.getUsername();

        plugin.platform().runAsync(() -> {
            for (Component line : core.bindMessages(uuid, playerName)) {
                player.sendMessage(line);
            }
        });
    }
}
