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

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

public class SurfaceCommand extends Command {

    protected SurfaceCommand(IBaritone baritone) {
        super(baritone, "surface", "top");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        final BetterBlockPos playerPos = ctx.playerFeet();
        final int bottom = playerPos.getY() + 1;

        // Walk the player's column from the build limit down, skipping air and tree canopy (leaves and logs),
        // and stop at the first real block: that is the surface above the player. If there is none, the
        // player already has open sky overhead. Unlike checking from sea level up, this works below sea level
        // (pits, ravines, excavations) and above it (mountains), and doesn't stop at the first cave ceiling.
        for (int y = ctx.world().getMaxBuildHeight() - 1; y >= bottom; y--) {
            final BlockState state = ctx.world().getBlockState(new BetterBlockPos(playerPos.getX(), y, playerPos.getZ()));
            if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
                continue;
            }
            // Surface water: stand in its top block; anything solid: stand on top of it.
            final int goalY = state.getFluidState().isEmpty() ? y + 1 : y;
            final Goal goal = new GoalBlock(playerPos.getX(), goalY, playerPos.getZ());
            logDirect(String.format("Going to: %s", goal.toString()));
            baritone.getCustomGoalProcess().setGoalAndPath(goal);
            return;
        }
        logDirect("Already at surface");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Used to get out of caves, mines, ...";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The surface/top command tells Baritone to head towards the closest surface-like area.",
                "",
                "This can be the surface or the highest available air space, depending on circumstances.",
                "",
                "Usage:",
                "> surface - Used to get out of caves, mines, ...",
                "> top - Used to get out of caves, mines, ..."
        );
    }
}
