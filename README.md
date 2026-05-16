# Dreambound

**A balanced alternative to Keep Inventory for Minecraft Fabric. Death is just a bad dream - respawn with your saved bed loadout intact, while the items you found during your run drop where you fell.**

Keep Inventory is too easy, and losing all your gear in a lava pit is too punishing. Dreambound bridges the gap by turning beds and respawn anchors into true inventory and experience checkpoints. 

*Note: Dreambound is a **server-side only** mod. Clients do not need to install it to play on a server running Dreambound.*

## How It Works

1. **The Checkpoint:** When you successfully sleep in a bed (or set a spawn with a Respawn Anchor), the mod takes a hidden "snapshot" of your exact inventory and XP.
2. **The Adventure:** Go out, mine, fight, and build. 
3. **The Nightmare:** If you die, your world state rolls back to the snapshot. You will wake up in your bed with the exact items and XP you had when you slept.
4. **The Leftovers:** Any new loot you picked up *after* sleeping is left behind at your death coordinate (or placed safely into a grave if you have Universal Graves installed).

### Anti-Exploit Mechanics
Dreambound uses strict intersection math to prevent duplication and free repairs:
* **No Dupes:** If you sleep with a Diamond Block, place it in the world, and die, you will wake up with 0 Diamond Blocks. You only keep what you didn't consume.
* **No Free Repairs:** If you sleep with a fresh sword and break it on some zombies, you will wake up with a damaged sword. Wear and tear is permanent!

## Features & Compatibility

* **Universal Graves Support:** Full native compatibility. Items you get to keep bypass the grave entirely, while forfeited items are neatly packed into your grave.
* **XP Rollback:** Restores your XP up to the amount you had in your snapshot.
* **Respawn Anchors:** Fully supports Nether spawn-setting logic. 

## Configuration

Dreambound is highly configurable. Upon first launch, a config file is generated where you can tweak the mechanics:

```properties
# Dreambound configuration
# Changes take effect after /dreambound reload or server restart.

# Save a dream snapshot after a successful bed sleep.
enableBedSleepSnapshots=true

# Save a dream snapshot when setting a new charged respawn anchor spawn in the Nether.
enableRespawnAnchorLogic=true

# Integrate with Universal Graves when it is installed.
# Restored dream items are kept out of the grave while forfeited items still go into it.
enableUniversalGravesCompat=true

# Restore XP up to the amount the player had in the dream snapshot.
# Extra XP is dropped at death.
restoreExperience=true

# Percentage of otherwise-restored dream XP lost on death.
# 0 keeps all restorable XP. 100 keeps none.
experienceLossPercent=0

# Clear the stored dream snapshot after it is used on respawn.
clearSnapshotOnRespawn=false

# Send a styled message after a bed or respawn-anchor snapshot is saved.
notifySnapshotSaved=true

# Send a styled message after dream items are restored on respawn.
notifyRespawnRestore=true

# Log detailed Universal Graves compatibility decisions.
# Useful when debugging grave compass or item filtering behavior.
debugUniversalGravesCompat=false
```
## Installation

1. Download the [Fabric Loader](https://fabricmc.net/).
2. Place the `Dreambound` `.jar` file into your server's `mods` folder.

**Required Dependencies:** 
* [Fabric API](https://modrinth.com/mod/fabric-api)
* [Cardinal Components API](https://modrinth.com/mod/cardinal-components-api)