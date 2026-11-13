// Recipes the pack adds on top of its mods. Server-side: clients get them on join, and /reload picks up edits.
ServerEvents.recipes(event => {
  // Create's millstone has no quartz recipe, though quartz dust is used across the tech mods (asked for by TechKeeper).
  event.custom({
    type: 'create:milling',
    ingredients: [{ item: 'minecraft:quartz' }],
    processing_time: 200,
    results: [{ id: 'modern_industrialization:quartz_dust' }]
  }).id('aero:milling/quartz_dust')
})
