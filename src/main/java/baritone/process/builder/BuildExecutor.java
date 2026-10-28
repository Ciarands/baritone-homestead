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
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.PathingCommandContext;
import baritone.utils.accessor.IPlayerControllerMP;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Carries out {@link BuildPlanner} plans. For each step: if it can be done from where we are, do it, else go to where
 * the plan says to stand. Nothing is taken on trust: before a click the game's placement logic is asked what it would
 * place with the rotation actually sent and the world as it actually is, and a few ticks after, the world is checked
 * for it. Anything not as planned gets the plan made again from the world as it is.
 * <p>
 * Placing turns first and clicks the tick after: the server takes the facing from the last rotation it was sent, and
 * for some blocks (observers, pistons, ladders...) from the head rotation, which only follows a tick later (see
 * EXPERIMENTS.md in BaritoneSchematicExperiments). The click itself is the same packet a player's click sends; the
 * server only checks it's in reach, not that the face can be seen.
 * <p>
 * Getting nowhere is noticed and got out of, whatever the cause: no path to a place to stand (that place is left out),
 * a click that doesn't come out right (three times and that block is given up), plans made over and over with nothing
 * getting done, or nothing done at all for a minute (what was being done is given up).
 */
public final class BuildExecutor {

    public interface Log {

        /**
         * A line of this tick's decisions
         */
        void line(String line);

        /**
         * Something that happened
         */
        void event(String line);
    }

    /**
     * Eye to the centre of the clicked block, for doing it from where we are (the server allows 6, the plan 4.5)
     */
    private static final double ACT_REACH = 5.0;
    /**
     * Ticks after a click before the world is believed: the server's answer to a predicted placement takes a moment
     */
    private static final int CONFIRM_TICKS = 4;
    /**
     * Ticks after a click by which it has to show
     */
    private static final int VERIFY_TICKS = 20;
    private static final int MAX_FAILURES = 3;
    private static final int TURN_TICKS = 20;
    private static final int BREAK_TICKS = 300;
    private static final int MOVE_STALL_TICKS = 400;
    /**
     * Not moving at all for this long while pathing, and not mining the way through either: stuck in place (walking
     * into the edge of a slab or stair over and over), however far the stand
     */
    private static final int STUCK_TICKS = 100;
    private static final int STALL_TICKS = 1200;
    private static final int MAX_PLANS_WITHOUT_PROGRESS = 4;
    private static final int LITTER_MARGIN = 4;

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private final ISchematic schematic;
    private final BlockPos origin;
    private final BuildPlanner planner;
    private final Log log;
    /**
     * Cells around the build that were empty to begin with: throwaway blocks there later are the path's, to go
     */
    private final LongOpenHashSet emptyAround = new LongOpenHashSet();

    private BuildPlanner.Plan plan;
    private int stageIndex;
    private int stepIndex;
    private String replanWhy = "start";

    private BlockPos movingTo;
    private double closest;
    private long closestTick;
    private Vec3 stillAt;
    private long stillSince;
    private long lastMoveTick;
    private int failedCalcs;
    /**
     * Stands not got to, one after the other, with nothing done in between
     */
    private int unreachedInARow;
    private static final int MAX_UNREACHED_IN_A_ROW = 3;
    private BuildPathContext pathContext;
    private long pathContextTick;

    private BuildPlanner.Step turningFor;
    private long turningSince;
    private BlockPos breakingPos;
    private long breakingSince;
    private boolean wasHitting;

    private static final class Pending {

        final BuildPlanner.Step step;
        final long tick;

        Pending(BuildPlanner.Step step, long tick) {
            this.step = step;
            this.tick = tick;
        }
    }

    private final List<Pending> pending = new ArrayList<>();
    private final Map<BlockPos, Integer> failures = new HashMap<>();
    /**
     * How often each block has been placed and seen there: placing one can make another come off (it can't stay
     * next to it), and placing that again the first, round and round, with every placement looking like progress
     */
    private final Map<BlockPos, Integer> placements = new HashMap<>();
    /**
     * What was placed, and seen there, by position: something else there later that nobody broke was made of it by
     * the world (grass spreading onto dirt, powder hardening), and placing it again would only go the same way
     */
    private final Map<BlockPos, net.minecraft.world.level.block.Block> placedBlock = new HashMap<>();

    private boolean changedByTheWorld(BlockPos pos) {
        net.minecraft.world.level.block.Block placed = placedBlock.get(pos);
        BlockState real = ctx.world().getBlockState(pos);
        return placed != null && !real.isAir() && real.getFluidState().isEmpty() && real.getBlock() != placed;
    }

    private static final int MAX_PLACEMENTS = 3;
    private long lastProgressTick;
    private int confirmed;
    private int confirmedAtLastPlan = -1;
    private int plansWithoutProgress;
    /**
     * Cells of the current plan the world changed after they were placed, left as they are: their steps are skipped
     */
    private final Set<BlockPos> leftAlone = new HashSet<>();

