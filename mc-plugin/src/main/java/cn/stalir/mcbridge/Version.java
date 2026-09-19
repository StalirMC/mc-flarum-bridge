package cn.stalir.mcbridge;

/**
 * Version reported to the forum, in the User-Agent and by the Velocity plugin
 * descriptor.
 *
 * It is a compile-time constant on purpose: Velocity's {@code @Plugin}
 * annotation requires one, so the value cannot be injected by Gradle. The build
 * therefore keeps it in sync with {@code gradle.properties} and
 * {@code tools/verify.mjs} fails when the two drift apart.
 */
public final class Version {

    public static final String VERSION = "0.0.11";

    private Version() {
    }
}
