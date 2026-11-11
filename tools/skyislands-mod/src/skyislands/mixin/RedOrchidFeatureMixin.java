package skyislands.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import skyislands.FastGen;

/**
 * Excessive Utilities' red orchid feature (1.11.0) reads every block of its chunk from y=-64 to 16 looking for redstone
 * ore with air above, about 20,000 reads, and returns false without drawing a random number if it finds none. Where all
 * of that band is air, which is nearly everywhere in a sky-island world, it can't find any, so it is skipped.
 */
@Pseudo
@Mixin(targets = "dev.aaronhowser.mods.excessive_utilities.feature.RedOrchidFeature")
abstract class RedOrchidFeatureMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true, remap = false)
    private void skyislands$emptyBand(FeaturePlaceContext<?> ctx, CallbackInfoReturnable<Boolean> cir) {
        if (!FastGen.enabled()) return;
        WorldGenLevel level = ctx.level();
        int cx = SectionPos.blockToSectionCoord(ctx.origin().getX()), cz = SectionPos.blockToSectionCoord(ctx.origin().getZ());
        if (!level.hasChunk(cx, cz)) return;
        ChunkAccess chunk = level.getChunk(cx, cz);
        int lo = Math.max(level.getMinBuildHeight(), -64), hi = Math.min(level.getMaxBuildHeight() - 2, 16);
        for (int s = SectionPos.blockToSectionCoord(lo); s <= SectionPos.blockToSectionCoord(hi); s++) {
            int i = chunk.getSectionIndexFromSectionY(s);
            if (i >= 0 && i < chunk.getSectionsCount() && !chunk.getSection(i).hasOnlyAir()) return;
        }
        cir.setReturnValue(false);
    }
}
