package com.dreambound;

import com.dreambound.component.DreamStateComponent;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStackTemplate;

import java.util.List;

public final class DreamboundCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dreambound")
            .then(Commands.literal("status")
                .executes(ctx -> status(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("target", EntityArgument.player())
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(ctx -> status(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))))
            .then(Commands.literal("reload")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> {
                    DreamboundMod.CONFIG = DreamboundConfig.load();
                    DreamboundMod.refreshUniversalGravesCompat();
                    ctx.getSource().sendSuccess(() ->
                        Component.literal("[Dreambound] ").withStyle(ChatFormatting.DARK_PURPLE)
                            .append(Component.literal("Config reloaded.").withStyle(ChatFormatting.LIGHT_PURPLE)),
                        true
                    );
                    return 1;
                })));
    }

    private static int status(CommandSourceStack source, ServerPlayer player) throws CommandSyntaxException {
        DreamStateComponent component = ModComponents.DREAM_STATE.get(player);

        if (!component.hasSnapshot()) {
            source.sendSuccess(() ->
                Component.literal("[Dreambound] ").withStyle(ChatFormatting.DARK_PURPLE)
                    .append(Component.literal(player.getName().getString() + " has no dream snapshot.").withStyle(ChatFormatting.GRAY)),
                false
            );
            return 0;
        }

        List<ItemStackTemplate> snapshot = component.getSnapshot();
        long itemSlots = snapshot.stream().filter(t -> t != null).count();
        int xp = component.getSnapshotXp();
        DreamboundConfig cfg = DreamboundMod.CONFIG;

        source.sendSuccess(() ->
            Component.literal("[Dreambound] ").withStyle(ChatFormatting.DARK_PURPLE)
                .append(Component.literal(String.format(
                    "%s: %d item slot(s), %d XP | bed=%s anchor=%s graves=%s xp=%s xpLoss=%d%% clearOnRespawn=%s",
                    player.getName().getString(),
                    itemSlots,
                    xp,
                    cfg.enableBedSleepSnapshots,
                    cfg.enableRespawnAnchorLogic,
                    cfg.enableUniversalGravesCompat,
                    cfg.restoreExperience,
                    cfg.experienceLossPercent,
                    cfg.clearSnapshotOnRespawn
                )).withStyle(ChatFormatting.LIGHT_PURPLE)),
            false
        );
        return 1;
    }
}
