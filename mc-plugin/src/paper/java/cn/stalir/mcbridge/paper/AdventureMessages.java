package cn.stalir.mcbridge.paper;

import cn.stalir.mcbridge.Message;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

/**
 * Turns core {@link Message}s into Adventure components.
 *
 * Only a platform module may do this: Paper ships Adventure, Minecraft 1.21.1 -
 * and therefore NeoForge - does not, so the shared core stops at {@link Message}
 * and each platform converts at the point of delivery. The NeoForge module has
 * the equivalent class built on {@code net.minecraft.network.chat.Component}.
 */
final class AdventureMessages {

    private AdventureMessages() {
    }

    /** Render a core message with the ampersand codes the language files use. */
    static Component render(Message message) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message.legacy());
    }

    /**
     * Send a core message to any Bukkit sender, console included.
     *
     * Kept as a call with the sender first so command code reads naturally and
     * the conversion cannot be forgotten at a call site.
     */
    static void send(CommandSender sender, Message message) {
        sender.sendMessage(render(message));
    }
}
