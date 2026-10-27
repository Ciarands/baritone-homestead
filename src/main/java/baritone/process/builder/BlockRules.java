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

package baritone.process.builder;

import baritone.Baritone;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * When a block in the world counts as the one the schematic wants: one set of rules for planning, placing and checking
 */
public final class BlockRules {

    private BlockRules() {}

    /**
     * @return The block placed first for {@code desired} when it's finished by using an item on it (a flower pot,
     * for a potted plant), or null if it's placed as it is
     */
    public static BlockState baseOf(BlockState desired) {
        if (desired.getBlock() instanceof FlowerPotBlock && ((FlowerPotBlock) desired.getBlock()).getContent() != Blocks.AIR) {
            return Blocks.FLOWER_POT.defaultBlockState();
        }
        return null;
    }

    /**
     * @return What to use on the base (see baseOf) to finish {@code desired}, or null
     */
    public static Item useItem(BlockState desired) {
        return baseOf(desired) == null ? null : ((FlowerPotBlock) desired.getBlock()).getContent().asItem();
    }

    /**
     * @return Whether {@code current} is the base of {@code desired}, still to be used with an item
     */
    public static boolean needsUse(BlockState current, BlockState desired) {
        BlockState base = desired == null ? null : baseOf(desired);
        return base != null && current.getBlock() == base.getBlock();
    }

    public static final Set<Property<?>> ORIENTATION_PROPS = ImmutableSet.of(
            RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING,
            StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
            PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP,
            TrapDoorBlock.OPEN, TrapDoorBlock.HALF
    );

    /**
     * Properties a placement can't set: the world derives them from the surroundings (connections, water, leaf
     * distance, power) or they change over time or by using the block. Comparing them made blocks that are right look
     * wrong forever: walls, fences and panes connect only once their neighbours are there, placed leaves are always
     * persistent, and so on, so the builder never placed them or kept breaking and replacing them.
     */
    public static final Set<String> UNCONTROLLED_PROPERTIES = ImmutableSet.of(
            "north", "east", "south", "west", "up", "down", // fence, wall, pane, bar, wire, vine, mushroom connections
            "shape", "in_wall", "waterlogged", "distance", "persistent", "snowy", "attached", "disarmed",
            "powered", "power", "triggered", "lit", "enabled", "extended", "short", "occupied", "has_book", "has_record",
            "has_bottle_0", "has_bottle_1", "has_bottle_2", "instrument", "note", "age", "stage", "berries", "bites",
            "honey_level", "level", "moisture", "hatch", "charges", "bloom", "can_summon", "shrieking",
            "sculk_sensor_phase", "thickness", "drag", "unstable", "cracked", "tilt", "locked", "conditional",
            // set by repeated placing or by using the block, which the builder doesn't do: built as one, or default
            "candles", "pickles", "eggs", "layers", "flower_amount", "delay", "mode", "inverted"
    );

