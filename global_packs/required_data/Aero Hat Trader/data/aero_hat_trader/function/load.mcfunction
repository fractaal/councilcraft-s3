# The Hat Bag Trader is a wandering trader tagged aero_hat_trader (a wandering trader, so Easy Villagers can't pick it up into a trader block that restocks it).
scoreboard objectives add aero_hat_day dummy
schedule function aero_hat_trader:check 1200t replace
