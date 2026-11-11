package skyislands;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod("skyislands")
public final class SkyIslandsMod {
    private static final DeferredRegister<MapCodec<? extends DensityFunction>> TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, "skyislands");

    static {
        TYPES.register("terrain", () -> SkyTerrain.MAP_CODEC);
    }

    public SkyIslandsMod(IEventBus modBus) {
        TYPES.register(modBus);
        NeoForge.EVENT_BUS.addListener(StructureDensity::onLevelLoad);
    }
}
