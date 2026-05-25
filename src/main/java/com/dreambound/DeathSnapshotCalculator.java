package com.dreambound;

import com.dreambound.component.DreamStateComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class DeathSnapshotCalculator {

    private static final int ARMOR_SIZE = 4;
    private static final int TOTAL_SIZE = Inventory.INVENTORY_SIZE + ARMOR_SIZE + 1;

    private DeathSnapshotCalculator() {
    }

    public static Result calculate(DreamStateComponent component, Inventory inv) {
        List<ItemStackTemplate> snapshot = component.getSnapshot();

        List<ItemStack> deathStacks = new ArrayList<>(TOTAL_SIZE);
        deathStacks.addAll(inv.getNonEquipmentItems());
        for (int i = 0; i < ARMOR_SIZE; i++) {
            deathStacks.add(inv.getItem(Inventory.INVENTORY_SIZE + i));
        }
        deathStacks.add(inv.getItem(Inventory.SLOT_OFFHAND));

        List<ItemStackTemplate> pending = new ArrayList<>(deathStacks.size());
        List<ItemStack> forfeited = new ArrayList<>();
        Consumer<ItemStack> onForfeited = forfeited::add;

        for (int slot = 0; slot < deathStacks.size(); slot++) {
            ItemStack deathStack = deathStacks.get(slot);
            ItemStackTemplate sleepTemplate = slot < snapshot.size() ? snapshot.get(slot) : null;

            if (deathStack.isEmpty()) {
                pending.add(null);
                continue;
            }

            if (EnchantmentHelper.has(deathStack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                pending.add(null);
                continue;
            }

            if (EnchantmentHelper.has(deathStack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
                pending.add(ItemStackTemplate.fromNonEmptyStack(deathStack));
                continue;
            }

            if (sleepTemplate == null) {
                onForfeited.accept(deathStack.copy());
                pending.add(null);
                continue;
            }

            if (deathStack.isDamageableItem()) {
                pending.add(resolveUnstackable(deathStack, sleepTemplate, onForfeited));
            } else {
                pending.add(resolveStackable(deathStack, sleepTemplate, onForfeited));
            }
        }

        return new Result(pending, forfeited);
    }

    private static ItemStackTemplate resolveUnstackable(
            ItemStack deathStack,
            ItemStackTemplate sleepTemplate,
            Consumer<ItemStack> onForfeited) {

        if (!isSameDreamStackIgnoringDamage(deathStack, sleepTemplate)) {
            onForfeited.accept(deathStack.copy());
            return null;
        }

        ItemStack kept = sleepTemplate.create();
        int deathDmg = deathStack.getOrDefault(DataComponents.DAMAGE, 0);
        kept.set(DataComponents.DAMAGE, deathDmg);
        return ItemStackTemplate.fromNonEmptyStack(kept);
    }

    private static ItemStackTemplate resolveStackable(
            ItemStack deathStack,
            ItemStackTemplate sleepTemplate,
            Consumer<ItemStack> onForfeited) {

        if (!ItemStack.isSameItemSameComponents(deathStack, sleepTemplate.create())) {
            onForfeited.accept(deathStack.copy());
            return null;
        }

        int keepCount = Math.min(sleepTemplate.count(), deathStack.getCount());
        int dropCount = deathStack.getCount() - keepCount;

        if (dropCount > 0) {
            onForfeited.accept(deathStack.copyWithCount(dropCount));
        }

        return sleepTemplate.withCount(keepCount);
    }

    private static boolean isSameDreamStackIgnoringDamage(ItemStack deathStack, ItemStackTemplate sleepTemplate) {
        ItemStack deathComparable = deathStack.copy();
        ItemStack sleepComparable = sleepTemplate.create();
        deathComparable.remove(DataComponents.DAMAGE);
        sleepComparable.remove(DataComponents.DAMAGE);
        return ItemStack.isSameItemSameComponents(deathComparable, sleepComparable);
    }

    public record Result(List<ItemStackTemplate> pending, List<ItemStack> forfeited) {}
}
