package com.dreambound.component;

import com.dreambound.DreamboundMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DreamStateComponentImpl implements DreamStateComponent {

    // Slot layout: [0..35] main inventory, [36..39] armor, [40] offhand
    // Sizes are derived from Inventory constants so they stay in sync.
    private static final int MAIN_SIZE    = Inventory.INVENTORY_SIZE; // 36
    private static final int ARMOR_SIZE   = 4;
    private static final int TOTAL_SIZE   = MAIN_SIZE + ARMOR_SIZE + 1; // 41

    // null means no snapshot has been taken yet (distinct from a snapshot of an empty inventory)
    private List<ItemStackTemplate> snapshot = null;

    // Transient; never written to or read from NBT.
    // Populated by the death-drop mixin; consumed by the respawn restoration logic.
    private List<ItemStackTemplate> pendingRespawnItems = null;
    private int pendingXp = 0;

    private int snapshotXp = 0;
    private boolean clearSnapshotAfterRestore = false;

    // null = no trinket snapshot
    private Map<String, ItemStackTemplate> trinketSnapshot = null;
    // transient; not written to NBT
    private Map<String, ItemStackTemplate> pendingTrinketItems = null;

    private record TrinketStackEntry(String slot, ItemStack stack) {
        static final Codec<TrinketStackEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("Slot").forGetter(TrinketStackEntry::slot),
            ItemStack.CODEC.fieldOf("Stack").forGetter(TrinketStackEntry::stack)
        ).apply(i, TrinketStackEntry::new));
    }

    // -------------------------------------------------------------------------
    // DreamStateComponent - sleep snapshot
    // -------------------------------------------------------------------------

    @Override
    public List<ItemStackTemplate> getSnapshot() {
        return snapshot != null ? Collections.unmodifiableList(snapshot) : Collections.emptyList();
    }

    @Override
    public boolean hasSnapshot() {
        return snapshot != null;
    }

    @Override
    public void captureInventory(ServerPlayer player) {
        Inventory inv = player.getInventory();
        List<ItemStackTemplate> captured = new ArrayList<>(TOTAL_SIZE);

        // Main inventory - 36 slots (indices 0-35)
        for (ItemStack stack : inv.getNonEquipmentItems()) {
            captured.add(stack.isEmpty() ? null : ItemStackTemplate.fromNonEmptyStack(stack));
        }

        // Armor - 4 slots (indices 36-39: feet, legs, chest, head)
        for (int i = 0; i < ARMOR_SIZE; i++) {
            ItemStack stack = inv.getItem(MAIN_SIZE + i);
            captured.add(stack.isEmpty() ? null : ItemStackTemplate.fromNonEmptyStack(stack));
        }

        // Offhand - 1 slot (index 40)
        ItemStack offhand = inv.getItem(Inventory.SLOT_OFFHAND);
        captured.add(offhand.isEmpty() ? null : ItemStackTemplate.fromNonEmptyStack(offhand));

        this.snapshot = captured;
        this.snapshotXp = player.totalExperience;
        DreamboundMod.LOGGER.debug("Dreambound: captured inventory snapshot for {}", player.getName().getString());
    }

    @Override
    public void clearSnapshot() {
        this.snapshot = null;
        this.trinketSnapshot = null;
    }

    // -------------------------------------------------------------------------
    // DreamStateComponent - pending respawn items (transient)
    // -------------------------------------------------------------------------

    @Override
    public List<ItemStackTemplate> getPendingRespawnItems() {
        return pendingRespawnItems;
    }

    @Override
    public void setPendingRespawnItems(List<ItemStackTemplate> items) {
        this.pendingRespawnItems = items;
    }

    @Override public int getSnapshotXp() { return snapshotXp; }
    @Override public int getPendingXp() { return pendingXp; }
    @Override public void setPendingXp(int xp) { this.pendingXp = xp; }
    @Override public boolean isClearSnapshotAfterRestore() { return clearSnapshotAfterRestore; }
    @Override public void setClearSnapshotAfterRestore(boolean clear) { this.clearSnapshotAfterRestore = clear; }

    @Override public Map<String, ItemStackTemplate> getTrinketSnapshot() { return trinketSnapshot; }
    @Override public void setTrinketSnapshot(Map<String, ItemStackTemplate> snapshot) { this.trinketSnapshot = snapshot; }
    @Override public Map<String, ItemStackTemplate> getPendingTrinketItems() { return pendingTrinketItems; }
    @Override public void setPendingTrinketItems(Map<String, ItemStackTemplate> items) { this.pendingTrinketItems = items; }

    // -------------------------------------------------------------------------
    // CardinalComponent - data serialization (pendingRespawnItems intentionally excluded)
    // -------------------------------------------------------------------------

    @Override
    public void readData(ValueInput input) {
        // "HasSnapshot" distinguishes "no snapshot yet" from "snapshot of an empty inventory"
        if (!input.getBooleanOr("HasSnapshot", false)) {
            snapshot = null;
            trinketSnapshot = null;
            return;
        }

        snapshotXp = input.getIntOr("SnapshotXp", 0);
        snapshot = new ArrayList<>(TOTAL_SIZE);
        for (int i = 0; i < TOTAL_SIZE; i++) {
            snapshot.add(null);
        }

        for (ItemStackWithSlot entry : input.listOrEmpty("Snapshot", ItemStackWithSlot.CODEC)) {
            int slot = entry.slot();
            ItemStack stack = entry.stack();
            if (slot >= 0 && slot < TOTAL_SIZE && !stack.isEmpty()) {
                snapshot.set(slot, ItemStackTemplate.fromNonEmptyStack(stack));
            }
        }

        trinketSnapshot = new HashMap<>();
        for (TrinketStackEntry entry : input.listOrEmpty("TrinketSnapshot", TrinketStackEntry.CODEC)) {
            if (entry.slot() != null && !entry.stack().isEmpty()) {
                trinketSnapshot.put(entry.slot(), ItemStackTemplate.fromNonEmptyStack(entry.stack()));
            }
        }
        if (trinketSnapshot.isEmpty()) trinketSnapshot = null;
    }

    @Override
    public void writeData(ValueOutput output) {
        output.putBoolean("HasSnapshot", snapshot != null);
        if (snapshot == null) return;
        output.putInt("SnapshotXp", snapshotXp);

        ValueOutput.TypedOutputList<ItemStackWithSlot> list = output.list("Snapshot", ItemStackWithSlot.CODEC);
        for (int slot = 0; slot < snapshot.size(); slot++) {
            ItemStackTemplate template = snapshot.get(slot);
            if (template != null) {
                list.add(new ItemStackWithSlot(slot, template.create()));
            }
        }

        if (trinketSnapshot != null && !trinketSnapshot.isEmpty()) {
            ValueOutput.TypedOutputList<TrinketStackEntry> trinketList = output.list("TrinketSnapshot", TrinketStackEntry.CODEC);
            for (Map.Entry<String, ItemStackTemplate> entry : trinketSnapshot.entrySet()) {
                if (entry.getValue() != null) {
                    trinketList.add(new TrinketStackEntry(entry.getKey(), entry.getValue().create()));
                }
            }
        }
    }
}
