package skyislands.mixin;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import skyislands.FastFill;
import skyislands.FastGen;
import skyislands.SkyTerrain;

/**
 * Vanilla has one sea level for the whole world. Worlds using SkyTerrain get per-lake water levels instead.
 *
 * In those worlds the noise fill also takes two shortcuts (FastGen), both exact: a chunk with no structure to blend
 * gets a zero blending density instead of the Beardifier, and a 4x4x4 cell whose corners are all below zero, in a chunk
 * like that and with no lake water in it, is left as air without working out each of its blocks.
 */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseBasedChunkGeneratorMixin {
    @Redirect(method = "createNoiseChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;forChunk(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/DensityFunctions$BeardifierOrMarker;Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;Lnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;Lnet/minecraft/world/level/levelgen/blending/Blender;)Lnet/minecraft/world/level/levelgen/NoiseChunk;"))
    private NoiseChunk skyislands$lakes(ChunkAccess chunk, RandomState state, DensityFunctions.BeardifierOrMarker beardifier,
                                        NoiseGeneratorSettings settings, Aquifer.FluidPicker fluid, Blender blender) {
        // The router keeps each function behind a holder, so walk to it rather than testing the slot's type.
        SkyTerrain[] terrain = new SkyTerrain[1];
        state.router().fluidLevelFloodednessNoise().mapAll(df -> {
            if (df instanceof SkyTerrain t) terrain[0] = t;
            return df;
        });
        if (terrain[0] == null) return NoiseChunk.forChunk(chunk, state, beardifier, settings, fluid, blender);
        fluid = terrain[0].fluidPicker(fluid);
        SkyTerrain.ArchPicker picker = fluid instanceof SkyTerrain.ArchPicker ap ? ap : null;
        boolean fast = false;
        if (FastGen.enabled()) {
            if (FastGen.nothingToBlend(beardifier)) {
                beardifier = FastGen.ZERO_BEARD;
                FastGen.BEARD_SKIPPED.incrementAndGet();
                fast = picker != null && blender == Blender.empty();
            } else {
                FastGen.BEARD_KEPT.incrementAndGet();
            }
        }
        NoiseChunk nc = NoiseChunk.forChunk(chunk, state, beardifier, settings, fluid, blender);
        ((FastFill) nc).skyislands$setup(fast, picker);
        return nc;
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    @Redirect(method = "doFill", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;selectCellYZ(II)V"))
    private void skyislands$selectCell(NoiseChunk nc, int cellY, int cellZ) {
        ((FastFill) nc).skyislands$selectCell(cellY, cellZ);
    }

    @Redirect(method = "doFill", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;updateForY(ID)V"))
    private void skyislands$updateForY(NoiseChunk nc, int y, double d) {
        if (!((FastFill) nc).skyislands$air()) nc.updateForY(y, d);
    }

    @Redirect(method = "doFill", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;updateForX(ID)V"))
    private void skyislands$updateForX(NoiseChunk nc, int x, double d) {
        if (!((FastFill) nc).skyislands$air()) nc.updateForX(x, d);
    }

    @Redirect(method = "doFill", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;updateForZ(ID)V"))
    private void skyislands$updateForZ(NoiseChunk nc, int z, double d) {
        if (!((FastFill) nc).skyislands$air()) nc.updateForZ(z, d);
    }

    @Redirect(method = "doFill", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;getInterpolatedState()Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState skyislands$state(NoiseChunk nc) {
        FastFill f = (FastFill) nc;
        return f.skyislands$air() ? AIR : f.skyislands$state();
    }
}
