execute as @e[type=minecraft:wandering_trader,tag=aero_hat_trader] run data modify entity @s Offers.Recipes[].uses set value 0
scoreboard players operation #last aero_hat_day = #now aero_hat_day
