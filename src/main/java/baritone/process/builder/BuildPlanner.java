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
import baritone.api.schematic.ISchematic;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.MovementHelper;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Works out what to do, in what order and from where, against a {@link VirtualWorld}: each step is checked with the
 * game's own placement logic in the world as it will be by then, and every place to stand is one from which the step
 * is in reach and where the player isn't in the way of it. Like Litematica's printer, layer by layer from the bottom:
 * <ol>
 * <li>clearing: everything in the way broken, top down, sealing off liquids first</li>
 * <li>layers: bottom up, each block once there's something to place it against that makes it come out right,
 * putting a temporary block there if nothing else will do</li>
 * <li>finishing: doors, trapdoors and gates opened or closed</li>
 * <li>cleanup: temporary blocks and pillars taken away again</li>
 * </ol>
 * The plan only ever says where to stand and what to click; walking there is the pathfinder's job, and the executor
 * checks every step against the real world before and after doing it.
 */
public final class BuildPlanner {

    public enum Kind {
        BREAK, PLACE, FINISH, TOGGLE, USE
    }

    public static final class Step {

        public final Kind kind;
        public final BlockPos pos;
        /**
         * What's there once it's done
         */
        public final BlockState result;
        /**
         * The click, for placing. The executor swaps in another if the world turns out different from the plan
         */
        public PlacementModel.Option option;
        /**
         * A throwaway block, to be taken away again
         */
        public final boolean temporary;
        /**
         * The cells it fills, for placing: the player can't be in any of them
         */
        public final List<BlockPos> fills;

        Step(Kind kind, BlockPos pos, BlockState result, PlacementModel.Option option, boolean temporary, List<BlockPos> fills) {
            this.kind = kind;
            this.pos = pos;
            this.result = result;
            this.option = option;
            this.temporary = temporary;
            this.fills = fills;
        }

        @Override
        public String toString() {
            return kind + (temporary ? " (temporary)" : "") + " " + pos.toShortString() + " " + result + (option != null ? " by " + option : "");
        }
    }

    public static final class Stage {

        /**
         * Where the feet go
         */
        public final BlockPos stand;
        /**
         * How it's got to: standing there as it is, or with blocks to dig out or pillar up first (the pathfinder
         * does either)
         */
        public final String how;
        public final List<Step> steps = new ArrayList<>();

        Stage(BlockPos stand, String how) {
            this.stand = stand;
            this.how = how;
        }
    }

    public static final class Plan {

        public final String phase;
        /**
         * The layer, for a layer plan
         */
        public final int layer;
        public final List<Stage> stages = new ArrayList<>();
        /**
         * What couldn't be done yet, and why
         */
        public final Map<BlockPos, String> waiting = new LinkedHashMap<>();
        public long micros;
        /**
         * How many places to stand the walking model reached from the player when this was planned
         */
        public int reachable;
        /**
         * Whether only those were used (a stand the model didn't reach turned out not to be got to)
         */
        public boolean trusted;

        Plan(String phase, int layer) {
            this.phase = phase;
            this.layer = layer;
        }

        public int steps() {
            int n = 0;
            for (Stage stage : stages) {
                n += stage.steps.size();
            }
            return n;
        }

        public boolean isEmpty() {
            return stages.isEmpty();
        }

        public String summary() {
            return phase + (layer != Integer.MIN_VALUE ? " y=" + layer : "") + ": " + steps() + " steps from " + stages.size() + " stands"
                    + (waiting.isEmpty() ? "" : ", " + waiting.size() + " waiting") + ", " + reachable + " places reachable" + (trusted ? "" : " (not trusted)")
                    + " (" + micros / 1000 + "ms)";
        }

        public List<String> describe() {
            List<String> lines = new ArrayList<>();
            lines.add(summary());
            for (Stage stage : stages) {
                lines.add("  stand " + stage.stand.toShortString() + " (" + stage.how + ")");
                for (Step step : stage.steps) {
                    lines.add("    " + step);
                }
            }
            waiting.forEach((pos, why) -> lines.add("  waiting " + pos.toShortString() + ": " + why));
            return lines;
        }
    }

    private static final int MAX_PILLAR = 8;
    private static final int LOOKAHEAD = 16;

    private final IPlayerContext ctx;
    private final ISchematic schematic;
    private final BlockPos origin;
    private final PlacementModel model;
    /**
     * Throwaway blocks the plans put down: supports, seals, pillars. Taken away in the cleanup
     */
    private final Set<BlockPos> temporary = new LinkedHashSet<>();
    /**
     * Places to stand that turned out not to work (no path there)
     */
    private final Set<BlockPos> badStands = new HashSet<>();
    /**
     * Stands that turned out not to work for one target (the player couldn't stand clear of it there, say: on a ladder
     * the feet aren't where the stand's block says): left out for that target only
     */
    private final Map<BlockPos, Set<BlockPos>> badStandsFor = new HashMap<>();
    /**
     * What can't be built at all, and why
     */
    private final Map<BlockPos, String> unbuildable = new LinkedHashMap<>();
    private List<BlockState> placeable = Collections.emptyList();
    private Set<Item> have = Collections.emptySet();
    /**
     * What to put down as temporary blocks; null if there's none
     */
    private BlockState throwaway;
    private boolean cleared;
    /**
     * Cleanup plans made so far, and how much the last one had to take away: cleaning up goes on only while that
     * shrinks (getting to some of it can leave new throwaway blocks behind, as many as it clears)
     */
    private int cleanupRounds;
    private int lastCleanupLeft = Integer.MAX_VALUE;
    private int cleanupStuck;
    private static final int MAX_CLEANUP_STUCK = 3;
    private static final int MAX_CLEANUP_ROUNDS = 40;

    public BuildPlanner(IPlayerContext ctx, ISchematic schematic, BlockPos origin) {
        this.ctx = ctx;
        this.schematic = schematic;
        this.origin = origin;
        this.model = new PlacementModel(ctx);
    }

    public PlacementModel model() {
        return model;
    }

    public Map<BlockPos, String> unbuildable() {
        return unbuildable;
    }

    public Set<BlockPos> temporary() {
        return temporary;
    }

