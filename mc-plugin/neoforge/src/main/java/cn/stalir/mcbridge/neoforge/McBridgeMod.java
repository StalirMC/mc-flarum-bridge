package cn.stalir.mcbridge.neoforge;

import cn.stalir.mcbridge.BridgeCore;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Entry point of the Minecraft side of the bridge on NeoForge.
 *
 * Like the Paper plugin, the mod stays thin: everything that talks to the forum
 * lives in the shared {@link BridgeCore} and everything that touches the server
 * lives in {@link NeoForgePlatform}. That is what lets one set of core classes
 * serve Paper, Folia and NeoForge with byte-identical protocol behaviour.
 *
 * Lifecycle: the bridge is started when the server has started and stopped when
 * it begins stopping, so a reload of the world does not leave the polling tasks
 * pointing at a dead server.
 */
@Mod(McBridgeMod.MODID)
public final class McBridgeMod {

    public static final String MODID = "mc_bridge";

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Set once the server is running.
     *
     * Commands are registered before that point and resolve the core per
     * invocation, so this is only ever read on the server thread.
     */
    private BridgeCore core;
    private NeoForgePlatform platform;

    public McBridgeMod(IEventBus modEventBus, ModContainer modContainer) {
        // Commands, the server lifecycle and player joins are all game events,
        // which live on the NeoForge bus rather than the mod bus.
        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("MC Bridge loaded; the bridge starts when the server does");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BindCommand.register(event.getDispatcher(), this::core);
        ReportCommand.register(event.getDispatcher(), this::core);
        BridgeCommand.register(event.getDispatcher(), this::core);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        platform = new NeoForgePlatform(new NeoForgeLog(LOGGER), event.getServer());
        core = new BridgeCore(platform);

        // Reads config.yml, extracts the bundled language files and schedules the
        // periodic polling tasks.
        core.start();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (core != null) {
            core.stop();
            core = null;
        }

        if (platform != null) {
            platform.shutdown();
            platform = null;
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        BridgeCore current = core;

        if (current == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // Tells a player who has not linked a forum account how to do it.
        current.promptBindingIfNeeded(player.getUUID(), player.getGameProfile().getName());
    }

    /**
     * Record public chat, so a report can carry what the reported player said.
     *
     * Private messages never reach this event, and the buffer behind
     * {@link BridgeCore#recordChat} is bounded, so this cannot grow with uptime.
     */
    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        BridgeCore current = core;

        if (current == null) {
            return;
        }

        current.recordChat(
                event.getPlayer().getGameProfile().getName(),
                event.getMessage().getString()
        );
    }

    private BridgeCore core() {
        return core;
    }
}