    /**
     * @param forPlacement Comparing what a placement would produce: properties fixed up after placing (opening,
     *                     completing a double slab) don't have to match yet
     */
    public static boolean sameBlockstate(BlockState first, BlockState second, boolean forPlacement) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        if (first.equals(second)) {
            return true;
        }
        boolean ignoreDirection = Baritone.settings().buildIgnoreDirection.value;
        List<String> ignoredProps = Baritone.settings().buildIgnoreProperties.value;
        if (forPlacement && first.getBlock() instanceof SlabBlock && second.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
            return true; // either half is the first step towards a double slab
        }
        ImmutableMap<Property<?>, Comparable<?>> map1 = first.getValues();
        ImmutableMap<Property<?>, Comparable<?>> map2 = second.getValues();
        for (Property<?> prop : map1.keySet()) {
            if (map1.get(prop) != map2.get(prop)
                    && !UNCONTROLLED_PROPERTIES.contains(prop.getName())
                    && !(forPlacement && prop == BlockStateProperties.OPEN)
                    && !(forPlacement && prop == ChestBlock.TYPE) // the first of a double chest goes down single
                    && !(ignoreDirection && ORIENTATION_PROPS.contains(prop))
                    // opening a gate turns it to face away from whoever opens it (walking through does): either way
                    // round along the same line is the same gate. Not for placing it, which can get it exactly right
                    && !(!forPlacement && first.getBlock() instanceof FenceGateBlock && prop == FenceGateBlock.FACING
                    && first.getValue(FenceGateBlock.FACING).getAxis() == second.getValue(FenceGateBlock.FACING).getAxis())
                    && !ignoredProps.contains(prop.getName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param itemVerify Comparing what a placement would produce rather than what's there (see sameBlockstate)
     */
    public static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        // water only: lava where a block should be is never fine
        if (current.getBlock() instanceof LiquidBlock && current.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && Baritone.settings().okIfWater.value) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && Baritone.settings().okIfAir.value.contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && Baritone.settings().buildIgnoreBlocks.value.contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && Baritone.settings().buildIgnoreExisting.value && !itemVerify) {
            return true;
        }
        if (Baritone.settings().buildValidSubstitutes.value.getOrDefault(desired.getBlock(), Collections.emptyList()).contains(current.getBlock()) && !itemVerify) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        if (current.getBlock() == Blocks.BAMBOO_SAPLING && desired.getBlock() == Blocks.BAMBOO) {
            return true; // bamboo is planted as a sapling and grows into it: nothing else can be done
        }
        if (itemVerify && needsUse(current, desired)) {
            return true; // the first step: the base, used with an item afterwards
        }
        return sameBlockstate(current, desired, itemVerify);
    }

    /**
     * @return Whether what placed {@code placeable} also places a block of the kind {@code desired} is, in some state:
     * the question for "do we have it", where the state depends on how it's placed. By item, as one item can place
     * different blocks (a torch, or a wall torch when clicking a side)
     */
    public static boolean sameBlock(BlockState placeable, BlockState desired) {
        return !(placeable.getBlock() instanceof AirBlock) && placeable.getBlock().asItem() != Items.AIR
                && placeable.getBlock().asItem() == desired.getBlock().asItem();
    }

    /**
     * @return Whether {@code current} only needs finishing to become {@code desired}, not breaking: opening or closing
     * by using it, a second slab placed onto it, or an item used on it (a plant put in a pot)
     */
    public static boolean completable(BlockState current, BlockState desired) {
        if (needsUse(current, desired)) {
            return true;
        }
        if (desired == null || current.getBlock() != desired.getBlock()) {
            return false;
        }
        return needsToggle(current, desired) || needsSecondSlab(current, desired) || awaitsPartner(current, desired);
    }

    /**
     * @return Whether {@code current} is the first half of a double chest, placed single: it pairs up when the other
     * half is placed against it
     */
    public static boolean awaitsPartner(BlockState current, BlockState desired) {
        return current.getBlock() instanceof ChestBlock && current.getBlock() == desired.getBlock()
                && current.getValue(ChestBlock.TYPE) == ChestType.SINGLE && desired.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && sameBlockstate(current, desired, true);
    }

    public static boolean needsToggle(BlockState current, BlockState desired) {
        Block block = current.getBlock();
        return block == desired.getBlock() // (another block where it goes has nothing to toggle towards)
                && current.hasProperty(BlockStateProperties.OPEN)
                && block != Blocks.IRON_DOOR && block != Blocks.IRON_TRAPDOOR // only redstone opens these
                && current.getValue(BlockStateProperties.OPEN) != desired.getValue(BlockStateProperties.OPEN)
                && sameBlockstate(current, desired, true);
    }

    public static boolean needsSecondSlab(BlockState current, BlockState desired) {
        return current.getBlock() instanceof SlabBlock && current.getBlock() == desired.getBlock()
                && current.getValue(SlabBlock.TYPE) != SlabType.DOUBLE && desired.getValue(SlabBlock.TYPE) == SlabType.DOUBLE;
    }
}
