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
import baritone.api.IBaritone;
import baritone.api.schematic.ISchematic;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Getting about a build: the path never places blocks where the build wants something (the builder places those, the
 * right way round, and a path's block would be wrong as often as not), and never breaks what's already right. Pillars
 * and bridges go only where nothing is wanted, and are taken away in the cleanup.
 */
public final class BuildPathContext extends CalculationContext {

    private final ISchematic schematic;
    private final BlockPos origin;
    private final List<BlockState> placeable;

    public BuildPathContext(IBaritone baritone, ISchematic schematic, BlockPos origin, List<BlockState> placeable) {
        super(baritone, true);
        this.schematic = schematic;
        this.origin = origin;
        this.placeable = new ArrayList<>(placeable);
        this.jumpPenalty += 10;
        this.backtrackCostFavoringCoefficient = 1;
    }

    private BlockState desired(int x, int y, int z, BlockState current) {
        int rx = x - origin.getX();
        int ry = y - origin.getY();
        int rz = z - origin.getZ();
        if (!schematic.inSchematic(rx, ry, rz, current)) {
            return null;
        }
        return schematic.desiredState(rx, ry, rz, current, placeable);
    }

    @Override
    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        double cost = super.costOfPlacingAt(x, y, z, current);
        if (cost >= COST_INF) {
            return cost;
        }
        BlockState desired = desired(x, y, z, current);
        if (desired == null) {
            return cost;
        }
        if (desired.getBlock() instanceof AirBlock) {
            return cost * Baritone.settings().placeIncorrectBlockPenaltyMultiplier.value; // to be taken away again
        }
        return COST_INF;
    }

    @Override
    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        double multiplier = super.breakCostMultiplierAt(x, y, z, current);
        if (multiplier >= COST_INF) {
            return multiplier;
        }
        BlockState desired = desired(x, y, z, current);
        // nor what's only to be finished (a trapdoor to be opened, a slab to be doubled, a pot to have its plant)
        if (desired != null && !(desired.getBlock() instanceof AirBlock)
                && (BlockRules.valid(current, desired, false) || BlockRules.completable(current, desired))) {
            return COST_INF;
        }
        // nor the ground under the build: what's built (or to be) stands on it, or is placed against it
        BlockState above = desired == null ? desired(x, y + 1, z, Blocks.AIR.defaultBlockState()) : null;
        if (above != null && !(above.getBlock() instanceof AirBlock)) {
            return COST_INF;
        }
        return multiplier;
    }
}
