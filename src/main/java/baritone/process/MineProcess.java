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

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.*;
import baritone.api.process.IMineProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.cache.CachedChunk;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.Avoidance;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Mine blocks of a certain type
 *
 * @author leijurv
 */
public final class MineProcess extends BaritoneProcessHelper implements IMineProcess {

    private BlockOptionalMetaLookup filter;
    private List<BlockPos> knownOreLocations;
    private List<BlockPos> blacklist; // inaccessible from blacklistOrigin
    private BlockPos blacklistOrigin; // where the blacklisted locations were found unreachable
    private Map<BlockPos, Integer> unreachableCount; // how many failed searches each location was part of, this run
    private int failuresWithNothingKnown; // consecutive failed searches with no known targets left: we can't move at all
    private List<BlockPos> collectingDrops; // the drops we're heading for, or null when heading for targets
    private Map<BlockPos, Long> anticipatedDrops;
    private BlockPos branchPoint;
    private GoalRunAway branchPointRunaway;
    private int desiredQuantity;
    private int depositedTargets; // target items deposited into the wireless terminal: they still count towards desiredQuantity
    private int targetsBeforeDeposit;
    private int tickCount;
    private final WirelessTerminalDeposit deposit;
    /**
     * Failed searches after which a location stays blacklisted for the rest of the run
     */
    private static final int MAX_UNREACHABLE = 3;
    private final DropObserver drops;

    public MineProcess(Baritone baritone) {
        super(baritone);
        this.deposit = new WirelessTerminalDeposit(ctx);
        this.drops = new DropObserver(ctx);
    }

