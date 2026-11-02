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

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What bone meal grows, as the server sees it: Universal Bone Meal's behaviour for the blocks it makes bonemealable
 * (sugar cane, cactus, vines, nether wart, grown stems, ...) where it applies, else the block's own. The mod is
 * optional, reached by reflection.
 */
final class BoneMeal {

    private static boolean noUniversalBoneMeal;
    private static Method compileBehaviors;
    private static Field behaviors;

    private BoneMeal() {}

    /**
     * @return Whether bone meal on it grows something now: Universal Bone Meal's behaviour if it applies (its handler
     * runs first, and leaves the rest to the block's own), else the block's own
     */
    static boolean grows(LevelReader level, BlockPos pos, BlockState state) {
        BonemealableBlock universal = universalBehavior(state.getBlock());
        if (universal != null && universal.isValidBonemealTarget(level, pos, state, true)) {
            return true; // (e.g. a stem grown in full, that it makes fruit)
        }
        return state.getBlock() instanceof BonemealableBlock && ((BonemealableBlock) state.getBlock()).isValidBonemealTarget(level, pos, state, true);
    }

    /**
     * @return The blocks worth a farm's bone meal: what grows from it. Not ground (grass blocks, dirt, moss,
     * nylium), nor grass, fern or seagrass: on those it only spreads more of them about.
     */
    static List<Block> targets() {
        List<Block> targets = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if ((block instanceof BonemealableBlock || universalBehavior(block) != null) && !(block instanceof TallGrassBlock)
                    && !(block instanceof SeagrassBlock) && !block.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                targets.add(block);
            }
        }
        return targets;
    }

    private static BonemealableBlock universalBehavior(Block block) {
        if (noUniversalBoneMeal) {
            return null;
        }
        try {
            if (behaviors == null) {
                Class<?> handler = Class.forName("fuzs.universalbonemeal.handler.BonemealHandler");
                compileBehaviors = handler.getDeclaredMethod("dissolve");
                compileBehaviors.setAccessible(true);
                behaviors = handler.getDeclaredField("blockToBehavior");
                behaviors.setAccessible(true);
            }
            compileBehaviors.invoke(null); // (built from its config on first use, again after a config reload)
            Object behavior = ((Map<?, ?>) behaviors.get(null)).get(block);
            return behavior instanceof BonemealableBlock ? (BonemealableBlock) behavior : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            noUniversalBoneMeal = true; // not installed
            return null;
        }
    }
}
