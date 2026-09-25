package cn.stalir.mcbridge.neoforge;

import cn.stalir.mcbridge.Message;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

/**
 * Turns core {@link Message}s into Minecraft components.
 *
 * This is the NeoForge counterpart of the paper module's AdventureMessages, and
 * it exists because Minecraft 1.21.1 does not ship Adventure at all: its own
 * {@code net.minecraft.network.chat.Component} is the only option here. The
 * shared core therefore renders {@link Message} and each platform converts at
 * the point of delivery.
 */
final class NeoForgeMessages {

    /**
     * The same ampersand codes Adventure's legacy serializer recognises.
     *
     * Only a real code is rewritten, so an ampersand that is not followed by one
     * survives untouched - which matters because announcement bodies can contain
     * URLs with query strings.
     */
    private static final Pattern LEGACY_CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    private NeoForgeMessages() {
    }

    /** Render a core message; Minecraft itself renders the legacy codes. */
    static Component render(Message message) {
        return Component.literal(LEGACY_CODE.matcher(message.legacy()).replaceAll("\u00a7$1"));
    }

    /**
     * Answer the sender of a command.
     *
     * {@code sendSuccess} with {@code false} delivers to that source only, which
     * matches what the Paper module does with its replies.
     */
    static void send(CommandSourceStack source, Message message) {
        source.sendSuccess(() -> render(message), false);
    }
}
