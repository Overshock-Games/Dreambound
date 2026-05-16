package com.dreambound.mixin;

import com.dreambound.DreamboundMod;
import com.dreambound.DeathSnapshotCalculator;
import com.dreambound.ModComponents;
import com.dreambound.component.DreamStateComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = ServerPlayer.class, priority = 2000)
public abstract class ServerPlayerDeathMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void dreambound$prepareUniversalGravesDeath(DamageSource damageSource, CallbackInfo ci) {
        if (!DreamboundMod.UNIVERSAL_GRAVES_LOADED) return;

        ServerPlayer player = (ServerPlayer) (Object) this;
        if (Boolean.TRUE.equals(player.level().getServer().getGameRules().get(GameRules.KEEP_INVENTORY))) return;

        DreamStateComponent component = ModComponents.DREAM_STATE.get(player);
        if (!component.hasSnapshot() || component.getPendingRespawnItems() != null) return;

        DeathSnapshotCalculator.Result result = DeathSnapshotCalculator.calculate(component, player.getInventory());
        List<ItemStackTemplate> pending = result.pending();
        component.setPendingRespawnItems(pending);
        DreamboundMod.buildGraveKeptCounts(player, pending);
        int forfeitedItems = result.forfeited().stream().mapToInt(ItemStack::getCount).sum();
        if (result.forfeited().isEmpty()) {
            DreamboundMod.graveCompassCandidates.remove(player.getUUID());
        } else {
            DreamboundMod.graveCompassCandidates.add(player.getUUID());
        }

        if (DreamboundMod.CONFIG.restoreExperience) {
            int deathXp = player.totalExperience;
            int keepXp = DreamboundMod.CONFIG.applyExperienceLoss(Math.min(deathXp, component.getSnapshotXp()));
            int dropXp = deathXp - keepXp;
            component.setPendingXp(keepXp);
            player.totalExperience = 0;
            player.experienceLevel = 0;
            player.experienceProgress = 0f;
            if (dropXp > 0) {
                ExperienceOrb.award((ServerLevel) player.level(), player.position(), dropXp);
            }
        } else {
            component.setPendingXp(0);
        }

        if (DreamboundMod.CONFIG.debugUniversalGravesCompat) {
            DreamboundMod.LOGGER.info(
                "Dreambound UG debug: prepared death for {} pendingSlots={} pendingItems={} forfeitedStacks={} forfeitedItems={} compassCandidate={}",
                player.getName().getString(),
                pending.stream().filter(t -> t != null).count(),
                pending.stream().filter(t -> t != null).mapToInt(ItemStackTemplate::count).sum(),
                result.forfeited().size(),
                forfeitedItems,
                !result.forfeited().isEmpty()
            );
        } else {
            DreamboundMod.LOGGER.debug(
                "Dreambound: precomputed Universal Graves death handling for {} - {} pending, {} forfeited",
                player.getName().getString(),
                pending.stream().filter(t -> t != null).count(),
                result.forfeited().size()
            );
        }
    }

}
