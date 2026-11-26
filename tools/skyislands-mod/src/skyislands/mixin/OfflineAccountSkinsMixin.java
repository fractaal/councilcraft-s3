package skyislands.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Players on offline-account clients (Prism's offline accounts) saw every other player in the default skin, though the
 * server had given them real ones (SkinRestorer sets valid Mojang textures and signatures on the profiles).
 * <p>
 * Prism launches an offline account with the Mojang API hosts pointed at a dead address, so authlib can't fetch Mojang's
 * public keys and calls every textures signature invalid. Vanilla's rule (PlayerInfo.createSkinLookup) is to show the
 * loaded skin for yourself, and for anyone else only if it is "secure" (signature verified); otherwise the default skin.
 * So nobody else's skin ever showed.
 * <p>
 * This makes the lookup use the loaded skin whether or not its signature could be verified. Texture downloads still
 * come only from the domains the game allows (textures.minecraft.net), so what is trusted is just this server's word on
 * which Mojang skin a player wears, which it sets itself. With a working online account nothing changes: the signature
 * verifies and the skin was already shown.
 * <p>
 * Client only. {@code flag} is the lookup's only boolean local, "this isn't me": the lambda it returns captures it after
 * the store, so changing it here is enough.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.PlayerInfo")
abstract class OfflineAccountSkinsMixin {
    @ModifyVariable(method = "createSkinLookup", at = @At("STORE"), ordinal = 0)
    private static boolean skyislands$showUnverifiedSkins(boolean notLocalPlayer) {
        return false;
    }
}
