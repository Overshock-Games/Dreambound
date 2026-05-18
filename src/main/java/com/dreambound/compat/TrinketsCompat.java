package com.dreambound.compat;

import com.dreambound.component.DreamStateComponent;
import eu.pb4.trinkets.api.TrinketAttachment;
import eu.pb4.trinkets.api.TrinketInventory;
import eu.pb4.trinkets.api.TrinketSlotAccess;
import eu.pb4.trinkets.api.TrinketsApi;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TrinketsCompat {

    private TrinketsCompat() {}

    /** Snapshots all occupied trinket slots into the component. */
    public static void captureInventory(ServerPlayer player, DreamStateComponent component) {
        TrinketAttachment attachment = TrinketsApi.getAttachment(player);
        Map<String, ItemStackTemplate> snapshot = new HashMap<>();
        attachment.forEach((access, stack) -> {
            if (!stack.isEmpty()) {
                snapshot.put(access.getSerializedName(), ItemStackTemplate.fromNonEmptyStack(stack));
            }
        });
        component.setTrinketSnapshot(snapshot.isEmpty() ? null : snapshot);
    }

    /**
     * Computes which trinket items to restore vs forfeit, stores pending items in the
     * component, clears all trinket slots, and appends forfeited stacks to {@code forfeited}.
     */
    public static void calculateDeathDrop(ServerPlayer player, DreamStateComponent component,
                                          List<ItemStack> forfeited) {
        TrinketAttachment attachment = TrinketsApi.getAttachment(player);
        Map<String, ItemStackTemplate> trinketSnapshot = component.getTrinketSnapshot();
        Map<String, ItemStackTemplate> pending = new HashMap<>();

        attachment.forEach((access, deathStack) -> {
            if (deathStack.isEmpty()) return;

            String key = access.getSerializedName();

            // Curse of Vanishing: destroy silently
            if (EnchantmentHelper.has(deathStack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                return;
            }

            // Curse of Binding: keep at death state
            if (EnchantmentHelper.has(deathStack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
                pending.put(key, ItemStackTemplate.fromNonEmptyStack(deathStack));
                return;
            }

            ItemStackTemplate sleepTemplate = trinketSnapshot != null ? trinketSnapshot.get(key) : null;

            if (sleepTemplate == null) {
                forfeited.add(deathStack.copy());
                return;
            }

            if (deathStack.isDamageableItem()) {
                if (deathStack.getItem() != sleepTemplate.item().value()) {
                    forfeited.add(deathStack.copy());
                } else {
                    ItemStack kept = sleepTemplate.create();
                    kept.set(DataComponents.DAMAGE,
                        deathStack.getOrDefault(DataComponents.DAMAGE, 0));
                    pending.put(key, ItemStackTemplate.fromNonEmptyStack(kept));
                }
            } else {
                int keepCount = Math.min(sleepTemplate.count(), deathStack.getCount());
                int dropCount = deathStack.getCount() - keepCount;
                if (dropCount > 0) forfeited.add(deathStack.copyWithCount(dropCount));
                pending.put(key, sleepTemplate.withCount(keepCount));
            }
        });

        // Clear all trinket slots before we stash the pending map
        attachment.forEach(access -> access.set(ItemStack.EMPTY));
        component.setPendingTrinketItems(pending.isEmpty() ? null : pending);
    }

    /** Restores pending trinket items to the player's slots on respawn. */
    public static void restoreFrom(ServerPlayer player, DreamStateComponent component) {
        Map<String, ItemStackTemplate> pending = component.getPendingTrinketItems();
        if (pending == null || pending.isEmpty()) {
            component.setPendingTrinketItems(null);
            return;
        }

        TrinketAttachment attachment = TrinketsApi.getAttachment(player);

        for (Map.Entry<String, ItemStackTemplate> entry : pending.entrySet()) {
            String key = entry.getKey();
            int at = key.lastIndexOf('@');
            if (at == -1) continue;
            String slotId = key.substring(0, at);
            int index;
            try {
                index = Integer.parseInt(key.substring(at + 1));
            } catch (NumberFormatException e) {
                continue;
            }

            TrinketInventory inv = attachment.getInventory(slotId);
            if (inv == null) continue;
            TrinketSlotAccess access = inv.getSlotAccess(index);
            if (access != null) {
                access.set(entry.getValue().create());
            }
        }

        component.setPendingTrinketItems(null);
    }
}
