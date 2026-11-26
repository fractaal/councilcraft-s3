package skyislands.mixin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create Aeronautics 1.3.2 draws a heat shimmer inside hot-air balloons: at the "after solid blocks" stage it draws the
 * balloon's inside faces into a buffer of its own and soft-light blends that onto Minecraft's main framebuffer. With an
 * Iris shader pack on, that goes wrong in two ways and leaves a smeary ghost of the balloon in the sky (seen 2026-11-26):
 * <ul>
 * <li>Iris draws its shadow map by calling the same "draw solid blocks" step with the sun's camera, and Sodium fires the
 * stage event from inside it, so the effect runs again with the shadow pass's matrices.</li>
 * <li>Veil's Iris compatibility binds Iris's scene colour and depth buffers whenever one of its shaders is applied, so the
 * effect's draw lands in the scene instead of its own buffer. The balloon as seen from the sun is written into the
 * scene's depth, and a shader pack that keeps its geometry buffers between frames (Photon) then lights those pixels
 * from whatever was last drawn there.</li>
 * </ul>
 * Under a shader pack none of the effect can show anyway: its colour is overwritten by the pack's lighting pass and its
 * blend goes to the main framebuffer, which the pack's final pass overwrites. So while a shader pack is in use, skip it
 * (and free its buffer). Without shaders it runs as before. Optional: without Create Aeronautics this doesn't apply.
 */
@Pseudo
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.effect.ClientBalloonEffectRenderer")
abstract class BalloonHeatEffectMixin {
    /** {@code IrisApi.getInstance().isShaderPackInUse()}, or null without Iris. Looked up by name: skyislands is
     * compiled against the server's libraries, which have no Iris. */
    private static final MethodHandle skyislands$shaderPackInUse = skyislands$findIris();

    @Shadow(remap = false)
    private static void freeFbo() {
    }

    @Inject(method = "onRenderLevelStage", at = @At("HEAD"), cancellable = true, remap = false)
    private static void skyislands$skipUnderShaderPack(CallbackInfo ci) {
        if (skyislands$shaderPackInUse == null) return;
        boolean inUse;
        try {
            inUse = (boolean) skyislands$shaderPackInUse.invokeExact();
        } catch (Throwable t) {
            return;
        }
        if (inUse) {
            freeFbo();
            ci.cancel();
        }
    }

    private static MethodHandle skyislands$findIris() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle getInstance = lookup.findStatic(api, "getInstance", MethodType.methodType(api));
            MethodHandle inUse = lookup.findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class));
            return MethodHandles.foldArguments(inUse, getInstance);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
