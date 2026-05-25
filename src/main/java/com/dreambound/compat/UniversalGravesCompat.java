package com.dreambound.compat;

import com.dreambound.DreamboundMod;
import com.dreambound.StackIdentity;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Universal Graves integration loaded reflectively so we don't need UG on the
 * compile classpath. Universal Graves fires PlayerGraveItemAddedEvent for every item it is
 * about to store; we block items that are already in pendingRespawnItems and
 * leave forfeited items available for the grave.
 */
public final class UniversalGravesCompat {

    private UniversalGravesCompat() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean register() {
        try {
            registerItemAddedEvent();
            return true;
        } catch (Exception e) {
            DreamboundMod.LOGGER.error("Dreambound: failed to register Universal Graves compat", e);
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerItemAddedEvent() throws ReflectiveOperationException {
        Class<?> ifaceClass = Class.forName("eu.pb4.graves.event.PlayerGraveItemAddedEvent");
        Object fabricEvent = ifaceClass.getDeclaredField("EVENT").get(null);

        Object listener = Proxy.newProxyInstance(
            UniversalGravesCompat.class.getClassLoader(),
            new Class<?>[]{ ifaceClass },
            (proxy, method, args) -> {
                if ("canAddItem".equals(method.getName()) && args != null && args.length == 2) {
                    return onCanAddItem((ServerPlayer) args[0], (ItemStack) args[1]);
                }
                return null;
            }
        );

        ((Event) fabricEvent).register(listener);
    }

    private static InteractionResult onCanAddItem(ServerPlayer player, ItemStack itemStack) {
        Map<StackIdentity, Integer> keptCounts = DreamboundMod.graveKeptCounts.get(player.getUUID());
        if (keptCounts == null) {
            debug("allowing {} x{} for {} because no kept-count budget exists",
                itemStack.getItem(), itemStack.getCount(), player.getName().getString());
            return InteractionResult.PASS;
        }

        StackIdentity identity = StackIdentity.of(itemStack);
        int kept = keptCounts.getOrDefault(identity, 0);
        if (kept <= 0) {
            debug("allowing {} x{} for {} because none of that item is dream-kept",
                itemStack.getItem(), itemStack.getCount(), player.getName().getString());
            return InteractionResult.PASS;
        }

        int stackCount = itemStack.getCount();
        keptCounts.put(identity, Math.max(0, kept - stackCount));

        int dropCount = stackCount - kept;
        if (dropCount > 0) {
            itemStack.setCount(dropCount);
            debug("trimmed grave item for {}: {} kept={}, graveGets={}",
                player.getName().getString(), itemStack.getItem(), kept, dropCount);
            return InteractionResult.PASS;
        }

        debug("blocked grave item for {}: {} x{} is fully dream-kept",
            player.getName().getString(), itemStack.getItem(), stackCount);
        return InteractionResult.FAIL; // block from grave
    }

    public static boolean giveCompassIfNeeded(ServerPlayer player) {
        try {
            if (!shouldGiveDeathCompass()) {
                debug("skipping compass for {} because Universal Graves config disables it", player.getName().getString());
                return true;
            }

            Class<?> compassItemClass = Class.forName("eu.pb4.graves.registry.GraveCompassItem");
            Class<?> managerClass = Class.forName("eu.pb4.graves.grave.GraveManager");
            Field instanceField = managerClass.getField("INSTANCE");
            Object manager = instanceField.get(null);
            if (manager == null) {
                debug("skipping compass for {} because GraveManager.INSTANCE is null", player.getName().getString());
                return false;
            }

            Method getId = managerClass.getMethod("getId", long.class);
            long graveId = resolveCompassGraveId(player, manager, getId);
            if (graveId < 0) {
                debug("skipping compass for {} because no valid Universal Graves grave id was found", player.getName().getString());
                return false;
            }

            int removed = removeExistingCompass(player, compassItemClass);

            Method create = compassItemClass.getMethod("create", long.class, boolean.class);
            ItemStack compass = (ItemStack) create.invoke(null, graveId, false);
            boolean added = player.addItem(compass);
            if (!added && !compass.isEmpty()) {
                player.drop(compass, false, false);
            }
            debug("gave compass for {} targeting grave {} (removedExisting={}, addedToInventory={}, remaining={})",
                player.getName().getString(), graveId, removed, added, compass.getCount());
            return true;
        } catch (Exception e) {
            DreamboundMod.LOGGER.warn("Dreambound: failed to restore Universal Graves compass", e);
            return true;
        }
    }

    private static long resolveCompassGraveId(ServerPlayer player, Object manager, Method getId) throws ReflectiveOperationException {
        long lastGraveId = getPlayerLastGraveId(player);
        if (lastGraveId >= 0 && getId.invoke(manager, lastGraveId) != null) {
            debug("using player lastGrave {} for {}", lastGraveId, player.getName().getString());
            return lastGraveId;
        }

        debug("player lastGrave for {} is invalid: {}", player.getName().getString(), lastGraveId);
        long latestGraveId = findLatestGraveId(player.getUUID(), manager, getId);
        if (latestGraveId >= 0) {
            setPlayerLastGraveId(player, latestGraveId);
            debug("using latest API grave {} for {}", latestGraveId, player.getName().getString());
        }
        return latestGraveId;
    }

    private static long getPlayerLastGraveId(ServerPlayer player) throws ReflectiveOperationException {
        Class<?> additionsClass = Class.forName("eu.pb4.graves.other.PlayerAdditions");
        Method lastGrave = additionsClass.getMethod("graves$lastGrave");
        return (Long) lastGrave.invoke(additionsClass.cast(player));
    }

    private static void setPlayerLastGraveId(ServerPlayer player, long graveId) throws ReflectiveOperationException {
        Class<?> additionsClass = Class.forName("eu.pb4.graves.other.PlayerAdditions");
        Method setLastGrave = additionsClass.getMethod("graves$setLastGrave", long.class);
        setLastGrave.invoke(additionsClass.cast(player), graveId);
    }

    private static long findLatestGraveId(UUID playerUuid, Object manager, Method getId) throws ReflectiveOperationException {
        Class<?> gravesApiClass = Class.forName("eu.pb4.graves.GravesApi");
        Collection<?> graves = (Collection<?>) gravesApiClass.getMethod("getGravesOf", UUID.class).invoke(null, playerUuid);
        if (graves == null || graves.isEmpty()) {
            debug("GravesApi returned no graves for {}", playerUuid);
            return -1;
        }

        Method getGraveId = null;
        Method getCreationTime = null;
        long latestId = -1;
        long latestCreationTime = Long.MIN_VALUE;
        int validCount = 0;

        for (Object grave : graves) {
            if (grave == null) continue;
            if (getGraveId == null) {
                getGraveId = grave.getClass().getMethod("getId");
                getCreationTime = grave.getClass().getMethod("getCreationTime");
            }

            long id = (Long) getGraveId.invoke(grave);
            long creationTime = (Long) getCreationTime.invoke(grave);
            if (id >= 0 && getId.invoke(manager, id) != null) {
                validCount++;
                if (creationTime >= latestCreationTime) {
                    latestCreationTime = creationTime;
                    latestId = id;
                }
            }
        }

        debug("GravesApi returned {} valid graves for {}; latest={}", validCount, playerUuid, latestId);
        return latestId;
    }

    private static int removeExistingCompass(ServerPlayer player, Class<?> compassItemClass) {
        int removed = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && compassItemClass.isInstance(stack.getItem())) {
                player.getInventory().setItem(slot, ItemStack.EMPTY);
                removed++;
            }
        }
        return removed;
    }

    private static boolean shouldGiveDeathCompass() throws ReflectiveOperationException {
        Class<?> configManagerClass = Class.forName("eu.pb4.graves.config.ConfigManager");
        Object config = configManagerClass.getMethod("getConfig").invoke(null);
        Object interactions = config.getClass().getField("interactions").get(config);
        return interactions.getClass().getField("giveGraveCompass").getBoolean(interactions);
    }

    private static void debug(String message, Object... args) {
        if (DreamboundMod.CONFIG != null && DreamboundMod.CONFIG.debugUniversalGravesCompat) {
            DreamboundMod.LOGGER.info("Dreambound UG debug: " + message, args);
        }
    }
}
