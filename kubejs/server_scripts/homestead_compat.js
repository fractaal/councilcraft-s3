// Ported from the Homestead modpack's CraftTweaker script Compat/flour.zs.
//
// Farm & Charm and Create each add their own wheat flour, and each mod's recipes only accept its own: Create's dough
// asks for #c:flours/wheat (Create's flour only), and Farm & Charm's recipes ask for farm_and_charm:flour or #c:flour
// (no Create flour). Let either flour be crafted into the other. No other one-item crafting recipe takes either flour.
//
// Homestead's other fix, Compat/farmland_fix.zs, is not needed here: Farm & Charm 1.1.29 lets its crops be planted on
// any FarmBlock, and nothing in this pack reads the farm_and_charm:farmland tag that script filled.
ServerEvents.recipes(event => {
  event.shapeless('create:wheat_flour', ['farm_and_charm:flour']).id('exosphere:create_wheat_flour_from_farm_and_charm_flour')
  event.shapeless('farm_and_charm:flour', ['create:wheat_flour']).id('exosphere:farm_and_charm_flour_from_create_wheat_flour')
})
