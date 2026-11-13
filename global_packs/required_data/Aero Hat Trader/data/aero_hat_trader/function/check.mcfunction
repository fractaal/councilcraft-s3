# Once a minute: if the in-game day has changed since the last reset and the trader is loaded, reset its trade limits.
# Checking each minute instead of resetting at a fixed time means an unloaded trader still gets its reset when someone visits.
execute store result score #now aero_hat_day run time query day
execute unless score #now aero_hat_day = #last aero_hat_day if entity @e[type=minecraft:wandering_trader,tag=aero_hat_trader,limit=1] run function aero_hat_trader:restock
schedule function aero_hat_trader:check 1200t replace
