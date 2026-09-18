package cn.stalir.mcbridge.velocity;

import cn.stalir.mcbridge.BridgeCore;
import cn.stalir.mcbridge.Messages;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /mcbridge <status|outbox|broadcast|stats|reload>} - administration and
 * diagnostics.
 *
 * Every reply is rendered by the shared core from the configured language, so
 * the command behaves exactly like its Paper/Folia counterpart. The
 * subcommands that talk to the forum block, so they run on the proxy scheduler
 * and send their reply from there.
 */
public final class VelocityBridgeCommand implements SimpleCommand {

    private static final String PERMISSION = "mcbridge.admin";

    private final McBridgeVelocityPlugin plugin;

    public VelocityBridgeCommand(McBridgeVelocityPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.source().hasPermission(PERMISSION)) {
            return List.of();
        }

        String[] arguments = invocation.arguments();

        if (arguments.length > 1) {
            return List.of();
        }

        String prefix = arguments.length == 0 ? "" : arguments[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();

        for (String subcommand : BridgeCore.subcommands()) {
            if (subcommand.startsWith(prefix)) {
                matches.add(subcommand);
            }
        }

        return matches;
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

        String[] arguments = invocation.arguments();

        if (arguments.length == 0) {
            sendLines(source, core.helpLines(), core.messages());
            return;
        }

        switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "status" -> plugin.platform().runAsync(() -> source.sendMessage(core.statusMessage()));

            case "outbox" -> plugin.platform().runAsync(
                    () -> sendComponents(source, core.outboxMessages()));

            case "broadcast" -> {
                if (arguments.length < 2) {
                    source.sendMessage(core.messages().prefixed("broadcast-usage"));
                    return;
                }

                String body = String.join(" ", Arrays.copyOfRange(arguments, 1, arguments.length));

                plugin.platform().runAsync(() -> source.sendMessage(core.broadcastResult(body, null)));
            }

            case "stats" -> sendLines(source, core.statsLines(), core.messages());

            case "reload" -> {
                core.reload();

                // Read the messages again: the reload may have switched language.
                Messages messages = core.messages();
                source.sendMessage(messages.prefixed("reloaded"));

                if (!core.config().isUsable()) {
                    source.sendMessage(messages.prefixed(
                            "reload-problems",
                            "problems",
                            String.join("; ", core.config().problems())
                    ));
                }
            }

            default -> source.sendMessage(core.messages().prefixed(
                    "unknown-subcommand",
                    "available",
                    String.join(", ", BridgeCore.subcommands())
            ));
        }
    }

    /** Send every line from the language file to the source. */
    private static void sendLines(CommandSource source, List<String> lines, Messages messages) {
        for (String line : lines) {
            source.sendMessage(messages.legacy(line));
        }
    }

    /** Send every component the core already rendered; safe from the scheduler thread. */
    private static void sendComponents(CommandSource source, List<Component> lines) {
        for (Component line : lines) {
            source.sendMessage(line);
        }
    }
}
