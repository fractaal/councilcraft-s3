package skyislands.mixin;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import skyislands.StructureFootings;

/** In sky-island worlds, structures hanging in the open sky lose their bottomless supports (StructureFootings). */
@Mixin(StructureStart.class)
abstract class StructureStartMixin {
    @Inject(method = "placeInChunk", at = @At("RETURN"))
    private void skyislands$footings(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
                                     BoundingBox box, ChunkPos chunkPos, CallbackInfo ci) {
        StructureFootings.apply((StructureStart) (Object) this, level, generator, box);
    }
}
