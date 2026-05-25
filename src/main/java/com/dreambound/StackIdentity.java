package com.dreambound;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

public record StackIdentity(Item item, DataComponentMap components) {

    public static StackIdentity of(ItemStack stack) {
        return new StackIdentity(stack.getItem(), stack.immutableComponents());
    }

    public static StackIdentity of(ItemStackTemplate template) {
        return of(template.create());
    }
}
