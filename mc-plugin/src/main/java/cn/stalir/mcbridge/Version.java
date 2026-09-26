package cn.stalir.mcbridge;

/**
 * Version reported to the forum, in the HTTP User-Agent.
 *
 * It is a compile-time constant because it is baked into a class: plugin.yml and
 * the NeoForge mod descriptor are expanded by Gradle from gradle.properties, but
 * this one cannot be. The build therefore keeps it in sync with
 * {@code gradle.properties} and {@code tools/verify.mjs} fails when the two
 * drift apart.
 */
public final class Version {

    public static final String VERSION = "0.0.20";

    private Version() {
    }
}
