package combatkick;

import net.bettercombat.api.client.BetterCombatClientEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only add-on to Better Combat: each melee hit kicks the camera in the direction of the swing,
 * and a damped spring sways it back. Purely visual; aim and the server are untouched.
 */
@Mod(value = CombatKick.MODID, dist = Dist.CLIENT)
public final class CombatKick {
    public static final String MODID = "combatkick";

    public CombatKick(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, KickConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(KillIcons::registerLayer);
        NeoForge.EVENT_BUS.addListener(KillIcons::onClientTick);
        NeoForge.EVENT_BUS.addListener(CameraKick::onCameraAngles);
        NeoForge.EVENT_BUS.addListener(CameraKick::onFov);
        NeoForge.EVENT_BUS.addListener(KeepMovement::onMovementInput);
        BetterCombatClientEvents.ATTACK_HIT.register(CameraKick::onAttackHit);
        BetterCombatClientEvents.ATTACK_HIT.register(RagdollFling::onAttackHit);
    }
}
