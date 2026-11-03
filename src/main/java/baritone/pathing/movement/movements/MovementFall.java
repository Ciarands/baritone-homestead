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

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.Buckets;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.MovementState.MovementTarget;
import baritone.utils.pathing.MutableMoveResult;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.Vec3;

public class MovementFall extends Movement {

    public MovementFall(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, MovementFall.buildPositionsToBreak(src, dest));
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult result = new MutableMoveResult();
        MovementDescend.cost(context, src.x, src.y, src.z, dest.x, dest.z, result);
        if (result.y != dest.y) {
            return COST_INF; // doesn't apply to us, this position is a descend not a fall
        }
        return result.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        for (int y = src.y - dest.y; y >= 0; y--) {
            set.add(dest.above(y));
        }
        return set;
    }

    private boolean willPlaceBucket() {
        CalculationContext context = new CalculationContext(baritone);
        MutableMoveResult result = new MutableMoveResult();
        return MovementDescend.dynamicFallCost(context, src.x, src.y, src.z, dest.x, dest.z, 0, context.get(dest.x, src.y - 2, dest.z), result);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        BlockPos playerFeet = ctx.playerFeet();
        Rotation toDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations());
        Rotation targetRotation = null;
        BlockState destState = ctx.world().getBlockState(dest);
        Block destBlock = destState.getBlock();

        if (ctx.world().getBlockState(dest.below()).is(Blocks.MAGMA_BLOCK) && MovementHelper.steppingOnBlocks(ctx).stream().allMatch(block -> MovementHelper.canWalkThrough(ctx, block))) {
            state.setInput(Input.SNEAK, true);
        }

        boolean isWater = destState.getFluidState().getType() instanceof WaterFluid;
        if (!isWater && willPlaceBucket() && !playerFeet.equals(dest)) {
            int water = Buckets.waterSlot(ctx.player().getInventory());
            if (water < 0 || ctx.world().dimension() == Level.NETHER) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            if (ctx.player().position().y - dest.getY() < ctx.playerController().getBlockReachDistance() && !ctx.player().onGround()) {
                ctx.player().getInventory().selected = water;

                targetRotation = new Rotation(toDest.getYaw(), 90.0F);

                if (ctx.isLookingAt(dest) || ctx.isLookingAt(dest.below())) {
                    state.setInput(Input.CLICK_RIGHT, true);
                    placedWater = true;
                }
            }
        }

        if (cleanup != Cleanup.NONE) {
            return cleanUp(state);
        }
        if (!MovementHelper.openDoors(ctx, state, src, new BetterBlockPos(dest.x, src.y, dest.z))) {
            return state;
        }

        if (targetRotation != null) {
            state.setTarget(new MovementTarget(targetRotation, true));
        } else {
            state.setTarget(new MovementTarget(toDest, false));
        }
        if (playerFeet.equals(dest) && (ctx.player().position().y - playerFeet.getY() < 0.094 || isWater)) { // 0.094 because lilypads
            if (isWater) { // only match water, not flowing water (which we cannot pick up with a bucket)
                int empty = Buckets.emptySlot(ctx.player().getInventory());
                if (empty >= 0) {
                    ctx.player().getInventory().selected = empty;
                    if (ctx.player().getDeltaMovement().y >= 0) {
                        return state.setInput(Input.CLICK_RIGHT, true);
                    } else {
                        return state;
                    }
                } else {
                    if (ctx.player().getDeltaMovement().y >= 0) {
                        // nothing to take it back up with (a bucket that never empties): put a block in its place and
                        // break that, if there's one to use
                        if (placedWater && Baritone.settings().allowPlace.value && Baritone.settings().allowBreak.value
                                && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway()) {
                            cleanup = Cleanup.STEP_OUT;
                            cleanupSince = ctx.world().getGameTime();
                            return cleanUp(state);
                        }
                        return state.setStatus(MovementStatus.SUCCESS);
                    } // don't else return state; we need to stay centered because this water might be flowing under the surface
                }
            } else {
                return state.setStatus(MovementStatus.SUCCESS);
            }
        }
        Vec3 destCenter = VecUtils.getBlockPosCenter(dest); // we are moving to the 0.5 center not the edge (like if we were falling on a ladder)
        if (Math.abs(ctx.player().position().x + ctx.player().getDeltaMovement().x - destCenter.x) > 0.1 || Math.abs(ctx.player().position().z + ctx.player().getDeltaMovement().z - destCenter.z) > 0.1) {
            if (!ctx.player().onGround() && Math.abs(ctx.player().getDeltaMovement().y) > 0.4) {
                state.setInput(Input.SNEAK, true);
            }
            state.setInput(Input.MOVE_FORWARD, true);
        }
        Vec3i avoid = Optional.ofNullable(avoid()).map(Direction::getNormal).orElse(null);
        if (avoid == null) {
            avoid = src.subtract(dest);
        } else {
            double dist = Math.abs(avoid.getX() * (destCenter.x - avoid.getX() / 2.0 - ctx.player().position().x)) + Math.abs(avoid.getZ() * (destCenter.z - avoid.getZ() / 2.0 - ctx.player().position().z));
            if (dist < 0.6) {
                state.setInput(Input.MOVE_FORWARD, true);
            } else if (!ctx.player().onGround()) {
                state.setInput(Input.SNEAK, false);
            }
        }
        if (targetRotation == null) {
            Vec3 destCenterOffset = new Vec3(destCenter.x + 0.125 * avoid.getX(), destCenter.y, destCenter.z + 0.125 * avoid.getZ());
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), destCenterOffset, ctx.playerRotations()), false));
        }
        return state;
    }

    private Direction avoid() {
        for (int i = 0; i < 15; i++) {
            BlockState state = ctx.world().getBlockState(ctx.playerFeet().below(i));
            if (state.getBlock() instanceof LadderBlock && state.hasProperty(LadderBlock.FACING)) {
                return state.getValue(LadderBlock.FACING);
            }
        }
        return null;
    }

    private enum Cleanup {
        NONE, STEP_OUT, PLACE, BREAK
    }

    /**
     * Each step of the cleanup gets this long before it's left as it is
     */
    private static final int CLEANUP_TICKS = 100;

    private boolean placedWater;
    private Cleanup cleanup = Cleanup.NONE;
    private long cleanupSince;
    private BlockPos standOff;

    /**
     * Takes away the water put down to land in, when there's no empty bucket to take it back up with: steps out of
     * it, puts a block in its place and breaks that (the water that ran off it dries up by itself). Any step that
     * doesn't work out in time is given up and the water left
     */
    private MovementState cleanUp(MovementState state) {
        if (ctx.world().getGameTime() - cleanupSince > CLEANUP_TICKS) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        switch (cleanup) {
            case STEP_OUT: {
                if (standOff == null) {
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos next = dest.relative(dir);
                        if (MovementHelper.canWalkOn(ctx, next.below()) && MovementHelper.canWalkThrough(ctx, new BetterBlockPos(next))
                                && MovementHelper.canWalkThrough(ctx, new BetterBlockPos(next.above()))
                                && !ctx.world().getFluidState(next).isSource()) {
                            standOff = next;
                            break;
                        }
                    }
                    if (standOff == null) {
                        return state.setStatus(MovementStatus.SUCCESS);
                    }
                }
                Vec3 center = VecUtils.getBlockPosCenter(standOff);
                if (ctx.playerFeet().equals(standOff) && Math.abs(ctx.player().position().x - center.x) < 0.25
                        && Math.abs(ctx.player().position().z - center.z) < 0.25) {
                    next(Cleanup.PLACE);
                    return state;
                }
                state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), center, ctx.playerRotations()), false));
                return state.setInput(Input.MOVE_FORWARD, true);
            }
            case PLACE: {
                if (!ctx.world().getFluidState(dest).isSource() && !ctx.world().getBlockState(dest).canBeReplaced()) {
                    next(Cleanup.BREAK);
                    return state;
                }
                if (MovementHelper.attemptToPlaceABlock(state, baritone, dest, true, false) == MovementHelper.PlaceResult.READY_TO_PLACE) {
                    state.setInput(Input.CLICK_RIGHT, true);
                }
                return state.setStatus(MovementStatus.RUNNING);
            }
            case BREAK: {
                BlockState placed = ctx.world().getBlockState(dest);
                if (MovementHelper.canWalkThrough(ctx, new BetterBlockPos(dest))) {
                    return state.setStatus(MovementStatus.SUCCESS);
                }
                Optional<Rotation> reach = RotationUtils.reachable(ctx, dest);
                if (reach.isEmpty()) {
                    return state.setStatus(MovementStatus.SUCCESS);
                }
                MovementHelper.switchToBestToolFor(ctx, placed);
                state.setTarget(new MovementTarget(reach.get(), true));
                if (ctx.isLookingAt(dest)) {
                    state.setInput(Input.CLICK_LEFT, true);
                }
                return state;
            }
            default:
                return state.setStatus(MovementStatus.SUCCESS);
        }
    }

    private void next(Cleanup step) {
        cleanup = step;
        cleanupSince = ctx.world().getGameTime();
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // if we haven't started walking off the edge yet, or if we're in the process of breaking blocks before doing the fall
        // then it's safe to cancel this
        return ctx.playerFeet().equals(src) || state.getStatus() != MovementStatus.RUNNING;
    }

    private static BetterBlockPos[] buildPositionsToBreak(BetterBlockPos src, BetterBlockPos dest) {
        BetterBlockPos[] toBreak;
        int diffX = src.getX() - dest.getX();
        int diffZ = src.getZ() - dest.getZ();
        int diffY = Math.abs(src.getY() - dest.getY());
        toBreak = new BetterBlockPos[diffY + 2];
        for (int i = 0; i < toBreak.length; i++) {
            toBreak[i] = new BetterBlockPos(src.getX() - diffX, src.getY() + 1 - i, src.getZ() - diffZ);
        }
        return toBreak;
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        // only break if one of the first three needs to be broken
        // specifically ignore the last one which might be water
        for (int i = 0; i < 4 && i < positionsToBreak.length; i++) {
            if (!MovementHelper.canWalkThrough(ctx, positionsToBreak[i])) {
                return super.prepared(state);
            }
        }
        return true;
    }
}
