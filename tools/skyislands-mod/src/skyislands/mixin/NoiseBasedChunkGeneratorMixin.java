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
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import skyislands.BlendReach;
import skyislands.FastFill;
import skyislands.FastGen;
import skyislands.SkyTerrain;

/**
 * Vanilla has one sea level for the whole world. Worlds using SkyTerrain get per-lake water levels instead.
 *
 * In those worlds the noise fill also takes shortcuts (FastGen), all exact. A chunk with no structure to blend gets a
 * zero blending density instead of the Beardifier. A chunk near structures keeps its Beardifier, but cells outside the
 * boxes its blending can reach (BlendReach) skip it. And a cell whose corners are all below zero, with no blending and no
 * lake water in it, is left as air without working out each of its blocks.
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
        int[] reach = null;
        if (FastGen.enabled()) {
            reach = BlendReach.of(beardifier, chunk.getPos());
            if (reach == null) {
                FastGen.BEARD_KEPT.incrementAndGet();
            } else if (reach.length == 0) {
                beardifier = FastGen.ZERO_BEARD;
                FastGen.BEARD_SKIPPED.incrementAndGet();
            } else {
                FastGen.BEARD_BOUNDED.incrementAndGet();
            }
        }
        NoiseChunk nc = NoiseChunk.forChunk(chunk, state, beardifier, settings, fluid, blender);
        ((FastFill) nc).skyislands$setup(reach != null && picker != null && blender == Blender.empty(), picker, reach);
        return nc;
    }

    /**
     * Height lookups (getBaseHeight and getBaseColumn, through iterateNoiseColumn) see lake water too. Without it, mods
     * that plan from heights, such as Streams Reflowing's rivers, see each lake as a dry hole in its island.
     */
    @ModifyArg(method = "iterateNoiseColumn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;<init>(ILnet/minecraft/world/level/levelgen/RandomState;IILnet/minecraft/world/level/levelgen/NoiseSettings;Lnet/minecraft/world/level/levelgen/DensityFunctions$BeardifierOrMarker;Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;Lnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;Lnet/minecraft/world/level/levelgen/blending/Blender;)V"), index = 7)
    private Aquifer.FluidPicker skyislands$lakeHeights(Aquifer.FluidPicker fluid, @Local(argsOnly = true) RandomState state) {
        SkyTerrain terrain = FastGen.terrainOf(state);
        return terrain == null ? fluid : terrain.columnFluidPicker(fluid);
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

    /**
     * Lake water at the surface gets the update vanilla gives aquifer water (marked for post-processing, then ticked when
     * the chunk is finished), so where it ends up beside air it flows instead of standing as a wall.
     */
    @Redirect(method = "doFill", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/Aquifer;shouldScheduleFluidUpdate()Z"))
    private boolean skyislands$lakeFlow(Aquifer aquifer, @Local NoiseChunk nc) {
        return aquifer.shouldScheduleFluidUpdate() || ((FastFill) nc).skyislands$lakeSurface();
    }

    @Redirect(method = "doFill", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;getInterpolatedState()Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState skyislands$state(NoiseChunk nc) {
        FastFill f = (FastFill) nc;
        return f.skyislands$air() ? AIR : f.skyislands$state();
    }
}