    public void badStand(BlockPos stand) {
        badStands.add(stand);
        if (reachable != null && !reachable.contains(stand.asLong())) {
            // the walking model said it couldn't be got to, and it couldn't: believe it from now on
            trustReach = true;
        }
    }

    /**
     * Where the player can get to from where planning starts, in the world as it is then, moving as the pathfinder may
     * about a build (see BuildPathContext): walking, stepping up one, dropping up to three, climbing ladders and vines,
     * digging through anything that isn't a finished part of the build, and pillaring or bridging with throwaway blocks
     * where the build wants nothing. Stands elsewhere are disfavoured, and left out once a path to one has failed: a
     * room closed off by the build itself (iron doors, walls up) isn't got into, and every try costs a path search
     */
    private LongOpenHashSet reachable;
    private boolean trustReach;
    /**
     * The pathfinder kept failing to get where the full model said it could: from then on stands reached only by
     * pillaring or bridging (putting blocks down to stand on) don't count. Walking, climbing, dropping and digging
     * through what may be broken still do: those are what paths reliably manage
     */
    private boolean strictReach;
    /**
     * Whether the strict model has been set aside once already, to clean up what it leaves out
     */
    private boolean relaxedForCleanup;

    /**
     * Paths keep failing to stands the model called reachable: the model is too hopeful about this build and terrain
     */
    public void reachUnreliable() {
        strictReach = true;
        trustReach = true;
    }

    public boolean isReachStrict() {
        return strictReach;
    }

    private static final int REACH_MARGIN = 8;
    private static final int MAX_DROP = 3;
    private static final double UNREACHABLE_PENALTY = 40;

