package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.BridgeCore;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Records public chat so that a {@code /report} can carry what the reported
 * player actually said.
 *
 * {@link AsyncChatEvent} is the event Paper supports; the deprecated
 * {@code AsyncPlayerChatEvent} is deliberately not used. This listener only ever
 * observes, and it is registered at MONITOR priority so it sees whatever the
 * message finally became after other plugins rewrote it.
 *
 * A cancelled message is skipped: it was never shown to anyone, so recording it
 * would put words in the player's mouth.
 */
public final class ChatListener implements Listener {

    private final BridgeCore core;

    public ChatListener(BridgeCore core) {
        this.core = core;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        core.recordChat(
                event.getPlayer().getName(),
                PlainTextComponentSerializer.plainText().serialize(event.message())
        );
    }
}
