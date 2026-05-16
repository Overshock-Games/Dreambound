package com.dreambound.component;

import com.dreambound.DreamboundMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    // -------------------------------------------------------------------------
    // CardinalComponent - data serialization (pendingRespawnItems intentionally excluded)
    // -------------------------------------------------------------------------

    @Override
    public void readData(ValueInput input) {
        // "HasSnapshot" distinguishes "no snapshot yet" from "snapshot of an empty inventory"
        if (!input.getBooleanOr("HasSnapshot", false)) {
            snapshot = null;
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
    }
}
