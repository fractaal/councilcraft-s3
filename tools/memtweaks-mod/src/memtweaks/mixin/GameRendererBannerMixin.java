package memtweaks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import memtweaks.StartupBanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws {@link StartupBanner} on top of the loading overlay, whoever drew the overlay itself. */
@Mixin(GameRenderer.class)
public abstract class GameRendererBannerMixin {
    @Shadow @Final Minecraft minecraft;

    @WrapOperation(method = "render", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Overlay;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
    private void memtweaks$bannerOverLoadingScreen(Overlay overlay, GuiGraphics graphics, int mouseX, int mouseY, float partialTick, Operation<Void> original) {
        original.call(overlay, graphics, mouseX, mouseY, partialTick);
        if (overlay instanceof LoadingOverlay && StartupBanner.showing()) StartupBanner.render(graphics);
    }

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void memtweaks$noticeFirstLoadOver(CallbackInfo ci) {
        if (this.minecraft.getOverlay() == null && this.minecraft.screen != null) StartupBanner.firstLoadFinished();
    }
}
