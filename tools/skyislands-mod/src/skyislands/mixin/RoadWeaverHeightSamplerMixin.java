package skyislands.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import skyislands.SkyTerrain;

/**
 * RoadWeaver 2.3.2 plans roads from heights it samples with its own noise chunks, whose fluid picker only knows the
 * vanilla sea level. In a sky-island world that misses every lake and river, so roads were planned along river beds,
 * with their lamps standing in the water. Give the sampler our water too: then it sees the water depth, and its own
 * water costs and bridges keep roads out of the lakes and rivers.
 */
@Pseudo
@Mixin(targets = "net.shiroha233.roadweaver.pathfinding.cache.NoiseChunkHeightSampler")
abstract class RoadWeaverHeightSamplerMixin {
    @Redirect(method = "create(Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;Lnet/minecraft/world/level/levelgen/RandomState;)Lnet/shiroha233/roadweaver/pathfinding/cache/NoiseChunkHeightSampler;",
            at = @At(value = "INVOKE", target = "Lnet/shiroha233/roadweaver/pathfinding/cache/NoiseChunkHeightSampler;createFluidPicker(Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;)Lnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;"))
    private static Aquifer.FluidPicker skyislands$lakes(NoiseGeneratorSettings settings, @Local(argsOnly = true) RandomState randomState) {
        Aquifer.FluidPicker vanilla = createFluidPicker(settings);
        SkyTerrain[] found = new SkyTerrain[1];
        randomState.router().fluidLevelFloodednessNoise().mapAll(df -> {
            if (df instanceof SkyTerrain t) found[0] = t;
            return df;
        });
        if (found[0] == null) return vanilla;
        com.mojang.logging.LogUtils.getLogger().info("[skyislands] RoadWeaver's route planner samples heights with sky-island lake water");
        return found[0].columnFluidPicker(vanilla);
    }

    @org.spongepowered.asm.mixin.Shadow
    static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        throw new AssertionError();
    }
}