    private void computeReachable(VirtualWorld world, BlockPos from) {
        reachable = new LongOpenHashSet();
        int minX = origin.getX() - REACH_MARGIN, maxX = origin.getX() + schematic.widthX() + REACH_MARGIN;
        int minY = origin.getY() - REACH_MARGIN, maxY = origin.getY() + schematic.heightY() + REACH_MARGIN;
        int minZ = origin.getZ() - REACH_MARGIN, maxZ = origin.getZ() + schematic.lengthZ() + REACH_MARGIN;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos seed : new BlockPos[]{from, from.above(), from.below()}) {
            // where the player is counts, standable as the model sees it or not (mid-step, on something odd)
            if ((seed == from || standable(world, seed)) && reachable.add(seed.asLong())) {
                queue.add(seed);
            }
        }
        while (!queue.isEmpty()) {
            BlockPos n = queue.poll();
            List<BlockPos> next = new ArrayList<>();
            boolean headroom = clearable(world, n.above(2));
            // up: climbing, or pillaring, which puts a block where the feet were (stone that was dug through to get
            // here is gone by then, it doesn't hold anyone up)
            BlockPos up = n.above();
            if (headroom && inBox(up, minX, maxX, minY, maxY, minZ, maxZ) && !reachable.contains(up.asLong())
                    && clearable(world, up) && (climbable(world, n) || climbable(world, up) || (!strictReach && mayPlace(world, n, true)))) {
                reachable.add(up.asLong());
                queue.add(up);
            }
            next.add(n.below()); // climbing down, or digging down
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos side = n.relative(dir);
                if (headroom) {
                    next.add(side.above());
                }
                if (!clearable(world, side) || !clearable(world, side.above())) {
                    continue;
                }
                next.add(side);
                for (int drop = 1; drop <= MAX_DROP && clearable(world, side.below(drop)); drop++) {
                    next.add(side.below(drop));
                }
            }
            for (BlockPos t : next) {
                if (!inBox(t, minX, maxX, minY, maxY, minZ, maxZ)) {
                    continue;
                }
                if (!reachable.contains(t.asLong()) && standable(world, t)) {
                    reachable.add(t.asLong());
                    queue.add(t);
                }
            }
        }
    }

    private boolean passable(VirtualWorld world, BlockPos pos) {
        return MovementHelper.canWalkThrough(world, pos.getX(), pos.getY(), pos.getZ());
    }

    private boolean climbable(VirtualWorld world, BlockPos pos) {
        return world.get(pos).is(net.minecraft.tags.BlockTags.CLIMBABLE);
    }

    /**
     * @return Whether the player's body can be in {@code pos}: open, or something the pathfinder may break there
     */
    private boolean clearable(VirtualWorld world, BlockPos pos) {
        if (passable(world, pos)) {
            return true;
        }
        BlockState state = world.get(pos);
        if (!Baritone.settings().allowBreak.value || !state.getFluidState().isEmpty() || state.getDestroySpeed(world.level(), pos) < 0
                || MovementHelper.avoidBreaking(world, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }
        BlockState desired = desired(world, pos);
        return desired == null || desired.getBlock() instanceof AirBlock
                || !(BlockRules.valid(state, desired, false) || BlockRules.completable(state, desired));
    }

    /**
     * @return Whether there is, or the pathfinder may put, something to stand on at {@code pos}
     */
    private boolean supportable(VirtualWorld world, BlockPos pos) {
        return MovementHelper.canWalkOn(world, pos.getX(), pos.getY(), pos.getZ()) || (!strictReach && mayPlace(world, pos, false));
    }

    /**
     * @return Whether the pathfinder may put a throwaway block at {@code pos}: where the build wants nothing, into
     * something replaceable (or, {@code cleared}, into the cell the player is leaving, whatever was there)
     */
    private boolean mayPlace(VirtualWorld world, BlockPos pos, boolean cleared) {
        if (!Baritone.settings().allowPlace.value || throwaway == null) {
            return false;
        }
        BlockState state = world.get(pos);
        BlockState desired = desired(world, pos);
        return state.getFluidState().isEmpty() && (cleared || replaceable(world, pos, state))
                && (desired == null || desired.getBlock() instanceof AirBlock);
    }

    private static boolean inBox(BlockPos t, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        return t.getX() >= minX && t.getX() <= maxX && t.getY() >= minY && t.getY() <= maxY && t.getZ() >= minZ && t.getZ() <= maxZ;
    }

    private boolean standable(VirtualWorld world, BlockPos pos) {
        return clearable(world, pos) && clearable(world, pos.above())
                && (supportable(world, pos.below()) || climbable(world, pos));
    }

    /**
     * @return Whether a stand can be got to as the walking model sees it: the stand itself, or for one to dig into or
     * pillar up to, a reachable cell next to it or (pillar) under it
     */
    private boolean reachableStand(BlockPos feet, String how) {
        if (reachable == null || reachable.contains(feet.asLong())) {
            return true;
        }
        if (how.equals("stand")) {
            return false;
        }
        for (Direction dir : Direction.values()) {
            if (reachable.contains(feet.relative(dir).asLong())) {
                return true;
            }
        }
        if (how.startsWith("pillar")) {
            int height = Integer.parseInt(how.substring("pillar ".length()));
            return reachable.contains(feet.below(height).asLong());
        }
        return false;
    }

    /**
     * Placing that turned out not to work for real, however it was tried
     */
    public void giveUp(BlockPos pos, String why) {
        unbuildable.put(pos, why);
    }

    /**
     * Waiting reason for something not built for want of the item: these are missing materials, not unbuildable
     */
    public static final String MISSING = "no item: ";

    /**
     * @param from      Where the player is, or will be
     * @param placeable What can be placed, for the schematic's choices
     * @param have      The items there are to place with
     * @param throwaway What to put down as temporary blocks, null if there's none
     * @param litter    Throwaway blocks left around the build by the pathfinder (bridging, pillaring), to go
     * @return What to do next, all of it checked in {@code world}, which it updates as if done; empty when the build is
     * done, or nothing more can be done
     */
    public Plan next(VirtualWorld world, BlockPos from, List<BlockState> placeable, Set<Item> have, BlockState throwaway, Collection<BlockPos> litter) {
        long start = System.nanoTime();
        this.placeable = placeable;
        this.have = have;
        this.throwaway = throwaway;
        Plan plan = plan(world, from, litter);
        plan.micros = (System.nanoTime() - start) / 1000;
        plan.reachable = reachable.size();
        plan.trusted = trustReach;
        return plan;
    }

    /**
     * Takes back giving up on what was left for want of materials, to try again
     */
    public void retryMissing() {
        unbuildable.values().removeIf(why -> why.startsWith(MISSING));
    }

    private Plan plan(VirtualWorld world, BlockPos from, Collection<BlockPos> litter) {
        computeReachable(world, from);
        if (!cleared) {
            Plan plan = planClearing(world, from);
            if (!plan.isEmpty()) {
                return plan;
            }
            cleared = true;
        }
        // the lowest layer with anything that can be done, with whatever is still waiting below it retried first
        Map<BlockPos, String> stillWaiting = new LinkedHashMap<>();
        for (int y = 0; y < schematic.heightY(); y++) {
            Plan plan = planLayer(world, from, origin.getY() + y);
            if (!plan.isEmpty()) {
                plan.waiting.putAll(stillWaiting);
                return plan;
            }
            stillWaiting.putAll(plan.waiting);
        }
        // nothing in any layer can be done: what's left can't be built
        stillWaiting.forEach(unbuildable::putIfAbsent);
        if (stillWaiting.values().stream().anyMatch(why -> why.startsWith(MISSING))) {
            // waiting for materials: no finishing or cleaning up yet (the temporary blocks may still be needed), the
            // build pauses until they're there
            return new Plan("waiting for materials", Integer.MIN_VALUE);
        }
        Plan toggles = planToggles(world, from);
        if (!toggles.isEmpty()) {
            return toggles;
        }
        toggles.waiting.forEach(unbuildable::putIfAbsent); // nothing else will come of them
        int mark = world.mark();
        Plan cleanup = planCleanup(world, from, litter);
        if (cleanup.isEmpty()) {
            if (strictReach && !relaxedForCleanup && !cleanup.waiting.isEmpty()) {
                // the strict model leaves things where they can't be got to: one more go with the full one (failures
                // to get to a stand still rule that stand out, so this ends)
                relaxedForCleanup = true;
                strictReach = false;
                trustReach = false;
                world.revert(mark);
                unbuildable.values().removeIf(why -> why.startsWith("nowhere to stand")); // (those get another go too)
                return plan(world, from, litter);
            }
            cleanup.waiting.forEach(unbuildable::putIfAbsent);
            return cleanup;
        }
        int left = cleanup.steps();
        cleanupStuck = left < lastCleanupLeft ? 0 : cleanupStuck + 1;
        if (cleanupStuck >= MAX_CLEANUP_STUCK || ++cleanupRounds > MAX_CLEANUP_ROUNDS) {
            // no less to take away, plan after plan: getting to it leaves as much behind as it clears. What's still
            // about is left, and said
            world.revert(mark); // (only looked at: none of it is done)
            for (Stage stage : cleanup.stages) {
                for (Step step : stage.steps) {
                    unbuildable.putIfAbsent(step.pos, "left in place: cleaning up kept leaving as much behind as it took away");
                }
            }
            cleanup.waiting.forEach(unbuildable::putIfAbsent);
            return new Plan("cleanup", Integer.MIN_VALUE);
        }
        lastCleanupLeft = Math.min(lastCleanupLeft, left);
        return cleanup;
    }

    // ---- what each cell needs

    private BlockState desired(VirtualWorld world, BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        BlockState current = world.get(pos);
        if (!schematic.inSchematic(x, y, z, current)) {
            return null;
        }
        return schematic.desiredState(x, y, z, current, placeable);
    }

    private boolean replaceable(VirtualWorld world, BlockPos pos, BlockState state) {
        return MovementHelper.isReplaceable(pos.getX(), pos.getY(), pos.getZ(), state, world);
    }

    /**
     * @return Whether something has to be broken there for the build: in the way of what goes there, or there where
     * nothing should be
     */
    private boolean inTheWay(VirtualWorld world, BlockPos pos, BlockState current, BlockState desired) {
        if (desired == null || current.getBlock() instanceof AirBlock || BlockRules.valid(current, desired, false)
                || BlockRules.completable(current, desired)) {
            return false;
        }
        if (current.getBlock() instanceof LiquidBlock) {
            return false; // placed into, or left
        }
        return desired.getBlock() instanceof AirBlock || !replaceable(world, pos, current);
    }

    private List<BlockPos> box(boolean topDown) {
        List<BlockPos> cells = new ArrayList<>();
        for (int i = 0; i < schematic.heightY(); i++) {
            int y = topDown ? schematic.heightY() - 1 - i : i;
            cells.addAll(layer(origin.getY() + y));
        }
        return cells;
    }

    /**
     * @return The cells of one layer, in a sweep back and forth so that one stand serves neighbours
     */
    private List<BlockPos> layer(int y) {
        List<BlockPos> cells = new ArrayList<>();
        for (int x = 0; x < schematic.widthX(); x++) {
            for (int i = 0; i < schematic.lengthZ(); i++) {
                int z = x % 2 == 0 ? i : schematic.lengthZ() - 1 - i;
                cells.add(new BlockPos(origin.getX() + x, y, origin.getZ() + z));
            }
        }
        return cells;
    }

    // ---- phases

    private Plan planClearing(VirtualWorld world, BlockPos from) {
        Plan plan = new Plan("clearing", Integer.MIN_VALUE);
        List<BlockPos> todo = new ArrayList<>();
        for (BlockPos pos : box(true)) {
            // only what has to go altogether: a wrong block where another is wanted is broken and replaced as its
            // layer is built, from the same stand, not in a pass over the whole build first. (Where liquid is wanted,
            // anything solid goes too: the builder doesn't pour it, but the place is left ready for it)
            BlockState desired = desired(world, pos);
            if (!unbuildable.containsKey(pos) && desired != null && (desired.getBlock() instanceof AirBlock || desired.getBlock() instanceof LiquidBlock)
                    && inTheWay(world, pos, world.get(pos), desired)) {
                todo.add(pos);
            }
        }
        BlockPos at = from;
        for (int i = 0; i < todo.size(); i++) {
            BlockPos pos = todo.get(i);
            List<BlockPos> upcoming = todo.subList(i + 1, Math.min(todo.size(), i + 1 + LOOKAHEAD));
            at = planBreak(world, plan, pos, at, upcoming);
            // sand or gravel from above falls in as it's broken: again, until there's no more
            for (int again = 0; again < MAX_REFILLS && world.get(pos).getBlock() instanceof FallingBlock
                    && inTheWay(world, pos, world.get(pos), desired(world, pos)); again++) {
                at = planBreak(world, plan, pos, at, upcoming);
            }
        }
        return plan;
    }

    private Plan planLayer(VirtualWorld world, BlockPos from, int y) {
        Plan plan = new Plan("layer", y);
        List<BlockPos> todo = new ArrayList<>();
        for (BlockPos pos : layer(y)) {
            if (unbuildable.containsKey(pos)) {
                continue;
            }
            BlockState desired = desired(world, pos);
            BlockState current = world.get(pos);
            if (desired == null || desired.getBlock() instanceof AirBlock || BlockRules.valid(current, desired, false)
                    || BlockRules.needsToggle(current, desired) || BlockRules.awaitsPartner(current, desired)) {
                continue;
            }
            Item item = PlacementModel.itemFor(desired);
            if (item == null) {
                unbuildable.put(pos, "nothing places " + desired.getBlock().getName().getString());
                continue;
            }
            Item use = BlockRules.useItem(desired);
            if (!have.contains(item) || (use != null && !have.contains(use))) {
                plan.waiting.put(pos, MISSING + (!have.contains(item) ? item : use));
                continue;
            }
            todo.add(pos);
        }
        BlockPos at = from;
        // a block can need another of the same layer to be placed against (a torch on a wall): go round again while
        // anything gets placed. One that would get in the way of a neighbour still to be placed (a log beside a door
        // decides the door's hinge side) waits for it, once
        boolean progress = true;
        Map<BlockPos, String> why = new LinkedHashMap<>();
        Set<BlockPos> waitedFor = new HashSet<>();
        while (progress && !todo.isEmpty()) {
            progress = false;
            why.clear();
            for (Iterator<BlockPos> it = todo.iterator(); it.hasNext(); ) {
                BlockPos pos = it.next();
                if (waitedFor.add(pos)) { // looked at once per plan: it's costly
                    BlockPos spoiled = spoils(world, pos, todo);
                    if (spoiled != null) {
                        why.put(pos, "waiting for " + spoiled.toShortString() + ", which it would leave no way to place right");
                        progress = true;
                        continue;
                    }
                }
                BlockPos next = planPlace(world, plan, pos, at, upcoming(todo, pos), why);
                if (next != null) {
                    at = next;
                    it.remove();
                    progress = true;
                }
            }
        }
        // what's left has nothing to be placed against that works: give it something
        for (Iterator<BlockPos> it = todo.iterator(); it.hasNext(); ) {
            BlockPos pos = it.next();
            BlockPos next = planWithSupport(world, plan, pos, at, why);
            if (next != null) {
                at = next;
                it.remove();
            }
        }
        for (BlockPos pos : todo) {
            plan.waiting.put(pos, why.getOrDefault(pos, "?"));
        }
        return plan;
    }

    private static List<BlockPos> upcoming(List<BlockPos> todo, BlockPos pos) {
        int i = todo.indexOf(pos);
        return todo.subList(i + 1, Math.min(todo.size(), i + 1 + LOOKAHEAD));
    }

    /**
     * @return A neighbour still to be placed that placing {@code pos} now would leave no click to place right, or null
     */
    private BlockPos spoils(VirtualWorld world, BlockPos pos, List<BlockPos> todo) {
        BlockState desired = desired(world, pos);
        if (desired == null || !replaceable(world, pos, world.get(pos))) {
            return null;
        }
        List<PlacementModel.Option> options = model.options(pos, desired, world);
        if (options.isEmpty()) {
            return null;
        }
        List<BlockPos> placeableNow = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockPos next = pos.relative(dir);
            if (!todo.contains(next)) {
                continue;
            }
            BlockState wanted = desired(world, next);
            if (wanted != null && replaceable(world, next, world.get(next)) && !model.options(next, wanted, world).isEmpty()) {
                placeableNow.add(next);
            }
        }
        if (placeableNow.isEmpty()) {
            return null;
        }
        int mark = world.mark();
        apply(world, pos, options.get(0));
        try {
            for (BlockPos next : placeableNow) {
                BlockState wanted = desired(world, next);
                if (wanted != null && !BlockRules.valid(world.get(next), wanted, false) && model.options(next, wanted, world).isEmpty()) {
                    return next;
                }
            }
            return null;
        } finally {
            world.revert(mark);
        }
    }

    private Plan planToggles(VirtualWorld world, BlockPos from) {
        Plan plan = new Plan("finishing", Integer.MIN_VALUE);
        BlockPos at = from;
        for (BlockPos pos : box(false)) {
            BlockState desired = desired(world, pos);
            BlockState current = world.get(pos);
            if (desired == null || unbuildable.containsKey(pos) || !BlockRules.needsToggle(current, desired)) {
                continue;
            }
            BlockPos stood = planToggle(world, plan, pos, desired, at, Collections.emptyList());
            if (stood == null) {
                plan.waiting.put(pos, "nowhere to stand in reach");
                continue;
            }
            at = stood;
        }
        return plan;
    }

    /**
     * Opens or closes {@code pos} to make it {@code desired}
     *
     * @return Where the player stands after, or null if there's nowhere to do it from
     */
    private BlockPos planToggle(VirtualWorld world, Plan plan, BlockPos pos, BlockState desired, BlockPos at, List<BlockPos> upcoming) {
        Choice choice = choose(world, pos, null, Collections.emptyList(), upcoming, at);
        if (choice == null) {
            return null;
        }
        stage(plan, choice).steps.add(new Step(Kind.TOGGLE, pos, desired, null, false, Collections.emptyList()));
        world.level().setBlock(pos, world.get(pos).cycle(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN), 3);
        return choice.stand;
    }

    private Plan planCleanup(VirtualWorld world, BlockPos from, Collection<BlockPos> litter) {
        Plan plan = new Plan("cleanup", Integer.MIN_VALUE);
        Set<BlockPos> todo = new TreeSet<>(Comparator.comparingInt((BlockPos b) -> -b.getY()).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        todo.addAll(temporary);
        todo.addAll(litter);
        for (BlockPos pos : box(true)) {
            BlockState current = world.get(pos);
            BlockState desired = desired(world, pos);
            if (desired != null && desired.getBlock() instanceof AirBlock && inTheWay(world, pos, current, desired)) {
                todo.add(pos);
            }
        }
        List<BlockPos> ordered = new ArrayList<>(todo);
        BlockPos at = from;
        for (int i = 0; i < ordered.size(); i++) {
            BlockPos pos = ordered.get(i);
            BlockState current = world.get(pos);
            BlockState desired = desired(world, pos);
            if (unbuildable.containsKey(pos)) {
                continue; // left in place, said why
            }
            if (current.getBlock() instanceof AirBlock || current.getBlock() instanceof LiquidBlock
                    || (desired != null && !freeFor(desired))) {
                temporary.remove(pos); // gone already, or part of the build now
                continue;
            }
            if (nextToLiquid(world, pos)) {
                unbuildable.put(pos, "left in place: holds back liquid");
                continue;
            }
            BlockPos held = holdsUp(world, pos);
            if (held != null) {
                unbuildable.put(pos, "left in place: holds up " + held.toShortString());
                continue;
            }
            at = planBreak(world, plan, pos, at, ordered.subList(i + 1, Math.min(ordered.size(), i + 1 + LOOKAHEAD)));
        }
        return plan;
    }

    // ---- steps

    /**
     * @return Where the player stands after, so the next step can start from there
     */
    private BlockPos planBreak(VirtualWorld world, Plan plan, BlockPos pos, BlockPos at, List<BlockPos> upcoming) {
        BlockState current = world.get(pos);
        if (current.getDestroySpeed(world.level(), pos) < 0) {
            unbuildable.put(pos, "can't break " + current.getBlock().getName().getString());
            return at;
        }
        if (!seal(world, plan, pos, at)) {
            // opening it would let the liquid in: not without the seal
            plan.waiting.put(pos, "liquid next to it that can't be sealed off first");
            return at;
        }
        Choice choice = choose(world, pos, null, Collections.singletonList(pos), upcoming, at);
        if (choice == null) {
            plan.waiting.put(pos, "nowhere to stand in reach");
            return at;
        }
        BlockState after = current.getFluidState().createLegacyBlock();
        world.level().setBlock(pos, after, 3);
        // whatever falls (sand, gravel) comes down the column into it
        BlockPos top = pos.above();
        while (world.get(top).getBlock() instanceof FallingBlock) {
            top = top.above();
        }
        for (BlockPos cell = pos; cell.getY() < top.getY() - 1; cell = cell.above()) {
            world.set(cell, world.get(cell.above()));
        }
        if (top.getY() > pos.getY() + 1) {
            world.set(top.below(), after);
        }
        stage(plan, choice).steps.add(new Step(Kind.BREAK, pos, world.get(pos), null, false, Collections.emptyList()));
        return choice.stand;
    }

    /**
     * Before something next to liquid is broken, the liquid gets a throwaway block put in it, so it doesn't run into
     * the build
     *
     * @return Whether every liquid next to it is sealed off (or wanted there)
     */
    private boolean seal(VirtualWorld world, Plan plan, BlockPos pos, BlockPos at) {
        boolean sealed = true;
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) {
                continue; // liquid doesn't flow up
            }
            BlockPos next = pos.relative(dir);
            if (world.get(next).getFluidState().isEmpty() || liquidWanted(world, next)) {
                continue;
            }
            BlockPos stood = planTemporary(world, plan, next, at);
            if (stood == null) {
                plan.waiting.put(next, "can't seal off liquid next to " + pos.toShortString());
                sealed = false;
            } else {
                at = stood;
            }
        }
        return sealed;
    }

    /**
     * @return Whether the liquid at {@code pos} is the one the build wants there (lava where water goes isn't)
     */
    private boolean liquidWanted(VirtualWorld world, BlockPos pos) {
        BlockState desired = desired(world, pos);
        return desired != null && desired.getBlock() instanceof LiquidBlock
                && world.get(pos).getFluidState().getType().isSame(desired.getFluidState().getType());
    }

    /**
     * @return Whether liquid at {@code pos} is what the build wants there, so it may stay next to an opening
     */
    public boolean liquidWanted(BlockPos pos, VirtualWorld world) {
        return liquidWanted(world, pos);
    }

    private boolean nextToLiquid(VirtualWorld world, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (dir != Direction.DOWN && !world.get(pos.relative(dir)).getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return Something of the build next to {@code pos} that wouldn't stay without it (hanging from it, resting on
     * it), or null
     */
    private BlockPos holdsUp(VirtualWorld world, BlockPos pos) {
        int mark = world.mark();
        world.set(pos, Blocks.AIR.defaultBlockState());
        try {
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                BlockState state = world.get(next);
                BlockState desired = desired(world, next);
                if (desired == null || desired.getBlock() instanceof AirBlock || state.isAir()) {
                    continue;
                }
                if (!state.canSurvive(world.level(), next)
                        || (dir == Direction.UP && state.getBlock() instanceof FallingBlock)) {
                    return next;
                }
            }
            return null;
        } finally {
            world.revert(mark);
        }
    }

    /**
     * @return Where the player stands after, or null if it can't be placed now
     */
    private BlockPos planPlace(VirtualWorld world, Plan plan, BlockPos pos, BlockPos at, List<BlockPos> upcoming, Map<BlockPos, String> why) {
        BlockState desired = desired(world, pos);
        BlockState current = world.get(pos);
        if (desired == null || BlockRules.valid(current, desired, false)) {
            return at; // nothing to do (placing something else put it there too: a bed's head, a door's upper half)
        }
        if (desired.getBlock() instanceof FallingBlock && FallingBlock.isFree(world.get(pos.below()))) {
            why.put(pos, "nothing under it yet, it would fall");
            return null;
        }
        if (BlockRules.needsSecondSlab(current, desired)) {
            return planFinish(world, plan, pos, current, desired, at, upcoming, why);
        }
        if (BlockRules.needsUse(current, desired)) {
            return planUse(world, plan, pos, desired, at, upcoming, why);
        }
        if (!replaceable(world, pos, current)) {
            // in the way: broken first
            BlockPos stood = planBreak(world, plan, pos, at, upcoming);
            if (!(world.get(pos).getBlock() instanceof AirBlock) && !replaceable(world, pos, world.get(pos))) {
                why.put(pos, "something in the way that can't be broken now");
                return null;
            }
            at = stood;
        }
        List<PlacementModel.Option> options = model.options(pos, desired, world);
        if (options.isEmpty()) {
            why.put(pos, "nothing to place it against that makes it come out right");
            return null;
        }
        // what the placement fills, besides the cell itself (a door's upper half, a bed's head): the player can't be
        // there
        int mark = world.mark();
        apply(world, pos, options.get(0));
        List<BlockPos> fills = world.changedSince(mark);
        world.revert(mark);
        Choice choice = choose(world, pos, options, fills, upcoming, at);
        if (choice == null) {
            why.put(pos, "nowhere to stand in reach of what it's placed against");
            return null;
        }
        stage(plan, choice).steps.add(new Step(Kind.PLACE, pos, choice.option.result, choice.option, false, fills));
        apply(world, pos, choice.option);
        if (BlockRules.needsSecondSlab(world.get(pos), desired)) {
            BlockPos stood = planFinish(world, plan, pos, world.get(pos), desired, choice.stand, upcoming, why);
            return stood != null ? stood : choice.stand;
        }
        if (BlockRules.needsUse(world.get(pos), desired)) {
            BlockPos stood = planUse(world, plan, pos, desired, choice.stand, upcoming, why);
            return stood != null ? stood : choice.stand;
        }
        if (BlockRules.needsToggle(world.get(pos), desired)) {
            // opened (or closed) as wanted right away: a trapdoor in a ladder shaft is in the way until it's open
            BlockPos stood = planToggle(world, plan, pos, desired, choice.stand, upcoming);
            return stood != null ? stood : choice.stand;
        }
        return choice.stand;
    }

    /**
     * Uses the item that finishes {@code desired} on its base, which is there (a plant put in a pot)
     */
    private BlockPos planUse(VirtualWorld world, Plan plan, BlockPos pos, BlockState desired, BlockPos at, List<BlockPos> upcoming, Map<BlockPos, String> why) {
        Choice choice = choose(world, pos, null, Collections.emptyList(), upcoming, at);
        if (choice == null) {
            why.put(pos, "nowhere to stand in reach to finish it");
            return null;
        }
        stage(plan, choice).steps.add(new Step(Kind.USE, pos, desired, null, false, Collections.emptyList()));
        world.set(pos, desired);
        return choice.stand;
    }

    private BlockPos planFinish(VirtualWorld world, Plan plan, BlockPos pos, BlockState current, BlockState desired, BlockPos at, List<BlockPos> upcoming, Map<BlockPos, String> why) {
        PlacementModel.Option option = model.finishSlab(pos, current, desired, world);
        if (option == null) {
            why.put(pos, "can't make the slab double");
            return null;
        }
        Choice choice = choose(world, pos, Collections.singletonList(option), Collections.singletonList(pos), upcoming, at);
        if (choice == null) {
            why.put(pos, "nowhere to stand in reach to make the slab double");
            return null;
        }
        stage(plan, choice).steps.add(new Step(Kind.FINISH, pos, option.result, option, false, Collections.singletonList(pos)));
        world.level().setBlock(pos, option.result, 3);
        return choice.stand;
    }

    /**
     * Places {@code pos} against throwaway blocks put there for it, where nothing is wanted: one next to it, and as
     * many more as it takes to get to something to put the first against (a chain out to the nearest solid block, for
     * something with nothing around it at all), if that makes it possible
     *
     * @return Where the player stands after, or null if it can't
     */
    private BlockPos planWithSupport(VirtualWorld world, Plan plan, BlockPos pos, BlockPos at, Map<BlockPos, String> why) {
        BlockState desired = desired(world, pos);
        if (desired == null || throwaway == null) {
            return null;
        }
        // the neighbours that would do, as support
        Map<BlockPos, BlockPos> towards = new HashMap<>(); // each cell to the one after it, towards the target
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        for (Direction dir : Direction.values()) {
            BlockPos support = pos.relative(dir);
            if (!freeForTemporary(world, support)) {
                continue;
            }
            int mark = world.mark();
            world.set(support, throwaway);
            boolean helps = !model.options(pos, desired, world).isEmpty();
            world.revert(mark);
            if (helps) {
                towards.put(support, pos);
                depth.put(support, 1);
                queue.add(support);
            }
        }
        // out from those to a cell next to something to put a block against
        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            if (touchesSolid(world, cell, pos)) {
                BlockPos placed = planChain(world, plan, cell, towards, pos, at, why);
                if (placed != null) {
                    return placed;
                }
                continue;
            }
            if (depth.get(cell) >= MAX_SCAFFOLD) {
                continue;
            }
            for (Direction dir : Direction.values()) {
                BlockPos next = cell.relative(dir);
                if (!next.equals(pos) && !towards.containsKey(next) && freeForTemporary(world, next)) {
                    towards.put(next, cell);
                    depth.put(next, depth.get(cell) + 1);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    /**
     * Throwaway blocks from {@code first} along {@code towards} up to (not including) the target, each against the
     * last, then the target. All taken back out of the plan if any of it can't be done
     */
    private BlockPos planChain(VirtualWorld world, Plan plan, BlockPos first, Map<BlockPos, BlockPos> towards, BlockPos target, BlockPos at, Map<BlockPos, String> why) {
        int planMark = world.mark();
        int stages = plan.stages.size();
        int lastSteps = stages == 0 ? 0 : plan.stages.get(stages - 1).steps.size();
        List<BlockPos> added = new ArrayList<>();
        BlockPos stood = at;
        for (BlockPos cell = first; !cell.equals(target); cell = towards.get(cell)) {
            stood = planTemporary(world, plan, cell, stood);
            if (stood == null) {
                break;
            }
            added.add(cell);
        }
        if (stood != null) {
            BlockPos placed = planPlace(world, plan, target, stood, Collections.emptyList(), why);
            if (placed != null && !added.isEmpty() && !staysWithout(world, target, added.get(added.size() - 1))) {
                // hangs from or rests on nothing the build has: it would come down again with the support
                why.put(target, "wouldn't stay: it hangs from or rests on nothing the build has");
                placed = null;
            }
            if (placed != null) {
                return placed;
            }
        }
        world.revert(planMark);
        while (plan.stages.size() > stages) {
            plan.stages.remove(plan.stages.size() - 1);
        }
        if (stages > 0) {
            List<Step> steps = plan.stages.get(stages - 1).steps;
            while (steps.size() > lastSteps) {
                steps.remove(steps.size() - 1);
            }
        }
        temporary.removeAll(added);
        return null;
    }

    private boolean staysWithout(VirtualWorld world, BlockPos pos, BlockPos support) {
        int mark = world.mark();
        world.set(support, Blocks.AIR.defaultBlockState());
        boolean stays = world.get(pos).canSurvive(world.level(), pos);
        world.revert(mark);
        return stays;
    }

    private static final int MAX_SCAFFOLD = 6;
    /**
     * Breaks of one cell that sand or gravel from above can fall into again
     */
    private static final int MAX_REFILLS = 16;

    /**
     * @return Whether a cell may have a temporary block put in it: empty, and nothing the build puts there. Not where
     * the build wants liquid either: the builder doesn't pour it, but a block left there would be one more thing wrong
     */
    private boolean freeForTemporary(VirtualWorld world, BlockPos pos) {
        BlockState there = world.get(pos);
        BlockState wanted = desired(world, pos);
        return replaceable(world, pos, there) && (wanted == null || freeFor(wanted));
    }

    private static boolean freeFor(BlockState desired) {
        return desired.getBlock() instanceof AirBlock;
    }

    private boolean touchesSolid(VirtualWorld world, BlockPos cell, BlockPos except) {
        for (Direction dir : Direction.values()) {
            BlockPos next = cell.relative(dir);
            if (!next.equals(except) && !replaceable(world, next, world.get(next)) && !world.get(next).getShape(world.level(), next).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts a throwaway block at {@code pos}, to be taken away in the cleanup
     *
     * @return Where the player stands after, or null if it can't
     */
    private BlockPos planTemporary(VirtualWorld world, Plan plan, BlockPos pos, BlockPos at) {
        if (throwaway == null) {
            return null;
        }
        List<PlacementModel.Option> options = model.options(pos, throwaway, world);
        if (options.isEmpty()) {
            return null;
        }
        Choice choice = choose(world, pos, options, Collections.singletonList(pos), Collections.emptyList(), at);
        if (choice == null) {
            return null;
        }
        stage(plan, choice).steps.add(new Step(Kind.PLACE, pos, choice.option.result, choice.option, true, Collections.singletonList(pos)));
        apply(world, pos, choice.option);
        temporary.add(pos.immutable());
        return choice.stand;
    }

    /**
     * Puts what {@code option} places into the planned world as the game does it: the block, the neighbours adjusting
     * to it, and whatever placing it does besides (a door's upper half, a bed's head)
     */
    private void apply(VirtualWorld world, BlockPos pos, PlacementModel.Option option) {
        try {
            world.level().setBlock(pos, option.result, 11);
            option.result.getBlock().setPlacedBy(world.level(), pos, option.result, ctx.player(), new ItemStack(option.result.getBlock().asItem()));
        } catch (RuntimeException e) {
            // something about it the planning world can't show: only the block itself is planned
            if (problems.size() < 50) {
                problems.add("placing " + option.result + " at " + pos.toShortString() + ": " + e);
            }
        }
    }

    /**
     * What went wrong working out what placing does, for the log
     */
    private final List<String> problems = new ArrayList<>();

    public List<String> problems() {
        return problems;
    }

    // ---- where to stand

    private static final class Choice {

        final BlockPos stand;
        final String how;
        final PlacementModel.Option option;

        Choice(BlockPos stand, String how, PlacementModel.Option option) {
            this.stand = stand;
            this.how = how;
            this.option = option;
        }
    }

    private Stage stage(Plan plan, Choice choice) {
        Stage last = plan.stages.isEmpty() ? null : plan.stages.get(plan.stages.size() - 1);
        if (last != null && last.stand.equals(choice.stand)) {
            return last;
        }
        Stage stage = new Stage(choice.stand, choice.how);
        plan.stages.add(stage);
        return stage;
    }

    /**
     * Where to stand to do something to {@code target}: where we are if that works, else whatever is in reach of it
     * and of most of what comes next, with the least walking, digging and pillaring. A place to stand that needs
     * pillaring up to gets the pillar planned too, so later steps can use it.
     *
     * @param options  The clicks that place it, or null to break or use it (in reach of the block itself)
     * @param fills    Cells the step fills: the player can't be there
     * @param upcoming What comes next, to stand where most of it can be done too
     */
    private Choice choose(VirtualWorld world, BlockPos target, List<PlacementModel.Option> options, Collection<BlockPos> fills, List<BlockPos> upcoming, BlockPos at) {
        if (at != null) {
            Choice here = tryStand(world, at, target, options, fills, false);
            if (here != null) {
                return here;
            }
        }
        Choice best = null;
        double bestScore = Double.MAX_VALUE;
        int reach = (int) Math.ceil(PlacementModel.REACH);
        for (int dy = -reach - 1; dy <= reach - 1; dy++) {
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (PlacementModel.eye(feet).distanceTo(Vec3.atCenterOf(target)) > PlacementModel.REACH + 1) {
                        continue;
                    }
                    Choice choice = tryStand(world, feet, target, options, fills, true);
                    if (choice == null) {
                        continue;
                    }
                    double score = cost(choice) - 3 * coverage(feet, upcoming) + (at == null ? 0 : distance(at, feet));
                    if (!reachableStand(feet, choice.how)) {
                        if (trustReach) {
                            continue;
                        }
                        score += UNREACHABLE_PENALTY;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = choice;
                    }
                }
            }
        }
        if (best != null) {
            commit(world, best);
        }
        return best;
    }

    private static double distance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    private static double cost(Choice choice) {
        if (choice.how.startsWith("pillar")) {
            return 4 + 3 * Integer.parseInt(choice.how.substring("pillar ".length()));
        }
        if (choice.how.startsWith("dig")) {
            return 4 * Integer.parseInt(choice.how.substring("dig ".length()));
        }
        return 0;
    }

    private static int coverage(BlockPos feet, List<BlockPos> upcoming) {
        Vec3 eye = PlacementModel.eye(feet);
        int n = 0;
        for (BlockPos pos : upcoming) {
            if (!pos.equals(feet) && !pos.equals(feet.above()) && eye.distanceTo(Vec3.atCenterOf(pos)) <= PlacementModel.REACH - 0.5) {
                n++;
            }
        }
        return n;
    }

    /**
     * The step for {@code target} couldn't be done from {@code stand} when the player got there: another stand for it
     */
    public void badStandFor(BlockPos target, BlockPos stand) {
        badStandsFor.computeIfAbsent(target.immutable(), k -> new HashSet<>()).add(stand.immutable());
    }

    private Choice tryStand(VirtualWorld world, BlockPos feet, BlockPos target, List<PlacementModel.Option> options, Collection<BlockPos> fills, boolean prepare) {
        if (badStands.contains(feet) || badStandsFor.getOrDefault(target, Collections.emptySet()).contains(feet)
                || fills.contains(feet) || fills.contains(feet.above()) || target.equals(feet.below())) {
            return null;
        }
        PlacementModel.Option option = null;
        if (options == null) {
            if (PlacementModel.eye(feet).distanceTo(Vec3.atCenterOf(target)) > PlacementModel.REACH) {
                return null;
            }
        } else {
            for (PlacementModel.Option candidate : options) {
                if (candidate.inReachFrom(feet)) {
                    option = candidate;
                    break;
                }
            }
            if (option == null) {
                return null;
            }
        }
        String how = standing(world, feet, prepare);
        return how == null ? null : new Choice(feet, how, option);
    }

    /**
     * @return How to come to stand at {@code feet}: "stand" as it is, "dig N" with N blocks to dig out first, "pillar N"
     * with N blocks to pillar up; null if none of that works (or, without {@code prepare}, if it isn't standable now)
     */
    private String standing(VirtualWorld world, BlockPos feet, boolean prepare) {
        boolean floor = MovementHelper.canWalkOn(world, feet.getX(), feet.getY() - 1, feet.getZ());
        boolean body = MovementHelper.canWalkThrough(world, feet.getX(), feet.getY(), feet.getZ())
                && MovementHelper.canWalkThrough(world, feet.getX(), feet.getY() + 1, feet.getZ());
        if (floor && body) {
            return "stand";
        }
        if (!prepare) {
            return null;
        }
        if (floor) {
            int dig = 0;
            for (BlockPos cell : new BlockPos[]{feet, feet.above()}) {
                if (MovementHelper.canWalkThrough(world, cell.getX(), cell.getY(), cell.getZ())) {
                    continue;
                }
                BlockState state = world.get(cell);
                BlockState desired = desired(world, cell);
                if (!state.getFluidState().isEmpty() || state.getDestroySpeed(world.level(), cell) < 0 || nextToLiquid(world, cell)
                        || (desired != null && !(desired.getBlock() instanceof AirBlock)
                        && (BlockRules.valid(state, desired, false) || BlockRules.completable(state, desired)))) {
                    return null; // can't, or shouldn't: part of the build
                }
                dig++;
            }
            return "dig " + dig;
        }
        if (!body || throwaway == null) {
            return null;
        }
        // pillar up: open all the way down to something to stand on, through cells where nothing is wanted
        int height = 0;
        BlockPos cell = feet.below();
        while (!MovementHelper.canWalkOn(world, cell.getX(), cell.getY(), cell.getZ())) {
            BlockState desired = desired(world, cell);
            if (++height > MAX_PILLAR || !replaceable(world, cell, world.get(cell)) || !world.get(cell).getFluidState().isEmpty()
                    || (desired != null && !(desired.getBlock() instanceof AirBlock))) {
                return null;
            }
            cell = cell.below();
        }
        return "pillar " + height;
    }

    /**
     * Puts what getting to the chosen place does into the planned world: blocks dug out, a pillar put up
     */
    private void commit(VirtualWorld world, Choice choice) {
        if (choice.how.startsWith("dig")) {
            world.set(choice.stand, Blocks.AIR.defaultBlockState());
            world.set(choice.stand.above(), Blocks.AIR.defaultBlockState());
        } else if (choice.how.startsWith("pillar")) {
            int height = Integer.parseInt(choice.how.substring("pillar ".length()));
            for (int i = 1; i <= height; i++) {
                BlockPos cell = choice.stand.below(i);
                world.set(cell, throwaway);
                temporary.add(cell.immutable());
            }
        }
    }
}