    public BuildExecutor(Baritone baritone, ISchematic schematic, BlockPos origin, Log log) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.schematic = schematic;
        this.origin = origin;
        this.planner = new BuildPlanner(ctx, schematic, origin);
        this.log = log;
        this.lastProgressTick = ctx.world().getGameTime();
        recordEmptyAround();
    }

    public BuildPlanner planner() {
        return planner;
    }

    /**
     * Where the current stage of the plan stands, if any (for watching it)
     */
    public BlockPos currentStand() {
        return plan != null && stageIndex < plan.stages.size() ? plan.stages.get(stageIndex).stand : null;
    }

    public int confirmed() {
        return confirmed;
    }

    /**
     * Going on after a pause: whatever was left for want of materials is tried again
     */
    public void resume() {
        planner.retryMissing();
        lastProgressTick = ctx.world().getGameTime();
        // time paused (waiting for materials, say) isn't time spent getting nowhere
        plansWithoutProgress = 0;
        confirmedAtLastPlan = -1;
        replan("resumed");
    }

    private void replan(String why) {
        if (plan != null) {
            log.event("REPLAN: " + why);
        }
        plan = null;
        replanWhy = why;
        movingTo = null;
        turningFor = null;
        breakingPos = null;
    }

    /**
     * @return What to do this tick; null once there's nothing left that can be done
     */
    public PathingCommand tick(boolean calcFailed, boolean isSafeToCancel) {
        long now = ctx.world().getGameTime();
        verifyPending(now);
        // slow mining isn't a stall (it has its own deadline), nor is waiting to see a click come out (that too)
        boolean breakingInTime = !pending.isEmpty()
                || (breakingPos != null && now - breakingSince <= breakDeadline(ctx.world().getBlockState(breakingPos), breakingPos));
        if (now - lastProgressTick > STALL_TICKS && !breakingInTime) {
            BuildPlanner.Step step = plan == null ? null : current();
            log.event("WATCHDOG: nothing done for " + (now - lastProgressTick) + " ticks" + (step == null ? "" : "; giving up " + step));
            if (step != null) {
                planner.giveUp(step.pos, "nothing got done for a minute trying it");
            }
            lastProgressTick = now;
            replan("stalled");
        }
        if (plan == null) {
            if (!pending.isEmpty()) {
                return pause("waiting for " + pending.size() + " clicks to show before planning");
            }
            if (!makePlan()) {
                return null;
            }
        }
        BuildPlanner.Step step = current();
        if (step == null) {
            if (plan != null) {
                replan("plan done");
            }
            return pause("plan done or changed");
        }
        if (isPending(step.pos)) {
            return pause("waiting for the last click at " + p(step.pos) + " to show");
        }
        LocalPlayer player = ctx.player();
        boolean steady = (player.onGround() || player.onClimbable()) && (isSafeToCancel || !baritone.getPathingBehavior().isPathing());
        String notHere = notDoableFromHere(step);
        if (steady && notHere == null) {
            movingTo = null;
            return act(step, now);
        }
        BuildPlanner.Stage stage = plan.stages.get(stageIndex);
        if (steady && ctx.playerFeet().equals(stage.stand)) {
            Vec3 centre = Vec3.atBottomCenterOf(stage.stand);
            double off = Math.hypot(player.getX() - centre.x, player.getZ() - centre.z);
            if (off > 0.15) {
                // the plan works from the middle of the stand; the path can leave us at its edge
                if (centringSince < 0) {
                    centringSince = now;
                }
                if (now - centringSince < CENTRE_TICKS) {
                    baritone.getLookBehavior().updateTarget(new Rotation(
                            RotationUtils.calcRotationFromVec3d(player.getEyePosition(), centre.add(0, player.getEyeHeight(), 0), ctx.playerRotations()).getYaw(),
                            player.getXRot()), false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                    return pause(String.format("centring on %s (%.2f off) for %s: %s", p(stage.stand), off, step, notHere));
                }
            }
            centringSince = -1;
            // where the plan said, and it still can't be done: the plan was wrong about this one
            log.event("at the planned stand " + p(stage.stand) + String.format(" (%.2f off centre)", off) + " but " + step + " can't be done: " + notHere);
            planner.badStandFor(step.pos, stage.stand); // another stand for it next time
            fail(step, "can't be done from where the plan stands: " + notHere);
            return pause("planned stand doesn't work");
        }
        centringSince = -1;
        return moveTo(stage, calcFailed, now, notHere);
    }

    private static final int CENTRE_TICKS = 40;
    private long centringSince = -1;

    // ---- planning

    private boolean makePlan() {
        if (confirmed == confirmedAtLastPlan) {
            plansWithoutProgress++;
        } else {
            plansWithoutProgress = 0;
        }
        confirmedAtLastPlan = confirmed;
        for (int attempt = 0; attempt < 2; attempt++) {
            VirtualWorld world = new VirtualWorld(ctx);
            plan = planner.next(world, ctx.playerFeet(), approxPlaceable(), have(), throwaway(), litter());
            stageIndex = 0;
            leftAlone.clear();
            stepIndex = 0;
            log.event("PLAN (" + replanWhy + "): " + String.join("\n", plan.describe()));
            if (plan.isEmpty()) {
                // nothing to do isn't nothing working: no count towards giving up
                plansWithoutProgress = 0;
                plan = null;
                return false;
            }
            if (plansWithoutProgress < MAX_PLANS_WITHOUT_PROGRESS) {
                return true;
            }
            BuildPlanner.Step first = plan.stages.get(0).steps.get(0);
            log.event("WATCHDOG: " + plansWithoutProgress + " plans with nothing done in between; giving up " + first);
            planner.giveUp(first.pos, "planned over and over with nothing getting done");
            plansWithoutProgress = 0;
            replanWhy = "gave up on " + p(first.pos);
        }
        return plan != null;
    }

    /**
     * @return The next step of the plan not yet done, or null if the plan is done or doesn't fit the world any more
     * (then a new one is wanted)
     */
    private BuildPlanner.Step current() {
        while (plan != null && stageIndex < plan.stages.size()) {
            List<BuildPlanner.Step> steps = plan.stages.get(stageIndex).steps;
            while (stepIndex < steps.size()) {
                BuildPlanner.Step step = steps.get(stepIndex);
                if (leftAlone.contains(step.pos)) {
                    stepIndex++;
                    continue;
                }
                if (isPending(step.pos)) {
                    return step;
                }
                if (isDone(step)) {
                    stepIndex++;
                    continue;
                }
                if (step.kind == BuildPlanner.Kind.BREAK && changedByTheWorld(step.pos)) {
                    BlockState real = ctx.world().getBlockState(step.pos);
                    log.event("the " + placedBlock.get(step.pos).getName().getString() + " placed at " + p(step.pos) + " turned into " + s(real) + " by itself: leaving it");
                    planner.giveUp(step.pos, "turned into " + real.getBlock().getName().getString() + " by itself after it was placed");
                    // the rest of the plan stands: each step is checked against the world before it's done anyway
                    leftAlone.add(step.pos);
                    stepIndex++;
                    continue;
                }
                String why = impossible(step);
                if (why != null) {
                    replan(step + " can't be done any more: " + why);
                    return null;
                }
                return step;
            }
            stageIndex++;
            stepIndex = 0;
        }
        return null;
    }

    private boolean isDone(BuildPlanner.Step step) {
        BlockState real = ctx.world().getBlockState(step.pos);
        switch (step.kind) {
            case PLACE:
                return step.temporary ? !replaceable(step.pos, real) : places(real, step.result);
            case FINISH:
                return real.getBlock() == step.result.getBlock() && BlockRules.sameBlockstate(real, step.result, false);
            case BREAK:
                return real.isAir() || real.getBlock() instanceof LiquidBlock;
            case TOGGLE:
                return isToggledRight(real, step.result);
            case USE:
                return real.getBlock() == step.result.getBlock();
            default:
                return false;
        }
    }

    /**
     * @return Why it can't be done in the world as it is, or null if it can
     */
    private String impossible(BuildPlanner.Step step) {
        BlockState real = ctx.world().getBlockState(step.pos);
        switch (step.kind) {
            case PLACE: {
                if (!replaceable(step.pos, real)) {
                    return s(real) + " is there";
                }
                BlockState support = ctx.world().getBlockState(step.option.clicked);
                if (replaceable(step.option.clicked, support) || support.getShape(ctx.world(), step.option.clicked).isEmpty()) {
                    return "nothing at " + p(step.option.clicked) + " to place it against";
                }
                return null;
            }
            case FINISH:
                return BlockRules.needsSecondSlab(real, step.result) ? null : s(real) + " is there, not the single slab";
            case BREAK:
                if (real.getDestroySpeed(ctx.world(), step.pos) < 0) {
                    return "unbreakable";
                }
                return unsealedLiquid(step.pos);
            case TOGGLE:
                return real.hasProperty(BlockStateProperties.OPEN) ? null : s(real) + " is there";
            case USE:
                return BlockRules.needsUse(real, step.result) ? null : s(real) + " is there, not its base";
            default:
                return null;
        }
    }

    private static boolean places(BlockState real, BlockState planned) {
        return real.getBlock() == planned.getBlock() && BlockRules.sameBlockstate(real, planned, true);
    }

    private boolean replaceable(BlockPos pos, BlockState state) {
        return state.canBeReplaced() || MovementHelper.isReplaceable(pos.getX(), pos.getY(), pos.getZ(), state, baritone.bsi);
    }

    // ---- where we are

    /**
     * @return Why the step can't be done from where we are, or null if it can
     */
    private String notDoableFromHere(BuildPlanner.Step step) {
        LocalPlayer player = ctx.player();
        // placing is done sneaking: the eye is lower than when walking up
        Vec3 eye = step.kind == BuildPlanner.Kind.PLACE || step.kind == BuildPlanner.Kind.FINISH
                ? player.position().add(0, PlacementModel.SNEAK_EYE, 0) : player.getEyePosition();
        BlockPos clicked = step.option != null ? step.option.clicked : step.pos;
        double distance = eye.distanceTo(Vec3.atCenterOf(clicked));
        if (distance > ACT_REACH) {
            return String.format("out of reach (%.1f)", distance);
        }
        AABB body = player.getBoundingBox().inflate(0.05);
        for (BlockPos fill : step.fills) {
            if (body.intersects(new AABB(fill))) {
                return "we're in the way of " + p(fill);
            }
        }
        if (step.kind == BuildPlanner.Kind.BREAK && onlySupport(step.pos)) {
            return "standing on it";
        }
        return null;
    }

    /**
     * @return Whether {@code pos} is the only thing holding us up: under our feet, with nothing else under them to
     * stand on (overlapping the edge of a block while standing on the next one is fine, it can be broken)
     */
    private boolean onlySupport(BlockPos pos) {
        AABB box = ctx.player().getBoundingBox();
        int y = Mth.floor(box.minY - 0.01);
        if (pos.getY() != y || !(box.maxX > pos.getX() && box.minX < pos.getX() + 1 && box.maxZ > pos.getZ() && box.minZ < pos.getZ() + 1)) {
            return false;
        }
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1e-7); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1e-7); z++) {
                if ((x != pos.getX() || z != pos.getZ()) && MovementHelper.canWalkOn(baritone.bsi, x, y, z)) {
                    return false;
                }
            }
        }
        return !ctx.player().onClimbable();
    }

    private PathingCommand moveTo(BuildPlanner.Stage stage, boolean calcFailed, long now, String notHere) {
        BlockPos stand = stage.stand;
        double distance = ctx.player().position().distanceTo(Vec3.atBottomCenterOf(stand));
        if (!stand.equals(movingTo)) {
            movingTo = stand;
            closest = distance;
            closestTick = now;
            stillAt = null;
            failedCalcs = 0;
            log.event("going to " + p(stand) + " (" + stage.how + ") for " + (stage.steps.size() - stepIndex) + " steps, first " + current() + " (from here: " + notHere + ")");
        }
        if (distance < closest - 0.5) {
            closest = distance;
            closestTick = now;
        }
        Vec3 position = ctx.player().position();
        // standing still to do something on the way doesn't count: only ticks spent moving
        if (stillAt == null || now - lastMoveTick > 1 || position.distanceTo(stillAt) > 0.5 || !ctx.playerController().hasBrokenBlock()) {
            stillAt = position;
            stillSince = now;
        }
        lastMoveTick = now;
        if (calcFailed) {
            failedCalcs++;
        }
        boolean stuck = now - stillSince > STUCK_TICKS;
        if (failedCalcs >= 2 || now - closestTick > MOVE_STALL_TICKS || stuck) {
            planner.badStand(stand);
            if (++unreachedInARow >= MAX_UNREACHED_IN_A_ROW && !planner.isReachStrict()) {
                log.event("WATCHDOG: " + unreachedInARow + " stands in a row not got to: from now on only stands that can be walked, climbed or dropped to");
                planner.reachUnreliable();
            }
            log.event("can't get to " + p(stand) + "; around the player:\n" + surroundings());
            replan("can't get to " + p(stand) + (failedCalcs >= 2 ? ": no path"
                    : stuck ? ": stuck in place for " + (now - stillSince) + " ticks" : ": no closer for " + (now - closestTick) + " ticks"));
            return pause("no way to the stand");
        }
        if (pathContext == null || now - pathContextTick > 100 || !stand.equals(pathContextStand)) {
            pathContext = new BuildPathContext(baritone, schematic, origin, approxPlaceable());
            pathContextTick = now;
            pathContextStand = stand;
        }
        log.line(String.format("moving to %s (%s), %.1f away, closest %.1f %d ticks ago", p(stand), stage.how, distance, closest, now - closestTick));
        return new PathingCommandContext(new GoalBlock(stand), PathingCommandType.REVALIDATE_GOAL_AND_PATH, pathContext);
    }

    private BlockPos pathContextStand;

    // ---- doing it

    private PathingCommand act(BuildPlanner.Step step, long now) {
        switch (step.kind) {
            case PLACE:
            case FINISH:
                return place(step, now);
            case BREAK:
                return breakBlock(step, now);
            case TOGGLE:
                return toggle(step, now);
            case USE:
                return use(step, now);
            default:
                return pause("?");
        }
    }

    private PathingCommand place(BuildPlanner.Step step, long now) {
        LocalPlayer player = ctx.player();
        Item item = step.temporary ? throwawayItem() : step.option.item;
        int slot = item == null ? -1 : slotOf(item);
        if (slot < 0) {
            replan("no " + (item == null ? "throwaway blocks" : item) + " left for " + step);
            return pause("out of it");
        }
        if (slot >= 9) {
            boolean moved = baritone.getInventoryBehavior().attemptToPutOnHotbar(slot, s -> false);
            return pause("moving " + item + " from slot " + slot + " to the hotbar" + (moved ? "" : " (not yet)"));
        }
        player.getInventory().selected = slot;
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        ItemStack stack = player.getInventory().getItem(slot);
        PlacementModel model = planner.model();
        // the plan's click, in the world as it is now
        BlockState planned = model.simulate(ctx.world(), stack, step.option.hitResult(), step.option.yaw, step.option.pitch, step.pos);
        if (!(step.temporary ? planned != null : planned != null && places(planned, step.result))) {
            PlacementModel.Option other = resolve(step, stack);
            if (other == null) {
                fail(step, "the planned click would place " + s(planned) + " now, and no other click in reach places it right");
                return pause("no click places it right");
            }
            log.event("planned click for " + p(step.pos) + " would place " + s(planned) + " now; using " + other);
            step.option = other;
        }
        PlacementModel.Option option = step.option;
        baritone.getLookBehavior().updateTarget(new Rotation(nearYaw(option.yaw), option.pitch), true);
        if (turningFor != step) {
            turningFor = step;
            turningSince = now;
        }
        if (!player.isShiftKeyDown()) {
            return pause("turning to " + p(step.pos) + ", waiting for sneak");
        }
        // what the server knows: the rotation last sent to it (not the view, which free look keeps apart)
        Rotation sent = sentRotation();
        BlockState result = model.simulate(ctx.world(), stack, option.hitResult(), sent.getYaw(), sent.getPitch(), step.pos);
        boolean right = step.temporary ? result != null : result != null && places(result, step.result);
        if (!right) {
            if (now - turningSince > TURN_TICKS) {
                fail(step, String.format("turned for %d ticks and the click would still place %s (yaw %.1f pitch %.1f, wanted %.1f %.1f)",
                        now - turningSince, s(result), sent.getYaw(), sent.getPitch(), option.yaw, option.pitch));
                return pause("can't turn to it");
            }
            return pause(String.format("turning to %s: would place %s at yaw %.1f pitch %.1f", p(step.pos), s(result), sent.getYaw(), sent.getPitch()));
        }
        InteractionResult used = ctx.playerController().processRightClickBlock(player, ctx.world(), InteractionHand.MAIN_HAND, option.hitResult());
        log.event("clicked " + p(option.clicked) + " " + option.face + " with " + item + " for " + step.kind + " " + p(step.pos) + " " + s(step.result)
                + String.format(" from %s (yaw %.1f pitch %.1f): ", p(ctx.playerFeet()), player.getYRot(), player.getXRot()) + used);
        if (!used.consumesAction()) {
            fail(step, "the click wasn't used (" + used + ")");
            return pause("click not used");
        }
        player.swing(InteractionHand.MAIN_HAND);
        pending.add(new Pending(step, now));
        stepIndex++;
        turningFor = null;
        return pause("placed " + p(step.pos));
    }

    /**
     * @return Another click that places it right in the world as it is, in reach from where we are
     */
    private PlacementModel.Option resolve(BuildPlanner.Step step, ItemStack stack) {
        VirtualWorld world = new VirtualWorld(ctx);
        // what the item places in general when the planned result isn't an item's own block (bamboo places a sapling)
        BlockState want = step.temporary || PlacementModel.itemFor(step.result) == null
                ? ((BlockItem) stack.getItem()).getBlock().defaultBlockState() : step.result;
        Vec3 eye = ctx.player().getEyePosition();
        for (PlacementModel.Option option : planner.model().options(step.pos, want, world)) {
            if (eye.distanceTo(Vec3.atCenterOf(option.clicked)) <= ACT_REACH && (step.temporary || places(option.result, step.result))) {
                return option;
            }
        }
        return null;
    }

    private PathingCommand breakBlock(BuildPlanner.Step step, long now) {
        BlockPos pos = step.pos;
        BlockState state = ctx.world().getBlockState(pos);
        if (!pos.equals(breakingPos)) {
            breakingPos = pos;
            breakingSince = now;
            wasHitting = false;
        }
        if (now - breakingSince > breakDeadline(state, pos)) {
            breakingPos = null;
            fail(step, "still not broken after " + (now - breakingSince) + " ticks");
            return pause("can't break it");
        }
        MovementHelper.switchToBestToolFor(ctx, state);
        // starting to break doesn't tell the server about a new slot (placing and continuing to break do): it would
        // break with what was held before, slower than the client predicts, and the predicted break gets undone
        ctx.playerController().syncHeldItem();
        Vec3 eye = ctx.player().getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, center, ctx.playerRotations()), true);
        Direction face = Direction.getNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
        // as the block break helper does it: the client stops a break it thinks the player isn't making, so it's told
        // we're not, between ticks
        ctx.playerController().setHittingBlock(wasHitting);
        if (wasHitting) {
            ctx.playerController().onPlayerDamageBlock(pos, face);
        } else {
            ctx.playerController().clickBlock(pos, face);
        }
        ctx.player().swing(InteractionHand.MAIN_HAND);
        boolean broken = ctx.playerController().hasBrokenBlock();
        wasHitting = !broken;
        ctx.playerController().setHittingBlock(false);
        if (broken) {
            ((IPlayerControllerMP) ctx.minecraft().gameMode).setDestroyDelay(0);
        }
        if (isDone(step)) {
            log.event("broke " + p(pos) + " " + s(state) + " from " + p(ctx.playerFeet()) + " in " + (now - breakingSince + 1) + " ticks");
            pending.add(new Pending(step, now));
            stepIndex++;
            breakingPos = null;
            return pause("broke " + p(pos));
        }
        return pause("breaking " + p(pos) + " " + s(state));
    }

    private Rotation sentRotation() {
        return baritone.getLookBehavior().getEffectiveRotation().orElse(ctx.playerRotations());
    }

    /**
     * Uses the item that finishes the block on it (a plant put in a pot): not sneaking, holding the item
     */
    private PathingCommand use(BuildPlanner.Step step, long now) {
        LocalPlayer player = ctx.player();
        Item item = BlockRules.useItem(step.result);
        int slot = item == null ? -1 : slotOf(item);
        if (slot < 0) {
            replan("no " + item + " left for " + step);
            return pause("out of it");
        }
        if (slot >= 9) {
            boolean moved = baritone.getInventoryBehavior().attemptToPutOnHotbar(slot, s -> false);
            return pause("moving " + item + " from slot " + slot + " to the hotbar" + (moved ? "" : " (not yet)"));
        }
        player.getInventory().selected = slot;
        if (player.isShiftKeyDown()) {
            return pause("waiting to stop sneaking to use " + item + " on " + p(step.pos));
        }
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(step.pos);
        baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(eye, center, ctx.playerRotations()), true);
        if (turningFor != step) {
            turningFor = step;
            turningSince = now;
            return pause("turning to " + p(step.pos) + " to use " + item + " on it");
        }
        Direction face = Direction.getNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
        BlockHitResult hit = new BlockHitResult(center.add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), face, step.pos, false);
        InteractionResult used = ctx.playerController().processRightClickBlock(player, ctx.world(), InteractionHand.MAIN_HAND, hit);
        log.event("clicked " + p(step.pos) + " with " + item + " to make it " + s(step.result) + ": " + used);
        turningFor = null;
        if (!used.consumesAction()) {
            fail(step, "the click wasn't used (" + used + ")");
            return pause("click not used");
        }
        player.swing(InteractionHand.MAIN_HAND);
        pending.add(new Pending(step, now));
        stepIndex++;
        return pause("used " + item + " on " + p(step.pos));
    }

    private PathingCommand toggle(BuildPlanner.Step step, long now) {
        LocalPlayer player = ctx.player();
        // not sneaking, or the click would place what's in hand instead of using the block
        if (player.isShiftKeyDown()) {
            return pause("waiting to stop sneaking to open/close " + p(step.pos));
        }
        // the rotation matters for some (a gate opens away from whoever opens it): one that comes out as wanted,
        // worked out with the game's own logic
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(step.pos);
        Direction face = Direction.getNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
        BlockHitResult hit = new BlockHitResult(center.add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), face, step.pos, false);
        Rotation look = RotationUtils.calcRotationFromVec3d(eye, center, ctx.playerRotations());
        Float yaw = toggleYaw(step, hit, look.getYaw());
        if (yaw == null) {
            fail(step, "no way of turning makes using it come out as wanted");
            return pause("can't open/close it right");
        }
        baritone.getLookBehavior().updateTarget(new Rotation(nearYaw(yaw), look.getPitch()), true);
        if (turningFor != step) {
            turningFor = step;
            turningSince = now;
            return pause("turning to " + p(step.pos) + " to open/close it");
        }
        if (toggled(step, hit, sentRotation().getYaw()) == null) {
            if (now - turningSince > TURN_TICKS) {
                fail(step, String.format("turned for %d ticks and using it still wouldn't come out right (yaw %.1f, wanted %.1f)", now - turningSince, sentRotation().getYaw(), yaw));
                return pause("can't turn to it");
            }
            return pause(String.format("turning to %s to open/close it: yaw %.1f, wanted %.1f", p(step.pos), sentRotation().getYaw(), yaw));
        }
        InteractionResult used = ctx.playerController().processRightClickBlock(player, ctx.world(), InteractionHand.MAIN_HAND, hit);
        log.event("clicked " + p(step.pos) + " to open/close it: " + used);
        turningFor = null;
        if (!used.consumesAction()) {
            fail(step, "the click wasn't used (" + used + ")");
            return pause("click not used");
        }
        player.swing(InteractionHand.MAIN_HAND);
        pending.add(new Pending(step, now));
        stepIndex++;
        return pause("opened/closed " + p(step.pos));
    }

    /**
     * @return A yaw from which using the block comes out as the step wants, the nearest to {@code preferred}; null if
     * none does
     */
    private Float toggleYaw(BuildPlanner.Step step, BlockHitResult hit, float preferred) {
        if (toggled(step, hit, preferred) != null) {
            return preferred;
        }
        Float best = null;
        for (int i = 0; i < 16; i++) {
            float yaw = i * 22.5f;
            if (toggled(step, hit, yaw) != null && (best == null || Math.abs(Mth.wrapDegrees(yaw - preferred)) < Math.abs(Mth.wrapDegrees(best - preferred)))) {
                best = yaw;
            }
        }
        return best;
    }

    /**
     * @return What using the block turned to {@code yaw} makes it, if that's what the step wants; else null
     */
    private BlockState toggled(BuildPlanner.Step step, BlockHitResult hit, float yaw) {
        VirtualWorld world = new VirtualWorld(ctx);
        LocalPlayer player = ctx.player();
        float yaw0 = player.getYRot();
        try {
            player.setYRot(yaw);
            world.get(step.pos).use(world.level(), player, InteractionHand.MAIN_HAND, hit);
        } catch (RuntimeException e) {
            log.line("simulating using " + p(step.pos) + " failed: " + e);
            return null; // something about it the planning world can't show
        } finally {
            player.setYRot(yaw0);
        }
        BlockState after = world.get(step.pos);
        return isToggledRight(after, step.result) ? after : null;
    }

    private static boolean isToggledRight(BlockState state, BlockState wanted) {
        return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN) == wanted.getValue(BlockStateProperties.OPEN)
                && BlockRules.sameBlockstate(state, wanted, true);
    }

    // ---- checking it

    private boolean isPending(BlockPos pos) {
        for (Pending p : pending) {
            if (p.step.pos.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    private void verifyPending(long now) {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            BlockState real = ctx.world().getBlockState(p.step.pos);
            // broken, and what was above fell in, as planned
            boolean done = isDone(p.step) || (p.step.kind == BuildPlanner.Kind.BREAK && !p.step.result.isAir() && real.getBlock() == p.step.result.getBlock());
            if (done && now - p.tick >= CONFIRM_TICKS + latencyTicks()) {
                it.remove();
                confirmed++;
                lastProgressTick = now;
                failures.remove(p.step.pos);
                unreachedInARow = 0;
                log.line("confirmed " + p.step.kind + " " + p(p.step.pos) + " after " + (now - p.tick) + " ticks");
                if (p.step.kind == BuildPlanner.Kind.PLACE && !p.step.temporary) {
                    placedBlock.put(p.step.pos, ctx.world().getBlockState(p.step.pos).getBlock());
                }
                if (p.step.kind == BuildPlanner.Kind.PLACE && !p.step.temporary
                        && placements.merge(p.step.pos, 1, Integer::sum) >= MAX_PLACEMENTS) {
                    log.event("placed " + p(p.step.pos) + " " + MAX_PLACEMENTS + " times now: it keeps coming off, leaving it");
                    planner.giveUp(p.step.pos, "keeps coming off: placed " + MAX_PLACEMENTS + " times");
                }
            } else if (!done && now - p.tick >= VERIFY_TICKS + 2 * latencyTicks()) {
                it.remove();
                fail(p.step, (now - p.tick) + " ticks after, " + s(ctx.world().getBlockState(p.step.pos)) + " is there");
            }
        }
    }

    /**
     * @return The round trip to the server in ticks: what the server says about a click takes that long to come back
     */
    private int latencyTicks() {
        net.minecraft.client.multiplayer.PlayerInfo info = ctx.player().connection.getPlayerInfo(ctx.player().getUUID());
        return info == null ? 0 : Math.min(100, info.getLatency() / 50);
    }

    /**
     * @return How long breaking {@code state} may take: a few times what the tool in hand, effects and footing make it,
     * so slow mining isn't taken for being stuck
     */
    private long breakDeadline(BlockState state, BlockPos pos) {
        float perTick = state.getDestroyProgress(ctx.player(), ctx.world(), pos);
        long expected = perTick <= 0 ? BREAK_TICKS : (long) Math.ceil(1 / perTick);
        return Math.max(BREAK_TICKS, 3 * expected + 40);
    }

    /**
     * @return Why breaking {@code pos} would let liquid in (liquid next to it the build doesn't want there), or null
     */
    private String unsealedLiquid(BlockPos pos) {
        VirtualWorld world = null;
        for (Direction dir : Direction.values()) {
            BlockPos next = pos.relative(dir);
            if (dir == Direction.DOWN || ctx.world().getFluidState(next).isEmpty()) {
                continue;
            }
            if (world == null) {
                world = new VirtualWorld(ctx);
            }
            if (!planner.liquidWanted(next, world)) {
                return "liquid at " + p(next) + " would come in";
            }
        }
        return null;
    }

    private void fail(BuildPlanner.Step step, String why) {
        int count = failures.merge(step.pos, 1, Integer::sum);
        log.event("FAILED (" + count + "/" + MAX_FAILURES + ") " + step + ": " + why);
        if (count >= MAX_FAILURES) {
            planner.giveUp(step.pos, why);
            failures.remove(step.pos);
        }
        replan("failed: " + step.kind + " " + p(step.pos));
    }

    // ---- inventory and surroundings

    private int slotOf(Item item) {
        List<ItemStack> items = ctx.player().getInventory().items;
        int limit = Baritone.settings().allowInventory.value ? items.size() : 9;
        for (int i = 0; i < limit; i++) {
            if (items.get(i).getItem() == item && !items.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private Set<Item> have() {
        Set<Item> have = new HashSet<>();
        List<ItemStack> items = ctx.player().getInventory().items;
        int limit = Baritone.settings().allowInventory.value ? items.size() : 9;
        for (int i = 0; i < limit; i++) {
            if (!items.get(i).isEmpty()) {
                have.add(items.get(i).getItem());
            }
        }
        return have;
    }

    private List<BlockState> approxPlaceable() {
        List<BlockState> result = new ArrayList<>();
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                result.add(((BlockItem) stack.getItem()).getBlock().defaultBlockState());
            }
        }
        return result;
    }

    private Item throwawayItem() {
        for (Item item : Baritone.settings().acceptableThrowawayItems.value) {
            if (item instanceof BlockItem && slotOf(item) >= 0) {
                return item;
            }
        }
        return null;
    }

    private BlockState throwaway() {
        Item item = throwawayItem();
        return item == null ? null : ((BlockItem) item).getBlock().defaultBlockState();
    }

    private void forEachAround(java.util.function.Consumer<BlockPos> action) {
        for (int x = -LITTER_MARGIN; x < schematic.widthX() + LITTER_MARGIN; x++) {
            for (int y = -LITTER_MARGIN; y < schematic.heightY() + LITTER_MARGIN; y++) {
                for (int z = -LITTER_MARGIN; z < schematic.lengthZ() + LITTER_MARGIN; z++) {
                    if (x >= 0 && x < schematic.widthX() && y >= 0 && y < schematic.heightY() && z >= 0 && z < schematic.lengthZ()) {
                        continue; // the build's own cells: the schematic says what goes there
                    }
                    action.accept(origin.offset(x, y, z));
                }
            }
        }
    }

    private void recordEmptyAround() {
        forEachAround(pos -> {
            BlockState state = ctx.world().getBlockState(pos);
            if (state.isAir() || replaceable(pos, state)) {
                emptyAround.add(BetterBlockPos.longHash(pos.getX(), pos.getY(), pos.getZ()));
            }
        });
    }

    /**
     * @return Throwaway blocks around the build where it was empty to begin with
     */
    private List<BlockPos> litter() {
        List<BlockPos> found = new ArrayList<>();
        List<Item> throwaways = Baritone.settings().acceptableThrowawayItems.value;
        forEachAround(pos -> {
            if (emptyAround.contains(BetterBlockPos.longHash(pos.getX(), pos.getY(), pos.getZ()))
                    && throwaways.contains(ctx.world().getBlockState(pos).getBlock().asItem())) {
                found.add(pos.immutable());
            }
        });
        return found;
    }

    // ---- how it came out

    /**
     * How the build came out, checked cell by cell against the schematic exactly, as Litematica's verifier does: what
     * the builder accepts as right while building (connections, waterlogging and the like, which the world sets) is
     * counted apart, so "done" never claims more than is there
     */
    public static final class Audit {

        public int total;
        public int exact;
        /**
         * Cells not exactly as in the schematic (and blocks left around it), by reason
         */
        public final Map<String, List<BlockPos>> notExact = new TreeMap<>();
    }

    public Audit audit() {
        Audit audit = new Audit();
        List<BlockState> placeable = approxPlaceable();
        Map<BlockPos, String> givenUp = planner.unbuildable();
        forEachCell(pos -> {
            BlockState current = ctx.world().getBlockState(pos);
            BlockState desired = desired(pos, current, placeable);
            if (desired == null) {
                return;
            }
            audit.total++;
            if (current.equals(desired) || (current.isAir() && desired.isAir())) {
                audit.exact++;
                return;
            }
            String why;
            if (givenUp.containsKey(pos)) {
                why = givenUp.get(pos);
            } else if (BlockRules.valid(current, desired, false) && current.getBlock() == desired.getBlock()) {
                why = "differs only in what the world sets or the builder can't (" + differingProperties(current, desired) + ")";
            } else {
                why = current.getBlock() == desired.getBlock()
                        ? "not as in the schematic (" + differingProperties(current, desired) + ")"
                        : "is " + current.getBlock().getName().getString() + " instead of " + desired.getBlock().getName().getString();
            }
            audit.notExact.computeIfAbsent(why, k -> new ArrayList<>()).add(pos.immutable());
        });
        givenUp.forEach((pos, why) -> {
            BlockPos rel = pos.subtract(origin);
            boolean inside = rel.getX() >= 0 && rel.getX() < schematic.widthX() && rel.getY() >= 0 && rel.getY() < schematic.heightY()
                    && rel.getZ() >= 0 && rel.getZ() < schematic.lengthZ();
            if (!inside) {
                audit.notExact.computeIfAbsent(why, k -> new ArrayList<>()).add(pos);
            }
        });
        return audit;
    }

    private static String differingProperties(BlockState current, BlockState desired) {
        List<String> names = new ArrayList<>();
        for (net.minecraft.world.level.block.state.properties.Property<?> prop : current.getProperties()) {
            if (!current.getValue(prop).equals(desired.getValue(prop))) {
                names.add(prop.getName() + " " + current.getValue(prop) + " not " + desired.getValue(prop));
            }
        }
        return String.join(", ", names);
    }

    // ---- planning the whole build, without doing it

    /**
     * Plans the whole build as if every step worked, with every item the schematic wants to hand
     *
     * @return The plans, each step, what would be left not right and why, and a map of each layer as it would end up
     */
    public List<String> simulate() {
        long start = System.nanoTime();
        BuildPlanner sim = new BuildPlanner(ctx, schematic, origin);
        VirtualWorld world = new VirtualWorld(ctx);
        List<BlockState> placeable = approxPlaceable();
        Set<Item> items = have();
        forEachCell(pos -> {
            BlockState desired = desired(pos, world.get(pos), placeable);
            if (desired != null && PlacementModel.itemFor(desired) != null) {
                items.add(PlacementModel.itemFor(desired));
                if (BlockRules.useItem(desired) != null) {
                    items.add(BlockRules.useItem(desired));
                }
            }
        });
        BlockState throwaway = throwaway();
        if (throwaway == null) {
            throwaway = net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState();
        }
        BlockPos at = ctx.playerFeet();
        List<String> steps = new ArrayList<>();
        List<String> plans = new ArrayList<>();
        int stands = 0;
        int stepCount = 0;
        Map<String, Integer> how = new TreeMap<>();
        Map<BlockPos, Integer> placed = new HashMap<>();
        for (int i = 0; i < 10000; i++) {
            long planStart = System.nanoTime();
            BuildPlanner.Plan plan = sim.next(world, at, placeable, items, throwaway, Collections.emptyList());
            log.event("simulated plan " + i + ": " + plan.summary() + " | " + sim.model().stats() + " | " + (System.nanoTime() - planStart) / 1_000_000 + "ms");
            if (plan.isEmpty()) {
                break;
            }
            plans.add(plan.summary());
            steps.addAll(plan.describe());
            stands += plan.stages.size();
            stepCount += plan.steps();
            for (BuildPlanner.Stage stage : plan.stages) {
                how.merge(stage.how.split(" ")[0], 1, Integer::sum);
                for (BuildPlanner.Step step : stage.steps) {
                    if (step.kind == BuildPlanner.Kind.PLACE && !step.temporary && placed.merge(step.pos, 1, Integer::sum) >= MAX_PLACEMENTS) {
                        sim.giveUp(step.pos, "keeps coming off: placed " + MAX_PLACEMENTS + " times");
                    }
                }
            }
            at = plan.stages.get(plan.stages.size() - 1).stand;
        }
        int[] counts = new int[2];
        List<String> wrong = new ArrayList<>();
        forEachCell(pos -> {
            BlockState desired = desired(pos, world.get(pos), placeable);
            if (desired == null) {
                return;
            }
            if (BlockRules.valid(world.get(pos), desired, false)) {
                counts[0]++;
            } else {
                counts[1]++;
                String why = sim.unbuildable().getOrDefault(pos, "?");
                wrong.add("  " + p(pos) + " " + s(desired) + " (is " + s(world.get(pos)) + "): " + why
                        + (why.startsWith("nothing to place") ? " | " + sim.model().explain(pos, desired, world) : ""));
            }
        });
        List<String> out = new ArrayList<>();
        out.add("Plan for " + schematic.widthX() + "x" + schematic.heightY() + "x" + schematic.lengthZ() + " at " + p(origin) + ", from " + p(ctx.playerFeet()));
        out.add(String.format("%d plans, %d steps from %d stands %s, %d temporary blocks left; %d cells right, %d not; planned in %dms",
                plans.size(), stepCount, stands, how, sim.temporary().size(), counts[0], counts[1], (System.nanoTime() - start) / 1_000_000));
        out.add("");
        out.add("Not right in the end (" + wrong.size() + "):");
        out.addAll(wrong);
        out.add("Left in place around the build (" + sim.unbuildable().keySet().stream().filter(pos -> desired(pos, world.get(pos), placeable) == null).count() + "):");
        sim.unbuildable().forEach((pos, why) -> {
            if (desired(pos, world.get(pos), placeable) == null) {
                out.add("  " + p(pos) + " " + s(world.get(pos)) + ": " + why);
            }
        });
        out.add("");
        out.add("Placement model: " + sim.model().stats());
        out.add("Problems working out placements (" + sim.problems().size() + "):");
        sim.problems().forEach(problem -> out.add("  " + problem));
        out.add("");
        out.add("Plans:");
        out.addAll(plans);
        out.add("");
        out.add("Layers as they'd end up (# right, . right and empty, X not right, T temporary left; x across, z down):");
        for (int y = 0; y < schematic.heightY(); y++) {
            out.add("y=" + (origin.getY() + y) + ":");
            for (int z = 0; z < schematic.lengthZ(); z++) {
                StringBuilder row = new StringBuilder("  ");
                for (int x = 0; x < schematic.widthX(); x++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = world.get(pos);
                    BlockState desired = desired(pos, state, placeable);
                    row.append(desired == null ? ' ' : sim.temporary().contains(pos) && !state.isAir() ? 'T'
                            : !BlockRules.valid(state, desired, false) ? 'X' : desired.isAir() ? '.' : '#');
                }
                out.add(row.toString());
            }
        }
        out.add("");
        out.add("Steps:");
        out.addAll(steps);
        return out;
    }

    private BlockState desired(BlockPos pos, BlockState current, List<BlockState> placeable) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        return schematic.inSchematic(x, y, z, current) ? schematic.desiredState(x, y, z, current, placeable) : null;
    }

    private void forEachCell(java.util.function.Consumer<BlockPos> action) {
        for (int y = 0; y < schematic.heightY(); y++) {
            for (int x = 0; x < schematic.widthX(); x++) {
                for (int z = 0; z < schematic.lengthZ(); z++) {
                    action.accept(origin.offset(x, y, z));
                }
            }
        }
    }

    // ---- helpers

    private float nearYaw(float yaw) {
        float current = ctx.player().getYRot();
        return current + Mth.wrapDegrees(yaw - current);
    }

    private PathingCommand pause(String why) {
        log.line(why);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    static String p(BlockPos pos) {
        return pos == null ? "-" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    static String s(BlockState state) {
        return state == null ? "nothing" : state.toString().replace("Block{minecraft:", "").replace("}", "");
    }

    /**
     * @return The blocks round the player, a layer at a time from below the feet up (x across, z down, '@' the feet),
     * for working out from the log why it couldn't get anywhere
     */
    private String surroundings() {
        BlockPos feet = ctx.playerFeet();
        Map<Block, Character> legend = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder();
        for (int dy = -3; dy <= 2; dy++) {
            out.append("  y=").append(feet.getY() + dy).append('\n');
            for (int dz = -2; dz <= 2; dz++) {
                out.append("    ");
                for (int dx = -2; dx <= 2; dx++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    Block block = ctx.world().getBlockState(pos).getBlock();
                    char c = block instanceof AirBlock ? '.' : legend.computeIfAbsent(block, b -> (char) ('a' + legend.size()));
                    out.append(dx == 0 && dy == 0 && dz == 0 ? '@' : c);
                }
                out.append('\n');
            }
        }
        legend.forEach((block, c) -> out.append("  ").append(c).append(' ').append(block.getName().getString()).append('\n'));
        return out.toString();
    }
}
