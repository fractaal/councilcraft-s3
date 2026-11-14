package memtweaks.mixin.voxy;

import memtweaks.MemTweaks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Voxy's WorldEngine constructor picks the section cache size and passes it straight to its
 * ActiveSectionTracker. This replaces that one argument with the configured size.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.WorldEngine", remap = false)
public abstract class VoxyWorldEngineMixin {
    @ModifyArg(
            method = "<init>(Lme/cortex/voxy/common/config/section/SectionStorage;Lme/cortex/voxy/commonImpl/VoxyInstance;)V",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lme/cortex/voxy/common/world/ActiveSectionTracker;<init>(ILme/cortex/voxy/common/world/ActiveSectionTracker$SectionLoader;ILme/cortex/voxy/common/world/WorldEngine;)V"),
            index = 2, remap = false, require = 0)
    private static int memtweaks$sectionCacheSize(int voxyChoice) {
        return MemTweaks.voxySectionCacheSize(voxyChoice);
    }
}
