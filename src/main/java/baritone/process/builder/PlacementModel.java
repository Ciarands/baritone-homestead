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

import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.accessor.IBlockItem;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * How to place a block so it comes out as wanted. Placing is a click on a face of a block next to the target, with a
 * rotation sent first: the rotation sets the facing, the clicked face and where on it the rest (axis, top or bottom
 * half, a door's hinge side, what it's attached to). The server doesn't check that the face can be seen, only the
 * distance, so where we stand doesn't matter beyond reach (see EXPERIMENTS.md in BaritoneSchematicExperiments).
 * <p>
 * The clicks that work are searched for with the game's own placement logic, at the target, in the planned world: the
 * block's neighbours matter (a plant needs earth under it, a door's hinge goes by what's beside it, a chest pairs with
 * the one next to it). Found clicks are kept by the target's neighbourhood (the wanted state and the six blocks around
 * it), so a wall of the same block is searched once; every use is still checked at the target itself.
 */
public final class PlacementModel {

    /**
     * Distance from the eye to the centre of the clicked block. The server allows 6; staying well inside it
     */
    public static final double REACH = 4.5;
    /**
     * Eye height while sneaking: we sneak to place, so clicking a chest or door places against it instead of using it
     */
    public static final double SNEAK_EYE = 1.27;

    private static final float[] YAWS = new float[16];
    private static final float[] PITCHES = {0, 80, -80};
    /**
     * Where on a face to click, across it: the middle, and either side of it on each axis (slab halves, stair halves,
     * door hinges go by where the face is clicked)
     */
    private static final double[][] FACE_POINTS = {{0.5, 0.5}, {0.25, 0.5}, {0.75, 0.5}, {0.5, 0.25}, {0.5, 0.75}};

    static {
        for (int i = 0; i < YAWS.length; i++) {
            YAWS[i] = i * 22.5f;
        }
    }

    /**
     * Click the face of the block on side {@code toSupport} of the target, at {@code hit} (relative to the target's
     * corner, on the face between them), having turned to {@code yaw}, {@code pitch}
     */
    private static final class Recipe {

        final Direction toSupport;
        final Vec3 hit;
        final float yaw;
        final float pitch;

