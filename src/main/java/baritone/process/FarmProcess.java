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
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IFarmProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.SettingsUtil;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public final class FarmProcess extends BaritoneProcessHelper implements IFarmProcess {

    private boolean active;

    private List<BlockPos> locations;
    private int tickCount;

    private int range;
    private BlockPos center;

    private static final List<Item> FARMLAND_PLANTABLE = Arrays.asList(
            Items.BEETROOT_SEEDS,
            Items.MELON_SEEDS,
            Items.WHEAT_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.POTATO,
            Items.CARROT
    );

    private static final List<Item> PICKUP_DROPPED = Arrays.asList(
            Items.BEETROOT_SEEDS,
            Items.BEETROOT,
            Items.MELON_SEEDS,
            Items.MELON_SLICE,
            Blocks.MELON.asItem(),
            Items.WHEAT_SEEDS,
            Items.WHEAT,
            Items.PUMPKIN_SEEDS,
            Blocks.PUMPKIN.asItem(),
            Items.POTATO,
            Items.CARROT,
            Items.NETHER_WART,
            Items.COCOA_BEANS,
            Blocks.SUGAR_CANE.asItem(),
            Blocks.BAMBOO.asItem(),
            Blocks.CACTUS.asItem()
    );

    /**
     * A full inventory goes into Tom's Simple Storage, but for a stack of each thing planted and of bone meal
     * (depositToTomsSimpleStorageWirelessTerminal)
     */
    private final FullInventoryDeposit deposit;

    public FarmProcess(Baritone baritone) {
        super(baritone);
        this.deposit = new FullInventoryDeposit(baritone);
    }

    /**
     * @return What stays when the inventory is deposited: a stack of each thing that's planted, and of bone meal
     */
    private static Map<Object, Integer> depositKeep() {
        Map<Object, Integer> keep = new HashMap<>();
        for (Item item : FARMLAND_PLANTABLE) {
            keep.put(item, 64);
        }
        keep.put(Items.NETHER_WART, 64);
        keep.put(Items.COCOA_BEANS, 64);
        keep.put(Items.BONE_MEAL, 64);
        return keep;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void farm(int range, BlockPos pos) {
        if (pos == null) {
            center = baritone.getPlayerContext().playerFeet();
        } else {
            center = pos;
        }
        this.range = range;
        active = true;
        locations = null;
        rightClicks.clear();
        boneMealTargets = null;
    }

    private enum Harvest {
        WHEAT((CropBlock) Blocks.WHEAT),
        CARROTS((CropBlock) Blocks.CARROTS),
        POTATOES((CropBlock) Blocks.POTATOES),
        BEETROOT((CropBlock) Blocks.BEETROOTS),
        PUMPKIN(Blocks.PUMPKIN, state -> true),
        MELON(Blocks.MELON, state -> true),
        NETHERWART(Blocks.NETHER_WART, state -> state.getValue(NetherWartBlock.AGE) >= 3),
        COCOA(Blocks.COCOA, state -> state.getValue(CocoaBlock.AGE) >= 2),
        SUGARCANE(Blocks.SUGAR_CANE, null) {
            @Override
            public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.below()).getBlock() instanceof SugarCaneBlock;
                }
                return true;
            }
        },
        BAMBOO(Blocks.BAMBOO, null) {
            @Override
            public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.below()).getBlock() instanceof BambooStalkBlock;
                }
                return true;
            }
        },
        CACTUS(Blocks.CACTUS, null) {
            @Override
            public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.below()).getBlock() instanceof CactusBlock;
                }
                return true;
            }
        };
        public final Block block;
        public final Predicate<BlockState> readyToHarvest;

        Harvest(CropBlock blockCrops) {
            this(blockCrops, blockCrops::isMaxAge);
            // max age is 7 for wheat, carrots, and potatoes, but 3 for beetroot
        }

        Harvest(Block block, Predicate<BlockState> readyToHarvest) {
            this.block = block;
            this.readyToHarvest = readyToHarvest;
        }

        public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
            return readyToHarvest.test(state);
        }
    }

    private boolean readyForHarvest(Level world, BlockPos pos, BlockState state) {
        for (Harvest harvest : Harvest.values()) {
            if (harvest.block == state.getBlock()) {
                return harvest.readyToHarvest(world, pos, state);
            }
        }
        return false;
    }

    private boolean isPlantable(ItemStack stack) {
        return FARMLAND_PLANTABLE.contains(stack.getItem());
    }

    private boolean isBoneMeal(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.BONE_MEAL);
    }

    private boolean isNetherWart(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.NETHER_WART);
    }

    private boolean isCocoa(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.COCOA_BEANS);
    }

    /**
     * The Right Click Harvest mod's blocks it leaves alone
     */
    private static final TagKey<Block> RIGHT_CLICK_HARVEST_BLACKLIST = TagKey.create(Registries.BLOCK, new ResourceLocation("rightclickharvest", "blacklist"));
    /**
     * A ripe crop still there this long after its first right-click is broken instead (the server wants a hoe we don't
     * have, or food we don't have: its config isn't known here)
     */
    private static final int RIGHT_CLICK_GIVE_UP_TICKS = 40;
    /**
     * Until the server has answered, a right-click or bone meal isn't sent again for this long
     */
    private static final int USE_RESEND_TICKS = 5;
    /**
     * Right-clicks and bone meal sent by packet in a tick at most
     */
    private static final int USES_PER_TICK = 16;
    /**
     * Ripe crops right-clicked: when first, when last
     */
    private final Map<BlockPos, long[]> rightClicks = new HashMap<>();
    /**
     * Crops bone meal was sent to: when
     */
    private final Map<BlockPos, Long> boneMealSent = new HashMap<>();
    /**
     * The blocks bone meal goes on (worked out once a farm has bone meal)
     */
    private Set<Block> boneMealTargets;
    private static Field rightClickHarvestHello;
    private static boolean noRightClickHarvest;

    /**
     * @return Whether right-clicking harvests this ripe crop and replants it (the Right Click Harvest mod), rather than
     * it having to be broken and planted again
     */
    private boolean rightClickHarvests(BlockPos pos, BlockState state, long now) {
        Block block = state.getBlock();
        long[] clicked = rightClicks.get(pos);
        return (block instanceof CropBlock || block instanceof CocoaBlock || block instanceof NetherWartBlock)
                && !state.is(RIGHT_CLICK_HARVEST_BLACKLIST) && (clicked == null || now - clicked[0] < RIGHT_CLICK_GIVE_UP_TICKS)
                && rightClickHarvestOnServer();
    }

    /**
     * Right-clicks a block with what's in hand by sending the packet a click would: no need to look at it or stand by
     * it, the server only wants it within reach.
     */
    private void sendUse(BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        ((IPlayerControllerMP) Minecraft.getInstance().gameMode).callStartPrediction(ctx.player().clientLevel,
                sequence -> new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, sequence));
    }

    /**
     * @return Bone meal uses that grow it all the way, counting one stage each (most give more): what's sent beyond
     * that isn't used up, the server only uses bone meal on what can still grow
     */
    private static int boneMealUses(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock) {
            return Math.max(1, ((CropBlock) block).getMaxAge() - ((CropBlock) block).getAge(state));
        }
        if (block instanceof CocoaBlock) {
            return Math.max(1, 2 - state.getValue(CocoaBlock.AGE));
        }
        return 1;
    }

    /**
     * @return Whether the server runs Right Click Harvest too (it greets clients that have it; the integrated server as well)
     */
    private static boolean rightClickHarvestOnServer() {
        if (noRightClickHarvest) {
            return false;
        }
        try {
            if (rightClickHarvestHello == null) {
                Field hello = Class.forName("io.github.jamalam360.rightclickharvest.RightClickHarvestClient").getDeclaredField("serverHasSaidHello");
                hello.setAccessible(true);
                rightClickHarvestHello = hello;
            }
            return rightClickHarvestHello.getBoolean(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            noRightClickHarvest = true; // not installed
            return false;
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        PathingCommand depositing = deposit.tick(isSafeToCancel, ItemStack::getItem, FarmProcess::depositKeep, this::logDirect);
        if (depositing != null) {
            return depositing;
        }
        if (Baritone.settings().mineGoalUpdateInterval.value != 0 && tickCount++ % Baritone.settings().mineGoalUpdateInterval.value == 0) {
            Set<Block> scan = new LinkedHashSet<>();
            for (Harvest harvest : Harvest.values()) {
                scan.add(harvest.block);
            }
            if (Baritone.settings().replantCrops.value) {
                scan.add(Blocks.FARMLAND);
                scan.add(Blocks.JUNGLE_LOG);
                if (Baritone.settings().replantNetherWart.value) {
                    scan.add(Blocks.SOUL_SAND);
                }
            }
            if (baritone.getInventoryBehavior().throwaway(false, this::isBoneMeal)) {
                // with bone meal, whatever grows from it is worth finding too (saplings, stems, sugar cane, ...)
                if (boneMealTargets == null) {
                    boneMealTargets = new HashSet<>(BoneMeal.targets());
                }
                scan.addAll(boneMealTargets);
            }

            List<Block> blocks = new ArrayList<>(scan);
            Baritone.getExecutor().execute(() -> locations = BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(ctx, blocks, Baritone.settings().farmMaxScanSize.value, 10, 10));
        }
        if (locations == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        List<BlockPos> toBreak = new ArrayList<>();
        List<BlockPos> openFarmland = new ArrayList<>();
        List<BlockPos> bonemealable = new ArrayList<>();
        List<BlockPos> openSoulsand = new ArrayList<>();
        List<BlockPos> openLog = new ArrayList<>();
        boolean hasImmatureCrop = false;
        for (BlockPos pos : locations) {
            //check if the target block is out of range.
            if (range != 0 && pos.distSqr(center) > range * range) {
                continue;
            }

            BlockState state = ctx.world().getBlockState(pos);
            boolean airAbove = ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock;
            if (state.getBlock() == Blocks.FARMLAND) {
                if (airAbove) {
                    openFarmland.add(pos);
                }
                continue;
            }
            if (state.getBlock() == Blocks.SOUL_SAND) {
                if (airAbove) {
                    openSoulsand.add(pos);
                }
                continue;
            }
            if (state.getBlock() == Blocks.JUNGLE_LOG) {
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (ctx.world().getBlockState(pos.relative(direction)).getBlock() instanceof AirBlock) {
                        openLog.add(pos);
                        break;
                    }
                }
                continue;
            }
            if (readyForHarvest(ctx.world(), pos, state)) {
                toBreak.add(pos);
                continue;
            }
            for (Harvest harvest : Harvest.values()) {
                if (harvest.block == state.getBlock()) {
                    hasImmatureCrop = true;
                    break;
                }
            }
            // the server decides whether a use takes (a sapling only sometimes grows): anything it's valid on gets it
            if (boneMealTargets != null && boneMealTargets.contains(state.getBlock()) && BoneMeal.grows(ctx.world(), pos, state)) {
                bonemealable.add(pos);
            }
        }

        baritone.getInputOverrideHandler().clearAllKeys();
        BetterBlockPos playerPos = ctx.playerFeet();
        double blockReachDistance = ctx.playerController().getBlockReachDistance();
        long now = ctx.world().getGameTime();
        rightClicks.keySet().removeIf(pos -> !readyForHarvest(ctx.world(), pos, ctx.world().getBlockState(pos)));
        int rightClicked = 0;
        for (BlockPos pos : toBreak) {
            if (rightClicked == USES_PER_TICK || ctx.player().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > blockReachDistance * blockReachDistance
                    || !rightClickHarvests(pos, ctx.world().getBlockState(pos), now)) {
                continue;
            }
            long[] clicked = rightClicks.computeIfAbsent(pos.immutable(), p -> new long[]{now, Long.MIN_VALUE / 2});
            if (now - clicked[1] < USE_RESEND_TICKS) {
                continue;
            }
            if (rightClicked == 0) {
                // a hoe meets requireHoe, and reaches the crops round it too
                baritone.getInventoryBehavior().throwaway(true, stack -> stack.getItem() instanceof HoeItem);
                ctx.playerController().syncHeldItem();
            }
            sendUse(pos);
            clicked[1] = now;
            rightClicked++;
        }
        if (rightClicked > 0) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        for (BlockPos pos : toBreak) {
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance || rightClickHarvests(pos, ctx.world().getBlockState(pos), now)) {
                continue; // (right-clicked crops are left to the server)
            }
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
            if (rot.isPresent() && isSafeToCancel) {
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                if (ctx.isLookingAt(pos)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        ArrayList<BlockPos> both = new ArrayList<>(openFarmland);
        both.addAll(openSoulsand);
        for (BlockPos pos : both) {
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            boolean soulsand = openSoulsand.contains(pos);
            Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5), blockReachDistance, false);
            if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, soulsand ? this::isNetherWart : this::isPlantable)) {
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), blockReachDistance);
                if (result instanceof BlockHitResult && ((BlockHitResult) result).getDirection() == Direction.UP) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    if (ctx.isLookingAt(pos)) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }
        for (BlockPos pos : openLog) {
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (!(ctx.world().getBlockState(pos.relative(dir)).getBlock() instanceof AirBlock)) {
                    continue;
                }
                Vec3 faceCenter = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(dir.getNormal()).scale(0.5));
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, faceCenter, blockReachDistance, false);
                if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, this::isCocoa)) {
                    HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), blockReachDistance);
                    if (result instanceof BlockHitResult && ((BlockHitResult) result).getDirection() == dir) {
                        baritone.getLookBehavior().updateTarget(rot.get(), true);
                        if (ctx.isLookingAt(pos)) {
                            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        }
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                }
            }
        }
        // bone meal by packet as well: on everything in reach at once, without looking, each enough to grow it all the way
        boneMealSent.values().removeIf(sent -> now - sent >= USE_RESEND_TICKS);
        int boneMealed = 0;
        for (BlockPos pos : bonemealable) {
            if (boneMealed >= USES_PER_TICK) {
                break;
            }
            if (ctx.player().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > blockReachDistance * blockReachDistance || boneMealSent.containsKey(pos)) {
                continue;
            }
            if (boneMealed == 0) {
                if (!baritone.getInventoryBehavior().throwaway(true, this::isBoneMeal)) {
                    break;
                }
                ctx.playerController().syncHeldItem();
            }
            for (int uses = boneMealUses(ctx.world().getBlockState(pos)); uses > 0; uses--) {
                sendUse(pos);
                boneMealed++;
            }
            boneMealSent.put(pos.immutable(), now);
        }
        if (boneMealed > 0) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (calcFailed) {
            logDirect("Farm failed");
            if (Baritone.settings().notificationOnFarmFail.value) {
                logNotification("Farm failed", true);
            }
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        List<Goal> goalz = new ArrayList<>();
        for (BlockPos pos : toBreak) {
            goalz.add(new GoalBreak(pos));
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isPlantable)) {
            for (BlockPos pos : openFarmland) {
                goalz.add(new GoalBlock(pos.above()));
            }
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isNetherWart)) {
            for (BlockPos pos : openSoulsand) {
                goalz.add(new GoalBlock(pos.above()));
            }
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isCocoa)) {
            for (BlockPos pos : openLog) {
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (ctx.world().getBlockState(pos.relative(direction)).getBlock() instanceof AirBlock) {
                        goalz.add(new GoalGetToBlock(pos.relative(direction)));
                    }
                }
            }
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isBoneMeal)) {
            for (BlockPos pos : bonemealable) {
                // bone meal goes on from within reach: no need to stand on it (on a sapling, the tree grows round us)
                goalz.add(new GoalNear(pos, 3));
            }
        }
        boolean dropsFalling = false;
        for (Entity entity : ctx.entities()) {
            if (!(entity instanceof ItemEntity) || !PICKUP_DROPPED.contains(((ItemEntity) entity).getItem().getItem())) {
                continue;
            }
            if (entity.onGround()) {
                // +0.1 because of farmland's 0.9375 dummy height lol
                goalz.add(new GoalBlock(new BetterBlockPos(entity.position().x, entity.position().y + 0.1, entity.position().z)));
            } else if (((ItemEntity) entity).getAge() < 40 && entity.distanceToSqr(ctx.player()) < 8 * 8) {
                dropsFalling = true; // just harvested, not landed yet
            }
        }
        if (goalz.isEmpty()) {
            if ((hasImmatureCrop && Baritone.settings().farmWaitForGrowth.value) || dropsFalling) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect("Farm failed");
            if (Baritone.settings().notificationOnFarmFail.value) {
                logNotification("Farm failed", true);
            }
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalComposite(goalz.toArray(new Goal[0])), PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        deposit.reset();
        active = false;
    }

    @Override
    public String displayName0() {
        return "Farming";
    }

    /**
     * Next to a block, to break it: anywhere adjacent, inside or below, but not right on top of it (it might not hold
     * us up, and then can't be broken)
     */
    private static final class GoalBreak extends GoalGetToBlock {

        GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return y <= this.y && super.isInGoal(x, y, z);
        }

        @Override
        public String toString() {
            return String.format("GoalBreak{x=%s,y=%s,z=%s}", SettingsUtil.maybeCensor(x), SettingsUtil.maybeCensor(y), SettingsUtil.maybeCensor(z));
        }
    }
}
