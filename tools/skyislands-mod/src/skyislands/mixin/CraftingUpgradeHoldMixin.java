package skyislands.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import skyislands.CraftingResultHold;

/**
 * Sophisticated Core's Crafting Upgrade (in backpacks and storage blocks) works the grid's result out again after every
 * change to the grid, and when its last recipe no longer matches, that means asking for every crafting recipe that
 * matches the grid: a scan of all 22,000 of the pack's crafting recipes, which can't stop at the first match. Taking a
 * craft changes the grid slot by slot, and a grid caught halfway matches nothing (or something else). The worst case is
 * an item that comes back into its slot, such as Mystical Agriculture's infusion crystal: taking it out leaves a half
 * grid, one full scan, and putting the damaged crystal back is a second, because CraftingItemHandler.setItem works the
 * result out before it forgets its cached list of the grid's items, so it sees the same half grid. A shift-click repeats
 * the craft up to 64 times in one go: 128 full scans per click (lab), and live froze for 10 to 35 s on 2026-11-23.
 *
 * While the result slot takes a craft (skyislands.mixin.CraftingUpgradeTakeMixin), hold those updates back and work the
 * result out once when the take is done, from the grid as it is then. What the take does to the grid, the storage and
 * the player is unchanged, and so is the result shown after it. The one difference: a grid caught halfway no longer
 * replaces the recipe variant the player picked with the half grid's first match.
 * Optional: without Sophisticated Core this mixin doesn't apply.
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.crafting.CraftingUpgradeContainer")
abstract class CraftingUpgradeHoldMixin implements CraftingResultHold {
    @Unique
    private int skyislands$holds;
    /** The grid of an update held back during the take, or null when none was. */
    @Unique
    private Container skyislands$heldGrid;

    @Shadow(remap = false)
    private void onCraftMatrixChanged(Container grid) {}

    @Override
    public void skyislands$holdResult() {
        skyislands$holds++;
    }

    @Override
    public void skyislands$releaseResult() {
        if (--skyislands$holds > 0 || skyislands$heldGrid == null) return;
        Container grid = skyislands$heldGrid;
        skyislands$heldGrid = null;
        onCraftMatrixChanged(grid);
    }

    @Inject(method = "updateCraftingResult", at = @At("HEAD"), cancellable = true, remap = false)
    private void skyislands$holdWhileTaking(Level level, Player player, CraftingContainer grid, ResultContainer result,
                                           ResultSlot resultSlot, CallbackInfo ci) {
        if (skyislands$holds == 0) return;
        skyislands$heldGrid = grid;
        ci.cancel();
    }
}
