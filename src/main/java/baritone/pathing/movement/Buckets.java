/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Buckets of any make for breaking a fall with water: the vanilla one, buckets of fish, and modded ones (wooden,
 * copper) that hold water but aren't the vanilla item, some not even a {@link BucketItem}. What a bucket holds is read
 * from the fluid it keeps, found by type, so a mod's own bucket counts without knowing it by name
 */
public final class Buckets {

    private static final Map<Class<?>, Optional<Field>> FLUID_FIELDS = new ConcurrentHashMap<>();

    private Buckets() {}

    /**
     * @return The hotbar slot of a bucket that empties out water (anything the game counts as water), or -1. Plain
     * water comes first, then other fluids counted as water (a mod's, which may do more than water does), then a
     * bucket of fish, which would let the fish out too
     */
    public static int waterSlot(Inventory inventory) {
        int slot = find(inventory, item -> !(item instanceof MobBucketItem), fluid -> fluid == Fluids.WATER);
        if (slot < 0) {
            slot = find(inventory, item -> !(item instanceof MobBucketItem), fluid -> fluid.is(FluidTags.WATER));
        }
        return slot >= 0 ? slot : find(inventory, item -> true, fluid -> fluid.is(FluidTags.WATER));
    }

    /**
     * @return The hotbar slot of an empty bucket of any make, to take the water back up with, or -1
     */
    public static int emptySlot(Inventory inventory) {
        return find(inventory, item -> true, fluid -> fluid == Fluids.EMPTY);
    }

    private static int find(Inventory inventory, Predicate<Item> item, Predicate<Fluid> fluid) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            Item held = inventory.getItem(slot).getItem();
            Fluid content = content(held);
            if (content != null && item.test(held) && fluid.test(content)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * @return What the bucket holds, or null if it isn't a bucket
     */
    private static Fluid content(Item item) {
        if (!(item instanceof BucketItem) && !(item instanceof DispensibleContainerItem)) {
            return null;
        }
        Optional<Field> field = FLUID_FIELDS.computeIfAbsent(item.getClass(), Buckets::fluidField);
        try {
            return field.isPresent() ? (Fluid) field.get().get(item) : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Optional<Field> fluidField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Item.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && Fluid.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        return Optional.of(field);
                    } catch (RuntimeException e) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }
}
