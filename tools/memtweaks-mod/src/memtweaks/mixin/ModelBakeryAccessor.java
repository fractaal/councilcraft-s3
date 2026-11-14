package memtweaks.mixin;

import java.util.Map;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ModelBakery.class)
public interface ModelBakeryAccessor {
    /** The bakery's own map of baked models. With ModernFix it holds only the ones baked up front. */
    @Accessor("bakedTopLevelModels")
    Map<ModelResourceLocation, BakedModel> memtweaks$bakedTopLevelModels();
}
