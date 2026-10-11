### Knockback trainer plugin

contains functionality for a knockback trainer

MC version: 1.8.8

Server type: Spigot

### !Necessary dependencies!

You will need a version of Citizens compatible with Minecraft 1.8.8 for the mod to work!
Ill provide a link to the correct build on their jenkins [here](https://ci.citizensnpcs.co/job/Citizens2/2803/) (Citizens-2.0.30-b2803.jar).

>NOTE: The player always gets safely teleported to the given coordinates (config) if they fall below Y = 0. This only happens in survial mode and is intended behaviour. Also the mod disables falldamage entirely to avoid the player dying.

---
#### Mandatory config:

go into
Search in Plugins/KnockbackReducer/
and configure the coordinates:

- where player gets teleported to
- where reducer spawns

- Furthermore build your own arena!

---
#### Optional runtime config:

The knockback and attack interval are configurable:

either via commands

(type /reducer for more info)

or via config:

Search in Plugins/KnockbackReducer/