    @Override
    public boolean isActive() {
        return filter != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        drops.tick(filter::has, filter::has);
        if (desiredQuantity > 0) {
            int curr = targetCount() + depositedTargets;
            if (curr >= desiredQuantity) {
                logDirect("Have " + curr + " valid items");
                cancel();
                return null;
            }
        }
        boolean canDeposit = Baritone.settings().depositToTomsSimpleStorageWirelessTerminal.value;
        boolean noFreeSlot = ctx.player().getInventory().getFreeSlot() == -1;
        // With somewhere to deposit, go as soon as no slot is free: from then on, any target we don't already hold a
        // part stack of can't be picked up, so mining it is wasted. Without, keep going while any target still fits.
        if (deposit.inProgress() || (canDeposit ? noFreeSlot : inventoryFull())) {
            String stopReason = null;
            if (!canDeposit) {
                // nothing mined from here on can be picked up; continuing only chases uncollectable drops in a loop
                stopReason = "Inventory full, no room for " + filter;
            } else if (!deposit.inProgress() && !(isSafeToCancel && ctx.player().onGround())) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE); // finish the current movement first
            } else {
                if (!deposit.inProgress()) {
                    targetsBeforeDeposit = targetCount();
                }
                baritone.getInputOverrideHandler().clearAllKeys();
                switch (deposit.tick(this::isTargetDrop)) {
                    case RUNNING:
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    case FAILED:
                        stopReason = "Inventory full, couldn't deposit into the wireless terminal: " + deposit.failure();
                        break;
                    case DONE:
                        int deposited = targetsBeforeDeposit - targetCount();
                        depositedTargets += deposited;
                        if (ctx.player().getInventory().getFreeSlot() == -1) {
                            stopReason = "Inventory still full after depositing into the wireless terminal";
                        } else {
                            long free = ctx.player().getInventory().items.stream().filter(ItemStack::isEmpty).count();
                            logDirect("Deposited into the wireless terminal: " + deposited + " mined items, " + free + " slots free now");
                        }
                        break;
                }
            }
            if (stopReason != null) {
                logDirect(stopReason + ". Stopping.");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification(stopReason + ", stopping mining", true);
                }
                cancel();
                return null;
            }
        }
        // Locations blacklisted as unreachable were only unreachable from where we stood. Once we've moved away,
        // give them another chance, otherwise reachable targets are thrown away for good; but not endlessly: one
        // that failed from several places is sealed off (in someone's wall, encased), and each retry costs a search.
        if (blacklistOrigin != null && !blacklist.isEmpty() && ctx.playerFeet().distSqr(blacklistOrigin) > 16) {
            // a new list: rescans on another thread may be reading the old one
            blacklist = blacklist.stream().filter(pos -> unreachableCount.getOrDefault(pos, 0) >= MAX_UNREACHABLE).collect(Collectors.toCollection(ArrayList::new));
            blacklistOrigin = null;
        }
        if (calcFailed && collectingDrops != null) {
            // it was the drops we couldn't get to: leave them, as with unreachable targets
            blacklist.addAll(collectingDrops);
            collectingDrops.forEach(pos -> unreachableCount.merge(pos, 1, Integer::sum));
            blacklistOrigin = ctx.playerFeet();
            collectingDrops = null;
        } else if (calcFailed) {
            if (knownOreLocations.isEmpty() && ++failuresWithNothingKnown >= 5) {
                // Not even exploring can move us: boxed in (e.g. on a ledge with nothing to place). Stop instead of
                // failing every tick.
                logDirect("Unable to find any path to " + filter + " and unable to move from here. Stopping.");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification("Mining stuck: unable to move from here", true);
                }
                cancel();
                return null;
            }
            logDirect("Unable to find any path to " + filter + ", blacklisting unreachable locations...");
            if (Baritone.settings().notificationOnMineFail.value) {
                logNotification("Unable to find any path to " + filter + ", blacklisting unreachable locations...", true);
            }
            // The failed search targeted all known locations at once, so none of them can be walked to from here.
            // Blacklist them all (one at a time repeats this ~2 s failure per location), except the ones within
            // arm's reach: those are mined directly below without pathing. Then keep going: rescanning finds other
            // targets, and with none known updateGoal explores, so a failed search doesn't cancel mining.
            List<BlockPos> unreachable = new ArrayList<>(knownOreLocations);
            unreachable.removeIf(this::minableFromHere);
            blacklist.addAll(unreachable);
            unreachable.forEach(pos -> unreachableCount.merge(pos, 1, Integer::sum));
            blacklistOrigin = ctx.playerFeet();
            knownOreLocations.removeAll(unreachable);
        } else if (!knownOreLocations.isEmpty()) {
            failuresWithNothingKnown = 0;
        }

        updateLoucaSystem();
        int mineGoalUpdateInterval = Baritone.settings().mineGoalUpdateInterval.value;
        List<BlockPos> curr = new ArrayList<>(knownOreLocations);
        if (mineGoalUpdateInterval != 0 && tickCount++ % mineGoalUpdateInterval == 0) { // big brain
            CalculationContext context = new CalculationContext(baritone, true);
            Baritone.getExecutor().execute(() -> rescan(curr, context));
        }
        if (Baritone.settings().legitMine.value) {
            if (!addNearby()) {
                cancel();
                return null;
            }
        }
        // Collect target drops lying a few blocks off before breaking more: mining everything within reach first left
        // drops inside dense veins until nothing was in reach anymore (and with drops right next to us, in pockets we
        // can't step into, they're left alone: we'd only stand there).
        List<BlockPos> drops = settledDropsNearby();
        if (!drops.isEmpty() && !Baritone.settings().legitMine.value) {
            collectingDrops = drops;
            return new PathingCommand(new GoalComposite(drops.stream().map(GoalTwoBlocks::new).toArray(Goal[]::new)), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        collectingDrops = null;
        // Break any known target within arm's reach before pathing anywhere. Pathing only reaches a target by
        // standing in its spot, which in hollow geodes (floating crystals, buds that can't be stood on) often has no
        // valid standing position even though the block is right in front of us: that fails the path search and
        // blacklists the block, and repeated, that is the pause loop.
        Optional<BlockPos> inReach = curr.stream()
                .filter(this::minableFromHere)
                .min(Comparator.comparingDouble(pos -> pos.distToCenterSqr(ctx.playerHead())));
        baritone.getInputOverrideHandler().clearAllKeys();
        if (inReach.isPresent() && ctx.player().onGround() && isSafeToCancel) {
            BlockPos pos = inReach.get();
            Rotation rot = RotationUtils.reachable(ctx, pos).get();
            baritone.getLookBehavior().updateTarget(rot, true);
            MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
            if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        PathingCommand command = updateGoal();
        if (command == null) {
            // none in range
            // maybe say something in chat? (ahem impact)
            cancel();
            return null;
        }
        return command;
    }


    private void updateLoucaSystem() {
        Map<BlockPos, Long> copy = new HashMap<>(anticipatedDrops);
        ctx.getSelectedBlock().ifPresent(pos -> {
            if (knownOreLocations.contains(pos)) {
                copy.put(pos, System.currentTimeMillis() + Baritone.settings().mineDropLoiterDurationMSThanksLouca.value);
            }
        });
        // elaborate dance to avoid concurrentmodificationexcepption since rescan thread reads this
        // don't want to slow everything down with a gross lock do we now
        for (BlockPos pos : anticipatedDrops.keySet()) {
            if (copy.get(pos) < System.currentTimeMillis()) {
                copy.remove(pos);
            }
        }
        anticipatedDrops = copy;
    }

    @Override
    public void onLostControl() {
        mine(0, (BlockOptionalMetaLookup) null);
    }

    @Override
    public String displayName0() {
        return "Mine " + filter;
    }

    private PathingCommand updateGoal() {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return null;
        }

        boolean legit = Baritone.settings().legitMine.value;
        List<BlockPos> locs = knownOreLocations;
        if (!locs.isEmpty()) {
            CalculationContext context = new CalculationContext(baritone);
            List<BlockPos> locs2 = prune(context, new ArrayList<>(locs), filter, Baritone.settings().mineMaxOreLocationsCount.value, blacklist, droppedItemsScan());
            knownOreLocations = locs2;
            // with none left after pruning (just broken, drops not landed yet, or all off limits) there's nothing to
            // path to: an empty goal would only fail the search and blacklist things. Explore instead, below.
            if (!locs2.isEmpty()) {
                // can't reassign locs, gotta make a new var locs2, because we use it in a lambda right here, and variables you use in a lambda must be effectively final
                Goal goal = new GoalComposite(locs2.stream().map(loc -> coalesce(loc, locs2, context)).toArray(Goal[]::new));
                // An exploring path never ends in its goal, so plain revalidation doesn't interrupt it: targets showing
                // up meanwhile (drops landing after the last block broke, ore coming into view) would wait for the
                // whole explore segment, by which time they're far behind
                PathExecutor current = baritone.getPathingBehavior().getCurrent();
                boolean exploring = current != null && branchPointRunaway != null && current.getPath().getGoal() == branchPointRunaway;
                return new PathingCommand(goal, legit || exploring ? PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH : PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
        }
        // we don't know any ore locations at the moment
        if (!legit && !Baritone.settings().exploreForBlocks.value) {
            return null;
        }
        // only when we should explore for blocks or are in legit mode we do this
        // Legit mining strip-mines at legitMineYLevel. Otherwise nothing matching is loaded nearby, so explore
        // at any height: not down to the legit-mining level (which sent surface searches such as #mine sand straight
        // down to deepslate), and not pinned to the current height either, which is impossible in caves and geodes.
        Integer y = legit ? Baritone.settings().legitMineYLevel.value : null;
        if (branchPoint == null) {
            /*if (!baritone.getPathingBehavior().isPathing() && playerFeet().y == y) {
                // cool, path is over and we are at desired y
                branchPoint = playerFeet();
                branchPointRunaway = null;
            } else {
                return new GoalYLevel(y);
            }*/
            branchPoint = ctx.playerFeet();
        }
        // TODO shaft mode, mine 1x1 shafts to either side
        // TODO also, see if the GoalRunAway with maintain Y at 11 works even from the surface
        if (branchPointRunaway == null) {
            branchPointRunaway = new GoalRunAway(1, y, branchPoint) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    return false;
                }

                @Override
                public double heuristic() {
                    return Double.NEGATIVE_INFINITY;
                }
            };
        }
        return new PathingCommand(branchPointRunaway, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private void rescan(List<BlockPos> already, CalculationContext context) {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return;
        }
        if (Baritone.settings().legitMine.value) {
            return;
        }
        List<BlockPos> dropped = droppedItemsScan();
        List<BlockPos> locs = searchWorld(context, filter, Baritone.settings().mineMaxOreLocationsCount.value, already, blacklist, dropped);
        locs.addAll(dropped);
        if (locs.isEmpty() && !Baritone.settings().exploreForBlocks.value) {
            logDirect("No locations for " + filter + " known, cancelling");
            if (Baritone.settings().notificationOnMineFail.value) {
                logNotification("No locations for " + filter + " known, cancelling", true);
            }
            cancel();
            return;
        }
        knownOreLocations = locs;
    }

    private boolean internalMiningGoal(BlockPos pos, CalculationContext context, List<BlockPos> locs) {
        // Here, BlockStateInterface is used because the position may be in a cached chunk (the targeted block is one that is kept track of)
        if (locs.contains(pos)) {
            return true;
        }
        BlockState state = context.bsi.get0(pos);
        if (Baritone.settings().internalMiningAirException.value && state.getBlock() instanceof AirBlock) {
            return true;
        }
        return filter.has(state) && plausibleToBreak(context, pos);
    }

    private Goal coalesce(BlockPos loc, List<BlockPos> locs, CalculationContext context) {
        boolean assumeVerticalShaftMine = !(baritone.bsi.get0(loc.above()).getBlock() instanceof FallingBlock);
        if (!Baritone.settings().forceInternalMining.value) {
            if (assumeVerticalShaftMine) {
                // we can get directly below the block
                return new GoalThreeBlocks(loc);
            } else {
                // we need to get feet or head into the block
                return new GoalTwoBlocks(loc);
            }
        }
        boolean upwardGoal = internalMiningGoal(loc.above(), context, locs);
        boolean downwardGoal = internalMiningGoal(loc.below(), context, locs);
        boolean doubleDownwardGoal = internalMiningGoal(loc.below(2), context, locs);
        if (upwardGoal == downwardGoal) { // symmetric
            if (doubleDownwardGoal && assumeVerticalShaftMine) {
                // we have a checkerboard like pattern
                // this one, and the one two below it
                // therefore it's fine to path to immediately below this one, since your feet will be in the doubleDownwardGoal
                // but only if assumeVerticalShaftMine
                return new GoalThreeBlocks(loc);
            } else {
                // this block has nothing interesting two below, but is symmetric vertically so we can get either feet or head into it
                return new GoalTwoBlocks(loc);
            }
        }
        if (upwardGoal) {
            // downwardGoal known to be false
            // ignore the gap then potential doubleDownward, because we want to path feet into this one and head into upwardGoal
            return new GoalBlock(loc);
        }
        // upwardGoal known to be false, downwardGoal known to be true
        if (doubleDownwardGoal && assumeVerticalShaftMine) {
            // this block and two below it are goals
            // path into the center of the one below, because that includes directly below this one
            return new GoalTwoBlocks(loc.below());
        }
        // upwardGoal false, downwardGoal true, doubleDownwardGoal false
        // just this block and the one immediately below, no others
        return new GoalBlock(loc.below());
    }

    private static class GoalThreeBlocks extends GoalTwoBlocks {

        public GoalThreeBlocks(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x == this.x && (y == this.y || y == this.y - 1 || y == this.y - 2) && z == this.z;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int xDiff = x - this.x;
            int yDiff = y - this.y;
            int zDiff = z - this.z;
            return GoalBlock.calculate(xDiff, yDiff < -1 ? yDiff + 2 : yDiff == -1 ? 0 : yDiff, zDiff);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 393857768;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalThreeBlocks{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    /**
     * @return Whether a known target can be broken right now without moving: still there, within reach, safe to break,
     * visible, and not part of the floor layer we stand on. Breaking floor blocks around ourselves leaves us on a
     * pillar over the geode's hollow with drops too high to path down from.
     */
    private boolean minableFromHere(BlockPos pos) {
        if (pos.getY() == ctx.playerFeet().getY() - 1) {
            return false;
        }
        BlockState state = BlockStateInterface.get(ctx, pos);
        return !(state.getBlock() instanceof AirBlock) // after breaking a block, it takes mineGoalUpdateInterval ticks for it to actually update this list =(
                && pos.distToCenterSqr(ctx.playerHead()) <= 36
                && !MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state)
                && RotationUtils.reachable(ctx, pos).isPresent();
    }

    /**
     * @return Whether no target drop can be picked up anymore: no empty main slot, and every stack of a target item is full
     */
    private boolean inventoryFull() {
        Inventory inv = ctx.player().getInventory();
        if (inv.getFreeSlot() != -1) {
            return false;
        }
        return inv.items.stream().noneMatch(stack -> isTargetDrop(stack) && stack.getCount() < stack.getMaxStackSize());
    }

    /**
     * @return Whether this is something mining the targets yields: predicted from their loot tables, or seen dropping
     */
    private boolean isTargetDrop(ItemStack stack) {
        return filter.has(stack) || drops.isDropOf(filter::has, stack.getItem());
    }

    /**
     * @return How many target items we carry, offhand included
     */
    private int targetCount() {
        return Stream.concat(ctx.player().getInventory().items.stream(), ctx.player().getInventory().offhand.stream())
                .filter(this::isTargetDrop)
                .mapToInt(ItemStack::getCount).sum();
    }

    /**
     * Settled ages (ticks): a drop still flying out of the broken block isn't worth chasing yet
     */
    private static final int SETTLED_DROP_AGE = 10;
    private static final double NEARBY_DROP_DISTANCE_SQ = 8 * 8;
    /**
     * Within this, a drop is picked up just by standing where we are, unless it's somewhere we can't step into anyway
     */
    private static final double PICKUP_DISTANCE_SQ = 1.5 * 1.5;

    /**
     * @return Positions of settled target drops within a few blocks, that there's room for, worth walking to
     */
    private List<BlockPos> settledDropsNearby() {
        if (!Baritone.settings().mineScanDroppedItems.value) {
            return Collections.emptyList();
        }
        Inventory inv = ctx.player().getInventory();
        List<BlockPos> ret = new ArrayList<>();
        for (Entity entity : ((ClientLevel) ctx.world()).entitiesForRendering()) {
            if (!(entity instanceof ItemEntity)) {
                continue;
            }
            ItemEntity item = (ItemEntity) entity;
            double distSq = item.distanceToSqr(ctx.player());
            if (item.getAge() >= SETTLED_DROP_AGE && distSq > PICKUP_DISTANCE_SQ && distSq <= NEARBY_DROP_DISTANCE_SQ
                    && !blacklist.contains(item.blockPosition()) && isTargetDrop(item.getItem())
                    && (inv.getFreeSlot() != -1 || inv.getSlotWithRemainingSpace(item.getItem()) != -1)) {
                ret.add(item.blockPosition());
            }
        }
        return ret;
    }

    public List<BlockPos> droppedItemsScan() {
        if (!Baritone.settings().mineScanDroppedItems.value) {
            return Collections.emptyList();
        }
        Inventory inv = ctx.player().getInventory();
        List<BlockPos> ret = new ArrayList<>();
        for (Entity entity : ((ClientLevel) ctx.world()).entitiesForRendering()) {
            if (entity instanceof ItemEntity) {
                ItemEntity ei = (ItemEntity) entity;
                // Only items there is room for: walking onto a drop that can't be picked up never removes it
                if (isTargetDrop(ei.getItem()) && (inv.getFreeSlot() != -1 || inv.getSlotWithRemainingSpace(ei.getItem()) != -1)) {
                    ret.add(entity.blockPosition());
                }
            }
        }
        if (!inventoryFull()) {
            ret.addAll(anticipatedDrops.keySet());
        }
        return ret;
    }

    public static List<BlockPos> searchWorld(CalculationContext ctx, BlockOptionalMetaLookup filter, int max, List<BlockPos> alreadyKnown, List<BlockPos> blacklist, List<BlockPos> dropped) {
        List<BlockPos> locs = new ArrayList<>();
        List<Block> untracked = new ArrayList<>();
        for (BlockOptionalMeta bom : filter.blocks()) {
            Block block = bom.getBlock();
            if (CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(block)) {
                BetterBlockPos pf = ctx.baritone.getPlayerContext().playerFeet();

                // maxRegionDistanceSq 2 means adjacent directly or adjacent diagonally; nothing further than that
                locs.addAll(ctx.worldData.getCachedWorld().getLocationsOf(
                        BlockUtils.blockToString(block),
                        Baritone.settings().maxCachedWorldScanCount.value,
                        pf.x,
                        pf.z,
                        2
                ));
            } else {
                untracked.add(block);
            }
        }

        locs = prune(ctx, locs, filter, max, blacklist, dropped);

        if (!untracked.isEmpty() || (Baritone.settings().extendCacheOnThreshold.value && locs.size() < max)) {
            locs.addAll(BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(
                    ctx.getBaritone().getPlayerContext(),
                    filter,
                    max,
                    10,
                    32
            )); // maxSearchRadius is NOT sq
        }

        locs.addAll(alreadyKnown);

        return prune(ctx, locs, filter, max, blacklist, dropped);
    }

    private boolean addNearby() {
        List<BlockPos> dropped = droppedItemsScan();
        knownOreLocations.addAll(dropped);
        BlockPos playerFeet = ctx.playerFeet();
        BlockStateInterface bsi = new BlockStateInterface(ctx);


        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return false;
        }

        int searchDist = 10;
        double fakedBlockReachDistance = 20; // at least 10 * sqrt(3) with some extra space to account for positioning within the block
        for (int x = playerFeet.getX() - searchDist; x <= playerFeet.getX() + searchDist; x++) {
            for (int y = playerFeet.getY() - searchDist; y <= playerFeet.getY() + searchDist; y++) {
                for (int z = playerFeet.getZ() - searchDist; z <= playerFeet.getZ() + searchDist; z++) {
                    // crucial to only add blocks we can see because otherwise this
                    // is an x-ray and it'll get caught
                    if (filter.has(bsi.get0(x, y, z))) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if ((Baritone.settings().legitMineIncludeDiagonals.value && knownOreLocations.stream().anyMatch(ore -> ore.distSqr(pos) <= 2 /* sq means this is pytha dist <= sqrt(2) */)) || RotationUtils.reachable(ctx, pos, fakedBlockReachDistance).isPresent()) {
                            knownOreLocations.add(pos);
                        }
                    }
                }
            }
        }
        knownOreLocations = prune(new CalculationContext(baritone), knownOreLocations, filter, Baritone.settings().mineMaxOreLocationsCount.value, blacklist, dropped);
        return true;
    }

    private static List<BlockPos> prune(CalculationContext ctx, List<BlockPos> locs2, BlockOptionalMetaLookup filter, int max, List<BlockPos> blacklist, List<BlockPos> dropped) {
        dropped.removeIf(drop -> {
            for (BlockPos pos : locs2) {
                if (pos.distSqr(drop) <= 9 && filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) && MineProcess.plausibleToBreak(ctx, pos)) { // TODO maybe drop also has to be supported? no lava below?
                    return true;
                }
            }
            return false;
        });
        // mining is a vibration: near a shrieker it can summon a Warden
        List<BlockPos> shriekers = Baritone.settings().avoidSculkShriekers.value
                ? Avoidance.sculkShriekers(ctx.worldData, ctx.getBaritone().getPlayerContext().playerFeet())
                : Collections.emptyList();
        int shriekerRadiusSq = Avoidance.SCULK_SHRIEKER_RADIUS * Avoidance.SCULK_SHRIEKER_RADIUS;
        List<BlockPos> locs = locs2
                .stream()
                .distinct()

                // remove any that are within loaded chunks that aren't actually what we want
                .filter(pos -> !ctx.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ()) || filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) || dropped.contains(pos))

                // remove any that are implausible to mine (encased in bedrock, or touching lava)
                .filter(pos -> MineProcess.plausibleToBreak(ctx, pos))

                .filter(pos -> {
                    if (Baritone.settings().allowOnlyExposedOres.value) {
                        return isNextToAir(ctx, pos);
                    } else {
                        return true;
                    }
                })

                .filter(pos -> pos.getY() >= Baritone.settings().minYLevelWhileMining.value + ctx.world.dimensionType().minY())

                .filter(pos -> pos.getY() <= Baritone.settings().maxYLevelWhileMining.value)

                .filter(pos -> !blacklist.contains(pos))

                .filter(pos -> shriekers.stream().noneMatch(shrieker -> shrieker.distSqr(pos) <= shriekerRadiusSq))

                .sorted(Comparator.comparingDouble(ctx.getBaritone().getPlayerContext().player().blockPosition()::distSqr))
                .collect(Collectors.toList());

        if (locs.size() > max) {
            return locs.subList(0, max);
        }
        return locs;
    }

    public static boolean isNextToAir(CalculationContext ctx, BlockPos pos) {
        int radius = Baritone.settings().allowOnlyExposedOresDistance.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= radius
                            && MovementHelper.isTransparent(ctx.getBlock(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos) {
        BlockState state = ctx.bsi.get0(pos);
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(), state, true) >= COST_INF) {
            return false;
        }
        if (MovementHelper.avoidBreaking(ctx.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }

        // bedrock above and below makes it implausible, otherwise we're good
        return !(ctx.bsi.get0(pos.above()).getBlock() == Blocks.BEDROCK && ctx.bsi.get0(pos.below()).getBlock() == Blocks.BEDROCK);
    }

    @Override
    public void mineByName(int quantity, String... blocks) {
        mine(quantity, new BlockOptionalMetaLookup(blocks));
    }

    @Override
    public void mine(int quantity, BlockOptionalMetaLookup filter) {
        this.filter = filter;
        if (this.filterFilter() == null) {
            this.filter = null;
        }
        this.desiredQuantity = quantity;
        this.depositedTargets = 0;
        this.deposit.reset();
        this.knownOreLocations = new ArrayList<>();
        this.blacklist = new ArrayList<>();
        this.blacklistOrigin = null;
        this.unreachableCount = new HashMap<>();
        this.failuresWithNothingKnown = 0;
        this.branchPoint = null;
        this.branchPointRunaway = null;
        this.anticipatedDrops = new HashMap<>();
        if (filter != null) {
            rescan(new ArrayList<>(), new CalculationContext(baritone));
        }
    }

    /**
     * @return Whether we're mining and this is one of the blocks being mined
     */
    public boolean isTarget(BlockState state) {
        BlockOptionalMetaLookup filter = this.filter; // read once: it's cleared on the main thread when mining stops
        return filter != null && filter.has(state);
    }

    private BlockOptionalMetaLookup filterFilter() {
        if (this.filter == null) {
            return null;
        }
        if (!Baritone.settings().allowBreak.value) {
            BlockOptionalMetaLookup f = new BlockOptionalMetaLookup(this.filter.blocks()
                    .stream()
                    .filter(e -> Baritone.settings().allowBreakAnyway.value.contains(e.getBlock()))
                    .toArray(BlockOptionalMeta[]::new));
            if (f.blocks().isEmpty()) {
                logDirect("Unable to mine when allowBreak is false and target block is not in allowBreakAnyway!");
                return null;
            }
            return f;
        }
        return filter;
    }
}
