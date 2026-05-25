# Changelog

## 1.1.1

### Fixed

* Fixed a major item duplication bug where stackable snapshot items could be restored from the dream even when the matching death slot contained a different item, such as food, books, or other newly acquired loot.
* Tightened death-state matching so restored stackable items must match the saved item and components, while damageable gear still carries forward durability loss without accepting different enchanted or customized items as the same saved item.
* Applied the same strict death-state matching to Trinkets Updated slots.
* Fixed the Universal Graves interaction exposed by this bug: graves could correctly keep newly acquired items while Dreambound incorrectly restored unrelated old items from the snapshot.
* Made Universal Graves kept-item filtering component-aware so a restored stack does not consume the grave budget for a different stack variant of the same item.
* Fixed XP restoration using stale `totalExperience`, which could restore XP that had already been spent after the snapshot. Dreambound now calculates current XP from level and progress when saving snapshots and processing deaths.
