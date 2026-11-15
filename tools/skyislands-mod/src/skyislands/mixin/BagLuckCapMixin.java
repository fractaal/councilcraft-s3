package skyislands.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import skyislands.LootRules;

/**
 * Mob Loot Bags: the Luck attribute a bag's tier roll reads from the player stops at skyislands.LootRules.BAG_LUCK_CAP.
 * Optional: without Mob Loot Bags this mixin doesn't apply.
 */
@Pseudo
@Mixin(targets = "tn.naizo.moblootbags.common.drop.DropEngine")
abstract class BagLuckCapMixin {
    @ModifyExpressionValue(method = "luck", remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getLuck()F", remap = false))
    private static float skyislands$capLuck(float luck) {
        return LootRules.capBagLuck(luck);
    }
}
