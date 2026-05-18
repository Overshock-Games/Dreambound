package com.dreambound.component;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStackTemplate;
import org.ladysnake.cca.api.v3.component.Component;

import java.util.List;
import java.util.Map;

public interface DreamStateComponent extends Component {

    /** Returns the snapshot slots in order: 36 main, 4 armor, 1 offhand. Null entries are empty slots. */
    List<ItemStackTemplate> getSnapshot();

    boolean hasSnapshot();

    /** Replaces any existing snapshot with a fresh copy of the player's current inventory. */
    void captureInventory(ServerPlayer player);

    void clearSnapshot();

    /**
     * Transient (non-NBT) intermediate state set during death processing.
     * Indexed identically to the snapshot: 36 main, 4 armor, 1 offhand.
     * Null entries represent slots that are empty or were forfeited.
     * Returns null if no pending restoration has been calculated yet.
     */
    List<ItemStackTemplate> getPendingRespawnItems();

    void setPendingRespawnItems(List<ItemStackTemplate> items);

    int getSnapshotXp();

    int getPendingXp();

    void setPendingXp(int xp);

    /** Transient flag: when true, clearSnapshot() is called after the pending respawn restore completes. */
    boolean isClearSnapshotAfterRestore();

    void setClearSnapshotAfterRestore(boolean clear);

    // --- Trinket snapshot (persisted; null = no snapshot) ---

    /** Returns the trinket snapshot as a map of serialized slot reference → item template, or null if none. */
    Map<String, ItemStackTemplate> getTrinketSnapshot();

    void setTrinketSnapshot(Map<String, ItemStackTemplate> snapshot);

    // --- Pending trinket items (transient; null until death processing sets them) ---

    Map<String, ItemStackTemplate> getPendingTrinketItems();

    void setPendingTrinketItems(Map<String, ItemStackTemplate> items);
}
