package gamebridge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Game Bridge: lets a tool outside the game read and change the running server through a local socket, the way a
 * browser's DevTools port lets a tool drive a page. See Bridge for the protocol and Methods for what it can do.
 *
 * It only ever runs on a dedicated server. On a client, or a client's built-in server, the mod does nothing.
 */
@Mod("gamebridge")
public final class GameBridgeMod {
    public GameBridgeMod(IEventBus modBus) {
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) return;
        NeoForge.EVENT_BUS.addListener(GameBridgeMod::onStarted);
        NeoForge.EVENT_BUS.addListener(GameBridgeMod::onStopping);
        GameEvents.register();
    }

    private static void onStarted(ServerStartedEvent event) {
        Bridge.start(event.getServer());
    }

    private static void onStopping(ServerStoppingEvent event) {
        Bridge.stop();
    }
}
