package skyislands.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import skyislands.WhaleBurst;

/**
 * Notes which whale is dying while Sky Whales builds its corpse, so skyislands.mixin.WhaleCorpseBurstMixin knows whose
 * loot it is. See skyislands.WhaleBurst. Without both mixins the corpse is built as before.
 * Optional: without Sky Whales this mixin doesn't apply.
 */
@Pseudo
@Mixin(targets = "dev.dacite.skywhales.entity.SkyWhale")
abstract class SkyWhaleDeathMixin {
    @WrapOperation(method = "tickDeath", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Ldev/dacite/skywhales/corpse/WhaleCorpseSpawner;spawnCorpse(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;F)V"))
    private void skyislands$noteDyingWhale(ServerLevel level, Vec3 center, float deathYaw, Operation<Void> spawnCorpse) {
        WhaleBurst.dying = (LivingEntity) (Object) this;
        try {
            spawnCorpse.call(level, center, deathYaw);
        } finally {
            WhaleBurst.dying = null;
        }
    }
}
