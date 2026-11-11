package skyislands.mixin;

import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import skyislands.StructureCache;

/** Structure templates upgrade from old Minecraft versions through skyislands.StructureCache. */
@Mixin(StructureTemplateManager.class)
abstract class StructureTemplateManagerMixin {
    @Redirect(method = "readStructure(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/datafix/DataFixTypes;updateToCurrentVersion(Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/nbt/CompoundTag;I)Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag skyislands$cachedUpgrade(DataFixTypes type, DataFixer fixer, CompoundTag tag, int version) {
        return StructureCache.upgrade(type, fixer, tag, version);
    }
}
