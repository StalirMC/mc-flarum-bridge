package cn.stalir.mcbridge;

/**
 * A rendered, platform-neutral chat message.
 *
 * The shared core deliberately does not use Adventure. Paper ships it, but
 * Minecraft 1.21.1 - and therefore NeoForge - does not: the version manifest for
 * 1.21.1 lists gson, guava and brigadier and no {@code net.kyori} library at all.
 * A core class that touched {@code net.kyori.adventure.text.Component} would
 * therefore fail to load on NeoForge with {@link NoClassDefFoundError}.
 *
 * A Message instead carries the legacy ampersand form that lang/*.yml already
 * uses, and each platform converts it to its own component type at the moment it
 * is delivered:
 *
 * <ul>
 *   <li>Paper/Folia - {@code LegacyComponentSerializer.legacyAmpersand()}</li>
 *   <li>NeoForge - {@code net.minecraft.network.chat.Component}</li>
 * </ul>
 *
 * Immutable, so a rendered message can be handed to another thread safely.
 */
public final class Message {

    private final String legacy;

    private Message(String legacy) {
        this.legacy = legacy;
    }

    /** Wrap an ampersand colour code string, as written in the language files. */
    public static Message of(String legacy) {
        return new Message(legacy == null ? "" : legacy);
    }

    /** A line break, for joining several messages into one block. */
    public static Message newline() {
        return new Message("\n");
    }

    /** Append another message to this one, on the same block. */
    public Message append(Message other) {
        if (other == null || other.legacy.isEmpty()) {
            return this;
        }

        return new Message(legacy + other.legacy);
    }

    /** The ampersand form, exactly as the language files spell it. */
    public String legacy() {
        return legacy;
    }

    /**
     * The same text with its colour codes removed.
     *
     * Console log lines are written this way: a log file has no use for colour
     * escapes, and this is what {@code PlainTextComponentSerializer} used to
     * produce.
     */
    public String plain() {
        return legacy.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    public boolean isEmpty() {
        return legacy.isEmpty();
    }

    @Override
    public String toString() {
        return legacy;
    }
}
