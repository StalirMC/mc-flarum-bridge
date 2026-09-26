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
 * {@code AsyncPlayerChatEvent} is deliberately not used.
 *
 * Two details matter more than they look:
 *
 * <ul>
 *   <li><b>LOWEST priority, and cancellation is NOT skipped.</b> A cancelled chat
 *       event does not mean the message never happened - it is the normal shape of
 *       a server running a chat formatter, because those commonly cancel the event
 *       and broadcast their own version of it. A listener that only observed
 *       uncancelled events would silently record nothing on such a server, which
 *       from the forum is indistinguishable from a player who never spoke.</li>
 *   <li><b>The raw message is recorded, not the final one.</b> At LOWEST the
 *       component is still what the player typed, before another plugin prepends a
 *       prefix or rewrites it. For a moderation transcript that is the useful
 *       thing anyway.</li>
 * </ul>
 *
 * Commands and private messages never reach this event, so nothing else can end
 * up in the buffer.
 */
public final class ChatListener implements Listener {

    private final BridgeCore core;

    public ChatListener(BridgeCore core) {
        this.core = core;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        core.recordChat(
                event.getPlayer().getName(),
                PlainTextComponentSerializer.plainText().serialize(event.message())
        );
    }
}