        Recipe(Direction toSupport, Vec3 hit, float yaw, float pitch) {
            this.toSupport = toSupport;
            this.hit = hit;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    /**
     * A click that places the wanted block at {@code target}
     */
    public static final class Option {

        public final BlockPos target;
        public final BlockPos clicked;
        public final Direction face;
        public final Vec3 hit;
        public final float yaw;
        public final float pitch;
        /**
         * What it places
         */
        public final BlockState result;
        /**
         * What to click with (a bamboo item places a sapling, a torch item a wall torch)
         */
        public final Item item;

        Option(BlockPos target, BlockPos clicked, Direction face, Vec3 hit, float yaw, float pitch, BlockState result, Item item) {
            this.target = target;
            this.clicked = clicked;
            this.face = face;
            this.hit = hit;
            this.yaw = yaw;
            this.pitch = pitch;
            this.result = result;
            this.item = item;
        }

        public BlockHitResult hitResult() {
            return new BlockHitResult(hit, face, clicked, false);
        }

        /**
         * @return Whether it can be done standing with feet at {@code feet} (sneaking)
         */
        public boolean inReachFrom(BlockPos feet) {
            return eye(feet).distanceTo(Vec3.atCenterOf(clicked)) <= REACH;
        }

        @Override
        public String toString() {
            return String.format("click %s %s at (%.2f,%.2f,%.2f) yaw %.1f pitch %.0f", clicked.toShortString(), face,
                    hit.x - clicked.getX(), hit.y - clicked.getY(), hit.z - clicked.getZ(), yaw, pitch);
        }
    }

    public static Vec3 eye(BlockPos feet) {
        return new Vec3(feet.getX() + 0.5, feet.getY() + SNEAK_EYE, feet.getZ() + 0.5);
    }

    private final IPlayerContext ctx;
    private final Map<List<BlockState>, List<Recipe>> recipes = new HashMap<>();
    private long searches;
    private long simulations;

    public PlacementModel(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * @return The item that places {@code desired} (a torch item for a wall torch too), or null if no item does
     * (liquids, fire, portals, potted plants...)
     */
    public static Item itemFor(BlockState desired) {
        BlockState base = BlockRules.baseOf(desired);
        Item item = (base != null ? base : desired).getBlock().asItem();
        return item instanceof BlockItem ? item : null;
    }

    public String stats() {
        return recipes.size() + " neighbourhoods, " + searches + " searches, " + simulations + " simulated clicks";
    }

    /**
     * @return The clicks that place {@code desired} at {@code target} in {@code world} as it is, with something to
     * click already there; supports below first, then the sides, then above
     */
    public List<Option> options(BlockPos target, BlockState desired, VirtualWorld world) {
        Item item = itemFor(desired);
        if (item == null) {
            return Collections.emptyList();
        }
        ItemStack stack = new ItemStack(item);
        List<BlockState> key = neighbourhood(target, desired, world);
        List<Recipe> known = recipes.get(key);
        if (known == null) {
            known = search(target, desired, world, stack);
            if (!known.isEmpty()) {
                // nothing found isn't kept: something further out can change that (a support placed beyond the
                // neighbours, a block that held it off removed)
                recipes.put(key, known);
            }
            return check(target, desired, world, stack, known);
        }
        List<Option> found = check(target, desired, world, stack, known);
        if (found.isEmpty()) {
            // something further out matters here (a door's hinge goes by what's beside the upper half too)
            found = check(target, desired, world, stack, search(target, desired, world, stack));
        }
        return found;
    }

    private static List<BlockState> neighbourhood(BlockPos target, BlockState desired, VirtualWorld world) {
        List<BlockState> key = new ArrayList<>(7);
        key.add(desired);
        for (Direction dir : Direction.values()) {
            key.add(world.get(target.relative(dir)));
        }
        return key;
    }

    private List<Option> check(BlockPos target, BlockState desired, VirtualWorld world, ItemStack stack, List<Recipe> candidates) {
        List<Option> found = new ArrayList<>();
        for (Recipe recipe : candidates) {
            BlockPos clicked = target.relative(recipe.toSupport);
            if (!clickable(world, clicked)) {
                continue;
            }
            Vec3 hit = Vec3.atLowerCornerOf(target).add(recipe.hit);
            BlockState result = simulate(world.level(), stack, new BlockHitResult(hit, recipe.toSupport.getOpposite(), clicked, false), recipe.yaw, recipe.pitch, target);
            if (result != null && placesRight(result, desired, target, world)) {
                found.add(new Option(target, clicked, recipe.toSupport.getOpposite(), hit, recipe.yaw, recipe.pitch, result, stack.getItem()));
            }
        }
        // exactly the wanted state first: what counts as right is lenient about properties the world may change later
        // (a vine's sides, a fence's connections), but placing can still get them as wanted
        found.sort(Comparator.comparingInt(option -> differences(option.result, desired)));
        return found;
    }

    private static int differences(BlockState result, BlockState desired) {
        if (result.getBlock() != desired.getBlock()) {
            return Integer.MAX_VALUE;
        }
        int n = 0;
        for (net.minecraft.world.level.block.state.properties.Property<?> prop : result.getProperties()) {
            if (!result.getValue(prop).equals(desired.getValue(prop))) {
                n++;
            }
        }
        return n;
    }

    private static boolean clickable(VirtualWorld world, BlockPos pos) {
        BlockState state = world.get(pos);
        // clicking something replaceable (grass, snow, water) places into it instead
        return !MovementHelper.isReplaceable(pos.getX(), pos.getY(), pos.getZ(), state, world) && !state.getShape(world.level(), pos).isEmpty();
    }

    /**
     * Every side there's something to click, every point across the face, every rotation: for each side and point, the
     * rotation in the middle of the widest range that places it right. What's sent is never quite what's asked for
     * (look randomisation, mouse steps), and the edge of a range (yaw 45, between south and west) would come out
     * either way.
     */
    private List<Recipe> search(BlockPos target, BlockState desired, VirtualWorld world, ItemStack stack) {
        searches++;
        List<Recipe> found = new ArrayList<>();
        for (Direction toSupport : ORDER) {
            BlockPos clicked = target.relative(toSupport);
            if (!clickable(world, clicked)) {
                continue;
            }
            for (double[] point : FACE_POINTS) {
                Vec3 hit = facePoint(toSupport, point);
                BlockHitResult hitResult = new BlockHitResult(Vec3.atLowerCornerOf(target).add(hit), toSupport.getOpposite(), clicked, false);
                float bestYaw = 0;
                float bestPitch = 0;
                int bestRun = 0;
                for (float pitch : PITCHES) {
                    boolean[] works = new boolean[YAWS.length];
                    for (int i = 0; i < YAWS.length; i++) {
                        BlockState result = simulate(world.level(), stack, hitResult, YAWS[i], pitch, target);
                        works[i] = result != null && placesRight(result, desired, target, world);
                    }
                    int[] run = longestRun(works);
                    if (run[1] > bestRun) {
                        bestRun = run[1];
                        bestPitch = pitch;
                        bestYaw = run[1] == YAWS.length ? 0 : (run[0] * 22.5f + (run[1] - 1) * 11.25f) % 360;
                    }
                    if (bestRun == YAWS.length) {
                        break; // any rotation does
                    }
                }
                if (bestRun > 0) {
                    found.add(new Recipe(toSupport, hit, bestYaw, bestPitch));
                }
            }
        }
        return found;
    }

    private static final Direction[] ORDER = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};

    /**
     * @return The point at {@code point} across the face of the target towards {@code toSupport}, relative to the
     * target's corner
     */
    private static Vec3 facePoint(Direction toSupport, double[] point) {
        double along = 0.5 + 0.5 * (toSupport.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1);
        switch (toSupport.getAxis()) {
            case X:
                return new Vec3(along, point[0], point[1]);
            case Y:
                return new Vec3(point[0], along, point[1]);
            default:
                return new Vec3(point[0], point[1], along);
        }
    }

    /**
     * @return For working out why nothing places it: what each side's block is and what clicking it would place
     */
    public String explain(BlockPos target, BlockState desired, VirtualWorld world) {
        Item item = itemFor(desired);
        if (item == null) {
            return "no item places it";
        }
        ItemStack stack = new ItemStack(item);
        StringBuilder out = new StringBuilder();
        for (Direction toSupport : ORDER) {
            BlockPos clicked = target.relative(toSupport);
            BlockState there = world.get(clicked);
            out.append(toSupport).append(": ").append(BuildExecutor.s(there));
            if (!clickable(world, clicked)) {
                out.append(" (nothing to click); ");
                continue;
            }
            Set<String> results = new TreeSet<>();
            for (double[] point : FACE_POINTS) {
                BlockHitResult hit = new BlockHitResult(Vec3.atLowerCornerOf(target).add(facePoint(toSupport, point)), toSupport.getOpposite(), clicked, false);
                for (float pitch : PITCHES) {
                    for (float yaw : new float[]{0, 90, 180, 270}) {
                        results.add(BuildExecutor.s(simulate(world.level(), stack, hit, yaw, pitch, target)));
                    }
                }
            }
            out.append(" -> ").append(results).append("; ");
        }
        return out.toString();
    }

    /**
     * @return The click that makes the single slab at {@code target} the wanted double one: the same slab placed onto
     * its open face (the top of a bottom slab)
     */
    public Option finishSlab(BlockPos target, BlockState current, BlockState desired, VirtualWorld world) {
        Item item = itemFor(desired);
        if (item == null || !BlockRules.needsSecondSlab(current, desired)) {
            return null;
        }
        Direction face = current.getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? Direction.UP : Direction.DOWN;
        float pitch = face == Direction.UP ? 80 : -80;
        Vec3 hit = Vec3.atCenterOf(target);
        BlockState result = simulate(world.level(), new ItemStack(item), new BlockHitResult(hit, face, target, false), 0, pitch, target);
        if (result == null || result.getBlock() != desired.getBlock() || result.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) {
            return null;
        }
        return new Option(target, target, face, hit, 0, pitch, result, item);
    }

    /**
     * @return Whether placing {@code result} at {@code target} is right for {@code desired}. The second of a double
     * chest has to pair with the first, which is there by then; only the first goes down single.
     */
    private static boolean placesRight(BlockState result, BlockState desired, BlockPos target, VirtualWorld world) {
        if (!BlockRules.valid(result, desired, true)) {
            return false;
        }
        if (desired.getBlock() instanceof ChestBlock && desired.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && result.getValue(ChestBlock.TYPE) != desired.getValue(ChestBlock.TYPE)) {
            return world.get(target.relative(ChestBlock.getConnectedDirection(desired))).getBlock() != desired.getBlock();
        }
        return true;
    }

    /**
     * @return Start and length of the longest run of {@code true}, going round
     */
    private static int[] longestRun(boolean[] works) {
        int n = works.length;
        int gap = -1;
        for (int i = 0; i < n; i++) {
            if (!works[i]) {
                gap = i;
                break;
            }
        }
        if (gap < 0) {
            return new int[]{0, n};
        }
        int bestStart = 0;
        int bestLength = 0;
        int length = 0;
        // once round, starting just after a gap, so no run is cut in two
        for (int k = 1; k <= n; k++) {
            int i = (gap + k) % n;
            if (works[i]) {
                length++;
                if (length > bestLength) {
                    bestLength = length;
                    bestStart = (i - length + 1 + n) % n;
                }
            } else {
                length = 0;
            }
        }
        return new int[]{bestStart, bestLength};
    }

    /**
     * @return What the game would place at {@code target}, clicking with {@code stack} as {@code hit} says, turned to
     * {@code yaw}, {@code pitch}, sneaking; null if nothing, or if it would go anywhere else (the same slab clicked on a
     * single slab's side completes that slab, it doesn't go next to it)
     */
    BlockState simulate(Level level, ItemStack stack, BlockHitResult hit, float yaw, float pitch, BlockPos target) {
        simulations++;
        LocalPlayer player = ctx.player();
        float yaw0 = player.getYRot();
        float pitch0 = player.getXRot();
        try {
            player.setYRot(yaw);
            player.setXRot(pitch);
            BlockPlaceContext context = new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, stack, hit) {
                @Override
                public boolean isSecondaryUseActive() {
                    return true;
                }
            };
            if (!context.getClickedPos().equals(target) || !context.canPlace()) {
                return null;
            }
            return ((IBlockItem) stack.getItem()).placementStateInvoker(context);
        } finally {
            player.setYRot(yaw0);
            player.setXRot(pitch0);
        }
    }
}
