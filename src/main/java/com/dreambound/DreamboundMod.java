package com.dreambound;

import com.dreambound.compat.UniversalGravesCompat;
import com.dreambound.component.DreamStateComponent;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class DreamboundMod implements ModInitializer {

	public static final String MOD_ID = "dreambound";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static DreamboundConfig CONFIG;
	public static boolean UNIVERSAL_GRAVES_LOADED;
	private static boolean universalGravesRegistered;

	private static final int SLEEP_COMPLETION_GRACE_TICKS = 20;
	private static final int GRAVE_COMPASS_RETRY_INTERVAL_TICKS = 5;
	private static final int GRAVE_COMPASS_MAX_RETRIES = 40;

	// UUID -> tracked sleep attempt. Evaluated after wake-up so vanilla can finish
	// the time/weather transition before we decide whether sleep succeeded.
	private static final Map<UUID, SleepAttempt> sleepAttempts = new HashMap<>();

	// UUID -> kept-item-counts; populated by the death mixin, consumed by the UG event.
	public static final Map<UUID, Map<Item, Integer>> graveKeptCounts = new HashMap<>();
	public static final Set<UUID> graveCompassCandidates = new HashSet<>();
	private static final Map<UUID, Integer> delayedRespawnRestores = new HashMap<>();
	private static final Map<UUID, GraveCompassRestoreAttempt> pendingGraveCompassRestores = new HashMap<>();

	@Override
	public void onInitialize() {
		CONFIG = DreamboundConfig.load();

		registerEvents();
		CommandRegistrationCallback.EVENT.register(
			(dispatcher, registryAccess, environment) -> DreamboundCommand.register(dispatcher)
		);

		refreshUniversalGravesCompat();

		LOGGER.info("Dreambound initialized.");
	}

	public static void refreshUniversalGravesCompat() {
		if (!CONFIG.enableUniversalGravesCompat || !FabricLoader.getInstance().isModLoaded("universal-graves")) {
			UNIVERSAL_GRAVES_LOADED = false;
			return;
		}

		if (!universalGravesRegistered) {
			universalGravesRegistered = UniversalGravesCompat.register();
			if (universalGravesRegistered) {
				LOGGER.info("Dreambound: Universal Graves detected - grave integration active.");
			} else {
				LOGGER.warn("Dreambound: Universal Graves detected, but grave integration is inactive.");
			}
		}

		UNIVERSAL_GRAVES_LOADED = universalGravesRegistered;
	}

	// Called by the death mixin to set up UG filtering for a given player.
	public static void buildGraveKeptCounts(ServerPlayer player, List<ItemStackTemplate> pending) {
		Map<Item, Integer> counts = new HashMap<>();
		for (ItemStackTemplate t : pending) {
			if (t != null) counts.merge(t.item().value(), t.count(), Integer::sum);
		}
		graveKeptCounts.put(player.getUUID(), counts);
	}

	private static void saveDreamSnapshot(ServerPlayer player) {
		ModComponents.DREAM_STATE.get(player).captureInventory(player);
		if (CONFIG.notifySnapshotSaved) {
			player.sendSystemMessage(
				Component.literal("Dreambound").withStyle(ChatFormatting.DARK_PURPLE)
					.append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal("Your inventory drifts safely into the dream.").withStyle(ChatFormatting.LIGHT_PURPLE))
			);
		}
	}

	private static void registerEvents() {

		// --- Beds: detect sleep start to record thundering state ---
		EntitySleepEvents.START_SLEEPING.register((entity, sleepingPos) -> {
			if (!CONFIG.enableBedSleepSnapshots) return;
			if (entity instanceof ServerPlayer player) {
				sleepAttempts.put(player.getUUID(), new SleepAttempt(player.level().isThundering(), 0));
			}
		});

		// --- Beds: mark wake-up; the server tick callback below evaluates success ---
		EntitySleepEvents.STOP_SLEEPING.register((entity, sleepingPos) -> {
			if (!CONFIG.enableBedSleepSnapshots) return;
			if (!(entity instanceof ServerPlayer player)) return;
			SleepAttempt attempt = sleepAttempts.get(player.getUUID());
			if (attempt != null) {
				attempt.stoppedAtTick = 0;
			}
		});

		EntitySleepEvents.ALLOW_RESETTING_TIME.register(player -> {
			if (!CONFIG.enableBedSleepSnapshots) return true;
			if (player instanceof ServerPlayer serverPlayer) {
				SleepAttempt attempt = sleepAttempts.get(serverPlayer.getUUID());
				if (attempt != null) {
					attempt.allowedResettingTime = true;
				}
			}
			return true;
		});

		ServerTickEvents.END_SERVER_TICK.register(DreamboundMod::finishCompletedSleepAttempts);

		// --- Respawn anchors ---
		// Always register; config is checked inside so /dreambound reload takes effect.
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			if (!CONFIG.enableRespawnAnchorLogic) return InteractionResult.PASS;
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
			if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

			BlockState state = level.getBlockState(hitResult.getBlockPos());
			if (!(state.getBlock() instanceof RespawnAnchorBlock)) return InteractionResult.PASS;

			boolean isCharged   = state.getValue(RespawnAnchorBlock.CHARGE) > 0;
			boolean inNether    = level.dimension().equals(Level.NETHER);
			boolean notCharging = !serverPlayer.getItemInHand(hand).is(Items.GLOWSTONE);

			if (isCharged && inNether && notCharging) {
				// Dedup: skip if the player is clicking the anchor they already have set.
				LevelData.RespawnData current = serverPlayer.getRespawnConfig().respawnData();
				BlockPos anchorPos = hitResult.getBlockPos();
				boolean sameAnchor = current.pos().equals(anchorPos)
					&& current.dimension().equals(level.dimension());

				if (!sameAnchor) {
					saveDreamSnapshot(serverPlayer);
				}
			}

			return InteractionResult.PASS;
		});

		// --- Respawn restoration ---
		ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
			if (alive) return;

			DreamStateComponent oldComponent = ModComponents.DREAM_STATE.get(oldPlayer);
			List<ItemStackTemplate> pending = oldComponent.getPendingRespawnItems();
			if (pending == null) return;

			DreamStateComponent newComponent = ModComponents.DREAM_STATE.get(newPlayer);
			newComponent.setPendingRespawnItems(pending);
			newComponent.setPendingXp(oldComponent.getPendingXp());
			oldComponent.setPendingRespawnItems(null);
			oldComponent.setPendingXp(0);
			graveKeptCounts.remove(oldPlayer.getUUID());

			if (UNIVERSAL_GRAVES_LOADED) {
				delayedRespawnRestores.put(newPlayer.getUUID(), newPlayer.level().getServer().getTickCount());
			} else {
				restoreDreamState(newPlayer, newComponent);
			}
		});
	}

	private static void restoreDreamState(ServerPlayer player, DreamStateComponent component) {
		List<ItemStackTemplate> pending = component.getPendingRespawnItems();
		int restoredItems = countItems(pending);
		int restoredXp = component.getPendingXp();

		if (pending != null && !pending.isEmpty()) {
			Inventory inv = player.getInventory();
			List<ItemStack> displaced = new java.util.ArrayList<>();
			for (int slot = 0; slot < pending.size(); slot++) {
				ItemStackTemplate template = pending.get(slot);
				if (template != null) {
					ItemStack existing = inv.getItem(slot);
					if (!existing.isEmpty()) {
						displaced.add(existing);
						inv.setItem(slot, ItemStack.EMPTY);
					}
				}
			}
			for (int slot = 0; slot < pending.size(); slot++) {
				ItemStackTemplate template = pending.get(slot);
				if (template != null) {
					inv.setItem(slot, template.create());
				}
			}
			for (ItemStack stack : displaced) {
				inv.placeItemBackInInventory(stack);
			}
		}

		if (CONFIG.restoreExperience) {
			player.giveExperiencePoints(restoredXp);
		}

		if (UNIVERSAL_GRAVES_LOADED) {
			boolean shouldRestoreCompass = graveCompassCandidates.remove(player.getUUID());
			if (CONFIG.debugUniversalGravesCompat) {
				LOGGER.info(
					"Dreambound UG debug: respawn restore for {} restoredItems={} restoredXp={} compassCandidate={}",
					player.getName().getString(),
					restoredItems,
					restoredXp,
					shouldRestoreCompass
				);
			}
			if (shouldRestoreCompass) {
				pendingGraveCompassRestores.put(
					player.getUUID(),
					new GraveCompassRestoreAttempt(player.level().getServer().getTickCount() + 1, 0)
				);
			}
		}

		component.setPendingRespawnItems(null);
		component.setPendingXp(0);

		if (CONFIG.clearSnapshotOnRespawn) {
			component.clearSnapshot();
		}

		if (CONFIG.notifyRespawnRestore && (restoredItems > 0 || restoredXp > 0)) {
			player.sendSystemMessage(
				Component.literal("Dreambound").withStyle(ChatFormatting.DARK_PURPLE)
					.append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal("You woke from the dream. ").withStyle(ChatFormatting.LIGHT_PURPLE))
					.append(Component.literal(String.valueOf(restoredItems)).withStyle(ChatFormatting.AQUA))
					.append(Component.literal(restoredItems == 1 ? " item followed you back." : " items followed you back.").withStyle(ChatFormatting.LIGHT_PURPLE))
			);
		}

		LOGGER.debug(
			"Dreambound: restored {} item(s) to {} on respawn",
			pending != null ? pending.stream().filter(t -> t != null).count() : 0,
			player.getName().getString()
		);
	}

	private static void finishCompletedSleepAttempts(MinecraftServer server) {
		finishDelayedRespawnRestores(server);
		finishPendingGraveCompassRestores(server);

		if (!CONFIG.enableBedSleepSnapshots) {
			sleepAttempts.clear();
			return;
		}
		if (sleepAttempts.isEmpty()) return;

		int tick = server.getTickCount();
		Iterator<Map.Entry<UUID, SleepAttempt>> iterator = sleepAttempts.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, SleepAttempt> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				iterator.remove();
				continue;
			}

			if (player.isSleeping()) continue;

			SleepAttempt attempt = entry.getValue();
			if (attempt.stoppedAtTick <= 0) {
				attempt.stoppedAtTick = tick;
			}

			Level level = player.level();
			boolean sleepSucceeded = attempt.allowedResettingTime
				&& (level.isBrightOutside() || (attempt.wasThunderingAtStart && !level.isThundering()));
			if (sleepSucceeded) {
				saveDreamSnapshot(player);
				iterator.remove();
			} else if (tick - attempt.stoppedAtTick >= SLEEP_COMPLETION_GRACE_TICKS) {
				iterator.remove();
			}
		}
	}

	private static void finishDelayedRespawnRestores(MinecraftServer server) {
		if (delayedRespawnRestores.isEmpty()) return;

		int tick = server.getTickCount();
		Iterator<Map.Entry<UUID, Integer>> iterator = delayedRespawnRestores.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Integer> entry = iterator.next();
			if (tick <= entry.getValue()) continue;

			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null) {
				restoreDreamState(player, ModComponents.DREAM_STATE.get(player));
			}
			iterator.remove();
		}
	}

	private static void finishPendingGraveCompassRestores(MinecraftServer server) {
		if (pendingGraveCompassRestores.isEmpty()) return;

		int tick = server.getTickCount();
		Iterator<Map.Entry<UUID, GraveCompassRestoreAttempt>> iterator = pendingGraveCompassRestores.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, GraveCompassRestoreAttempt> entry = iterator.next();
			GraveCompassRestoreAttempt attempt = entry.getValue();
			if (tick < attempt.nextTick) continue;

			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				iterator.remove();
				continue;
			}

			attempt.tries++;
			if (UniversalGravesCompat.giveCompassIfNeeded(player)) {
				iterator.remove();
				continue;
			}

			if (attempt.tries >= GRAVE_COMPASS_MAX_RETRIES) {
				if (CONFIG.debugUniversalGravesCompat) {
					LOGGER.info(
						"Dreambound UG debug: stopped trying to restore compass for {} after {} attempts",
						player.getName().getString(),
						attempt.tries
					);
				}
				iterator.remove();
				continue;
			}

			attempt.nextTick = tick + GRAVE_COMPASS_RETRY_INTERVAL_TICKS;
		}
	}

	private static int countItems(List<ItemStackTemplate> templates) {
		if (templates == null) return 0;
		int count = 0;
		for (ItemStackTemplate template : templates) {
			if (template != null) count += template.count();
		}
		return count;
	}

	private static final class SleepAttempt {
		private final boolean wasThunderingAtStart;
		private int stoppedAtTick;
		private boolean allowedResettingTime;

		private SleepAttempt(boolean wasThunderingAtStart, int stoppedAtTick) {
			this.wasThunderingAtStart = wasThunderingAtStart;
			this.stoppedAtTick = stoppedAtTick;
		}
	}

	private static final class GraveCompassRestoreAttempt {
		private int nextTick;
		private int tries;

		private GraveCompassRestoreAttempt(int nextTick, int tries) {
			this.nextTick = nextTick;
			this.tries = tries;
		}
	}
}
