package skyislands.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Functional Storage 1.5.8's Armory Cabinet runs its search on the server too (each keystroke is a button click), and
 * there it matches every stored item whose name doesn't match against the item's tooltip (ArmoryCabinetMenu.matches).
 * Some mods build tooltips with code that only exists in a game: Royal Variations 2.0's knight armour asks whether
 * Shift is held, a client class, and Relics 0.12.9 called a method only its client side had. On the server that throws
 * on every keystroke, so the server's filter never updates and the log fills with errors (about 1,500 in 10 minutes on
 * 2026-11-23); Relics' Error, which nothing caught, crashed the server that morning.
 *
 * When an item's tooltip can't be built here, search it by name alone (matches has already checked the name) and
 * remember its item, so it isn't tried again on the next keystroke. Items whose tooltips work are searched as before.
 * Optional: without Functional Storage this mixin doesn't apply.
 */
@Pseudo
@Mixin(targets = "com.buuz135.functionalstorage.inventory.ArmoryCabinetMenu")
abstract class ArmoryCabinetTooltipMixin {
    @Unique
    private static final Logger skyislands$LOG = LogUtils.getLogger();
    /** Items whose tooltip has thrown here. */
    @Unique
    private static final Set<Item> skyislands$nameOnly = ConcurrentHashMap.newKeySet();

    @WrapOperation(method = "matches", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraft/world/item/ItemStack;getTooltipLines(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List;"))
    private List<Component> skyislands$nameOnlyIfTooltipFails(ItemStack stack, Item.TooltipContext context, Player player, TooltipFlag flag,
                                                               Operation<List<Component>> getTooltipLines) {
        if (skyislands$nameOnly.contains(stack.getItem())) return List.of();
        try {
            return getTooltipLines.call(stack, context, player, flag);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            skyislands$nameOnly.add(stack.getItem());
            skyislands$LOG.warn("[skyislands] Armory Cabinet search: {}'s tooltip can't be built here ({}), so it is searched by name only",
                    BuiltInRegistries.ITEM.getKey(stack.getItem()), t.toString());
            return List.of();
        }
    }
}
