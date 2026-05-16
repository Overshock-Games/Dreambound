package com.dreambound;

import com.dreambound.component.DreamStateComponent;
import com.dreambound.component.DreamStateComponentImpl;
import net.minecraft.resources.Identifier;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentInitializer;
import org.ladysnake.cca.api.v3.entity.RespawnCopyStrategy;

public final class ModComponents implements EntityComponentInitializer {

    public static final ComponentKey<DreamStateComponent> DREAM_STATE =
        ComponentRegistry.getOrCreate(
            Identifier.fromNamespaceAndPath(DreamboundMod.MOD_ID, "dream_state"),
            DreamStateComponent.class
        );

    @Override
    public void registerEntityComponentFactories(EntityComponentFactoryRegistry registry) {
        // ALWAYS_COPY: the sleep snapshot persists through death so it remains valid
        // at the time the player respawns at their last set spawn point.
        registry.registerForPlayers(
            DREAM_STATE,
            player -> new DreamStateComponentImpl(),
            RespawnCopyStrategy.ALWAYS_COPY
        );
    }
}
