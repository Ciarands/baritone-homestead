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

import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * The Falling Trees mod, for mining wood: breaking a tree's log with an axe brings down the logs above and beside it
 * along with its leaves, as a falling tree that drops everything where it stood once it has landed. So only the base is
 * broken, and the fall waited out. Only trees (logs): not the mod's mushrooms, cacti, bamboo or chorus. An optional
 * dependency, reached by reflection; what fells is worked out with the mod's own search, as the server does it.
 */
final class FallingTrees {

    /**
     * How long what's known to fall with a base is trusted
     */
    private static final int CACHE_TICKS = 40;

    private static boolean missing;
    private static Method getTree;
    private static Class<?> genericTree;
    private static Method isTreeStem;
    private static Method gatherLogs;
    private static Method gatherLeavesAroundLog;
    private static Method getCommonConfig;
    private static Class<?> treeEntity;

    private final Map<BlockPos, Set<BlockPos>> fallsWith = new HashMap<>();
    private long cachedAt = Long.MIN_VALUE / 2;

    private static boolean load() {
        if (missing) {
            return false;
        }
        if (getTree != null) {
            return true;
        }
        try {
            getTree = Class.forName("me.pandamods.fallingtrees.registry.TreeRegistry").getMethod("getTree", BlockState.class);
            genericTree = Class.forName("me.pandamods.fallingtrees.trees.GenericTree");
            isTreeStem = genericTree.getMethod("isTreeStem", BlockState.class);
            gatherLogs = genericTree.getDeclaredMethod("gatherLogs", Level.class, BlockPos.class);
            gatherLogs.setAccessible(true);
            gatherLeavesAroundLog = genericTree.getDeclaredMethod("gatherLeavesAroundLog", Level.class, BlockPos.class);
            gatherLeavesAroundLog.setAccessible(true);
            getCommonConfig = Class.forName("me.pandamods.fallingtrees.config.FallingTreesConfig").getMethod("getCommonConfig");
            treeEntity = Class.forName("me.pandamods.fallingtrees.entity.TreeEntity");
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            missing = true; // not installed (or changed beyond recognition)
            getTree = null;
            return false;
        }
    }

    /**
     * @return Whether breaking a log fells its tree for us: trees enabled, and an axe the mod accepts carried if it
     * wants one
     */
    static boolean fells(IPlayerContext ctx) {
        if (!load()) {
            return false;
        }
        try {
            Object common = getCommonConfig.invoke(null);
            Object trees = common.getClass().getField("trees").get(common);
            Object config = trees.getClass().getField("genericTree").get(trees);
            Class<?> configClass = config.getClass();
            if (!configClass.getField("enabled").getBoolean(config)) {
                return false;
            }
            if (!configClass.getField("requireTool").getBoolean(config)) {
                return true;
            }
            Object tools = configClass.getField("allowedToolFilter").get(config);
            Method valid = tools.getClass().getMethod("isValid", ItemStack.class);
            for (ItemStack stack : ctx.player().getInventory().items) {
                if (!stack.isEmpty() && (Boolean) valid.invoke(tools, stack)) {
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * @return The tree (wood) the mod fells this as part of, or null
     */
    private static Object treeOf(BlockState state) {
        try {
            Object tree = getTree.invoke(null, state);
            return genericTree.isInstance(tree) && (Boolean) isTreeStem.invoke(tree, state) ? tree : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Replaces the known logs by what to break for them: the base of the tree they fall with (a log standing on the
     * ground: not on another log, leaves or nothing). Logs of trees that don't fell (no leaves, too big) stay.
     */
    List<BlockPos> targets(Level level, List<BlockPos> known) {
        if (level.getGameTime() - cachedAt >= CACHE_TICKS) {
            fallsWith.clear();
            cachedAt = level.getGameTime();
        }
        Map<BlockPos, BlockPos> baseOf = new HashMap<>();
        Set<BlockPos> falling = new HashSet<>();
        for (BlockPos pos : known) {
            BlockState state = level.getBlockState(pos);
            Object tree = treeOf(state);
            if (tree == null) {
                continue;
            }
            BlockPos base = pos;
            while (treeOf(level.getBlockState(base.below())) != null) {
                base = base.below();
            }
            BlockState ground = level.getBlockState(base.below());
            if (ground.isAir() || ground.is(BlockTags.LEAVES) || !ground.getFluidState().isEmpty()) {
                continue; // a branch, or what's left hanging: resolved by the trunk's search below, else broken as it is
            }
            BlockPos found = base;
            Set<BlockPos> logs = fallsWith.computeIfAbsent(found, b -> fallingWith(tree, level, b));
            if (!logs.isEmpty()) {
                baseOf.put(found, found);
                falling.addAll(logs);
            }
        }
        Set<BlockPos> targets = new LinkedHashSet<>();
        for (BlockPos pos : known) {
            if (baseOf.containsKey(pos) || !falling.contains(pos)) {
                targets.add(pos);
            } else {
                // comes down with a base: break that instead (a 2x2 trunk's other bases fall with it too)
                baseOf.keySet().stream().filter(base -> fallsWith.get(base).contains(pos)).findFirst().ifPresent(targets::add);
            }
        }
        return new ArrayList<>(targets);
    }

    /**
     * @return The logs breaking the base brings down, the base among them; none if it doesn't fell (no leaves)
     */
    @SuppressWarnings("unchecked")
    private static Set<BlockPos> fallingWith(Object tree, Level level, BlockPos base) {
        try {
            Set<BlockPos> logs = (Set<BlockPos>) gatherLogs.invoke(tree, level, base);
            for (BlockPos log : logs) {
                if (!((Set<BlockPos>) gatherLeavesAroundLog.invoke(tree, level, log)).isEmpty()) {
                    return logs;
                }
            }
            return Collections.emptySet(); // no leaves: the mod leaves it standing, the log breaks as usual
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Collections.emptySet(); // too big to fell, as the mod finds too
        }
    }

    /**
     * @return Whether a tree is coming down within {@code distance} of the player: its drops land when it's done
     */
    static boolean fallingNear(IPlayerContext ctx, double distance) {
        if (!load()) {
            return false;
        }
        for (Entity entity : ctx.entities()) {
            if (treeEntity.isInstance(entity) && entity.distanceToSqr(ctx.player()) <= distance * distance) {
                return true;
            }
        }
        return false;
    }
}
