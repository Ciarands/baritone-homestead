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

package baritone.process;

import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Learns what mining targets really drop by watching: items that appear where a target we were breaking just broke.
 * <p>
 * Predicting drops from loot tables misses whatever changes them in code: mods replacing loot tables at runtime
 * (Spelunkery's rough ores), tools altering drops (auto-smelting pickaxes), and the like. Without knowing those items
 * as targets, Baritone wouldn't go back to pick them up, count them, or make room for them.
 */
final class DropObserver {

    /**
     * Drops show up a tick or two after the break; anything appearing later than this is from something else
     */
    private static final int WINDOW_TICKS = 20;
    private static final double RADIUS_SQ = 2 * 2;

    private static final class Break {

        final Vec3 center;
        final Block block;
        final boolean target;
        final long time;

        Break(BlockPos pos, BlockState state, boolean target, long time) {
            this.center = Vec3.atCenterOf(pos);
            this.block = state.getBlock();
            this.target = target;
            this.time = time;
        }
    }

    private final IPlayerContext ctx;
    /**
     * Observed drops by block, kept across mining runs: they depend on the block (and tool), not on the run. Concurrent:
     * target rescans read it off the main thread
     */
    private final Map<Block, Set<Item>> drops = new ConcurrentHashMap<>();
    private final List<Break> recentBreaks = new ArrayList<>();
    private BlockPos hitting;
    private BlockState hittingState;

    DropObserver(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Call every tick while mining.
     *
     * @param isTarget  The mining targets
     * @param predicted The drops already expected from loot tables: learning those isn't news worth telling
     */
    void tick(Predicate<BlockState> isTarget, Predicate<ItemStack> predicted) {
        long now = ctx.world().getGameTime();
        if (hitting != null && ctx.world().getBlockState(hitting) != hittingState) {
            // every block we break, not just targets: a drop belongs to the nearest one. Something in the way broken
            // a tick before a target next to it would otherwise pass for one of the target's drops
            recentBreaks.add(new Break(hitting, hittingState, isTarget.test(hittingState), now));
        }
        hitting = ctx.getSelectedBlock().orElse(null);
        hittingState = hitting == null ? null : ctx.world().getBlockState(hitting);
        recentBreaks.removeIf(b -> now - b.time > WINDOW_TICKS);
        if (recentBreaks.isEmpty()) {
            return;
        }
        ctx.entitiesStream().filter(ItemEntity.class::isInstance).map(ItemEntity.class::cast).forEach(item -> {
            Break source = null;
            for (Break b : recentBreaks) {
                // spawned since the break (client-side age counts from when we first saw it), right where it broke
                if (item.getAge() <= now - b.time + 1 && item.position().distanceToSqr(b.center) <= RADIUS_SQ
                        && (source == null || item.position().distanceToSqr(b.center) < item.position().distanceToSqr(source.center))) {
                    source = b;
                }
            }
            if (source == null || !source.target) {
                return;
            }
            Item drop = item.getItem().getItem();
            if (drops.computeIfAbsent(source.block, block -> ConcurrentHashMap.newKeySet()).add(drop) && !predicted.test(item.getItem())) {
                Helper.HELPER.logDirect("Also collecting " + BuiltInRegistries.ITEM.getKey(drop) + ": it drops from " + BuiltInRegistries.BLOCK.getKey(source.block));
            }
        });
    }

    /**
     * @return Whether {@code item} has been seen dropping from one of the targets
     */
    boolean isDropOf(Predicate<BlockState> isTarget, Item item) {
        for (Map.Entry<Block, Set<Item>> entry : drops.entrySet()) {
            if (entry.getValue().contains(item) && isTarget.test(entry.getKey().defaultBlockState())) {
                return true;
            }
        }
        return false;
    }
}
