package skyislands.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import skyislands.LootRules;

/**
 * FTB Quests loot crates: choose the passive, monster or boss weight by what the mob is (skyislands.LootRules), not by
 * Entity.canUsePortal, which calls every dying mob a boss. Optional: without FTB Quests this mixin doesn't apply.
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.quest.loot.EntityWeight")
abstract class FtbEntityWeightMixin {
    @Shadow(remap = false) public int passive;
    @Shadow(remap = false) public int monster;
    @Shadow(remap = false) public int boss;

    @Inject(method = "getWeight", at = @At("HEAD"), cancellable = true, remap = false)
    private void skyislands$weightByMobKind(Entity entity, CallbackInfoReturnable<Integer> cir) {
        LootRules.logOnce(2, "[skyislands] FTB Quests crate weights are chosen by mob kind");
        // No switch on the enum here: javac would add a helper class to the mixin package, which can't be loaded at runtime.
        LootRules.WeightKind kind = LootRules.weightKind(entity);
        cir.setReturnValue(kind == LootRules.WeightKind.BOSS ? boss : kind == LootRules.WeightKind.MONSTER ? monster : passive);
    }
}
