package memtweaks;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;

/**
 * Some mods wrap their models by calling replaceAll on the model registry in NeoForge's ModifyBakingResult
 * event. With ModernFix's dynamic resources that call walks every model location in the game (2.5 million
 * here), then bakes and permanently keeps each model the mod wants to wrap, all during startup.
 *
 * This keeps the mod's function instead and applies it when ModernFix bakes a model on demand, through
 * ModernFix's own integration hook. Models that already exist when replaceAll is called (ones baked up front,
 * ones another mod put into the registry, and ones baked earlier in the same event) are still replaced on
 * the spot, the way ModernFix does it, because they never pass through that hook again.
 */
public final class LazyModelReplace {
    private static final List<BiFunction<? super ModelResourceLocation, ? super BakedModel, ? extends BakedModel>> deferred = new CopyOnWriteArrayList<>();
    private static final Set<ModelResourceLocation> bakedDuringEvent = ConcurrentHashMap.newKeySet();
    private static volatile boolean eventActive;
    private static volatile Map<ModelResourceLocation, BakedModel> bakedUpFront;
    private static boolean hooked, hookFailed, warned;
    private static int deferredCalls, replacedNow;

    private LazyModelReplace() {}

    /** Start of the ModifyBakingResult event. {@code upFront} is the bakery's map of models baked before it. */
    public static void beginEvent(Map<ModelResourceLocation, BakedModel> upFront) {
        deferred.clear();
        bakedDuringEvent.clear();
        deferredCalls = 0;
        replacedNow = 0;
        bakedUpFront = upFront;
        eventActive = true;
    }

    public static void endEvent() {
        eventActive = false;
        bakedUpFront = null;
        bakedDuringEvent.clear();
        if (deferredCalls > 0) MemTweaks.logLazyModelReplace(deferredCalls, replacedNow);
    }

    /**
     * Called in place of ModernFix's replaceAll loop. Returns false to let ModernFix run its own loop.
     * @param registry    the registry the mod was given
     * @param backing     the map behind it, which holds the models other mods have put there
     * @param visible     the model locations the mod is allowed to see
     */
    public static boolean defer(Map<ModelResourceLocation, BakedModel> registry, Map<ModelResourceLocation, BakedModel> backing,
                                Set<ModelResourceLocation> visible,
                                BiFunction<? super ModelResourceLocation, ? super BakedModel, ? extends BakedModel> function) {
        Map<ModelResourceLocation, BakedModel> upFront = bakedUpFront;
        if (!eventActive || upFront == null || !MemTweaks.lazyModelReplaceAll()) return false;
        // A mod ModernFix restricts to some namespaces gets a filtered view. Leave those to ModernFix.
        if (!visible.getClass().getName().startsWith("it.unimi.dsi.fastutil.")) return false;
        Map<ModelResourceLocation, BakedModel> overrides = overridesOf(backing);
        if (overrides == null || !hook()) return false;

        // Each existing model once: the ones mods have put, then the ones baked up front, then the ones baked in this event.
        for (ModelResourceLocation location : List.copyOf(overrides.keySet())) replaceNow(registry, location, function);
        for (ModelResourceLocation location : List.copyOf(upFront.keySet())) {
            if (!overrides.containsKey(location)) replaceNow(registry, location, function);
        }
        for (ModelResourceLocation location : bakedDuringEvent) {
            if (!overrides.containsKey(location) && !upFront.containsKey(location)) replaceNow(registry, location, function);
        }
        deferred.add(function);
        deferredCalls++;
        return true;
    }

    private static void replaceNow(Map<ModelResourceLocation, BakedModel> registry, ModelResourceLocation location,
                                   BiFunction<? super ModelResourceLocation, ? super BakedModel, ? extends BakedModel> function) {
        // Same probe as ModernFix: a function that returns null for a null model doesn't care about this one.
        boolean wanted;
        try {
            wanted = function.apply(location, null) != null;
        } catch (Throwable e) {
            wanted = true;
        }
        if (!wanted) return;
        BakedModel model = registry.get(location);
        if (model == null) return;
        BakedModel replacement = function.apply(location, model);
        if (replacement != null && replacement != model) {
            registry.put(location, replacement);
            replacedNow++;
        }
    }

    /** ModernFix calls this, through the integration below, each time it bakes a model on demand. */
    static BakedModel onBaked(ModelResourceLocation location, BakedModel model) {
        if (eventActive) bakedDuringEvent.add(location);
        if (model == null) return null;
        for (BiFunction<? super ModelResourceLocation, ? super BakedModel, ? extends BakedModel> function : deferred) {
            try {
                BakedModel replacement = function.apply(location, model);
                if (replacement != null) model = replacement;
            } catch (RuntimeException e) {
                if (!warned) {
                    warned = true;
                    MemTweaks.warnLazyModelReplace("a mod's model replacement failed for " + location, e);
                }
            }
        }
        return model;
    }

    @SuppressWarnings("unchecked")
    private static Map<ModelResourceLocation, BakedModel> overridesOf(Map<ModelResourceLocation, BakedModel> backing) {
        try {
            Field f = backing.getClass().getDeclaredField("overrideMap");
            f.setAccessible(true);
            return (Map<ModelResourceLocation, BakedModel>) f.get(backing);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Registers with ModernFix's list of client integrations, by name so ModernFix isn't needed to compile. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static synchronized boolean hook() {
        if (hooked) return true;
        if (hookFailed) return false;
        try {
            Class<?> api = Class.forName("org.embeddedt.modernfix.api.entrypoint.ModernFixClientIntegration");
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "onBakedModelLoad" -> onBaked((ModelResourceLocation) args[0], (BakedModel) args[2]);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "memtweaks lazy model replacement";
                default -> null;
            };
            Object integration = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, handler);
            List list = (List) Class.forName("org.embeddedt.modernfix.ModernFixClient").getField("CLIENT_INTEGRATIONS").get(null);
            list.add(integration);
            hooked = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            hookFailed = true;
            MemTweaks.warnLazyModelReplace("could not register with ModernFix, leaving replaceAll to it", e);
            return false;
        }
    }
}
