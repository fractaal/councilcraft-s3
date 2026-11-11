package skyislands.mixin;

import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import skyislands.FastGen;

/**
 * applyBiomeDecoration tries every feature of every biome found in the 3x3 chunks around it. In sky-island worlds, the
 * biomes are only collected from sections near land (FastGen.decorationSections), so cave biomes that exist only in
 * the open sky between islands don't send their features searching empty space.
 */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorMixin {
    @Redirect(method = "lambda$applyBiomeDecoration$6", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection[] skyislands$decorationSections(ChunkAccess chunk, WorldGenLevel level, Set<?> biomes, ChunkPos pos) {
        return FastGen.decorationSections(chunk, level);
    }
}
