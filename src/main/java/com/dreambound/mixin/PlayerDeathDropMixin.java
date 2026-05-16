package com.dreambound.mixin;

import com.dreambound.DreamboundMod;
import com.dreambound.DeathSnapshotCalculator;
import com.dreambound.ModComponents;
import com.dreambound.component.DreamStateComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(Player.class)
public abstract class PlayerDeathDropMixin {

    @Inject(method = "dropEquipment", at = @At("HEAD"), cancellable = true)
    private void dreambound$interceptDeathDrop(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!(self instanceof ServerPlayer player)) return;

        if (Boolean.TRUE.equals(player.level().getServer().getGameRules().get(GameRules.KEEP_INVENTORY))) return;

        DreamStateComponent component = ModComponents.DREAM_STATE.get(player);
        if (!component.hasSnapshot()) return;

        Inventory inv = player.getInventory();
        List<ItemStackTemplate> pending = component.getPendingRespawnItems();
        if (DreamboundMod.UNIVERSAL_GRAVES_LOADED && pending != null) {
            ci.cancel();
            inv.clearContent();
            DreamboundMod.LOGGER.debug(
                "Dreambound: death drop intercepted for {} with precomputed UG pending items",
                player.getName().getString()
            );
            return;
        }

        ci.cancel();

        DeathSnapshotCalculator.Result result = DeathSnapshotCalculator.calculate(component, inv);
        pending = result.pending();
        component.setPendingRespawnItems(pending);

        if (DreamboundMod.CONFIG.restoreExperience) {
            // XP: keep min(death, sleep), drop the rest as orbs.
            // Zeroing player XP before vanilla's XP-drop call (in ServerPlayer.die) means vanilla drops 0.
            int deathXp = player.totalExperience;
            int keepXp  = DreamboundMod.CONFIG.applyExperienceLoss(Math.min(deathXp, component.getSnapshotXp()));
            int dropXp  = deathXp - keepXp;
            component.setPendingXp(keepXp);
            player.totalExperience    = 0;
            player.experienceLevel    = 0;
            player.experienceProgress = 0f;
            if (dropXp > 0) {
                ExperienceOrb.award((ServerLevel) player.level(), player.position(), dropXp);
            }
        } else {
            component.setPendingXp(0);
        }

        for (ItemStack stack : result.forfeited()) {
            player.drop(stack, true, false);
        }

        inv.clearContent();

        DreamboundMod.LOGGER.debug(
            "Dreambound: death drop intercepted for {} - {} pending, {} forfeited",
            player.getName().getString(),
            pending.stream().filter(t -> t != null).count(),
            result.forfeited().size()
        );
    }
}
