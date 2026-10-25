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

package baritone.utils;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.TickEvent;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.Helper;
import baritone.api.utils.input.Input;
import baritone.behavior.Behavior;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * An interface with the game's control system allowing the ability to
 * force down certain controls, having the same effect as if we were actually
 * physically forcing down the assigned key.
 *
 * @author Brady
 * @since 7/31/2018
 */
public final class InputOverrideHandler extends Behavior implements IInputOverrideHandler {

    /**
     * Maps inputs to whether or not we are forcing their state down.
     */
    private final Map<Input, Boolean> inputForceStateMap = new HashMap<>();

    private final BlockBreakHelper blockBreakHelper;
    private final BlockPlaceHelper blockPlaceHelper;

    /**
     * Falling Trees fells a whole tree when one of its logs is broken; the mod is an optional dependency
     */
    private static final boolean FALLING_TREES = classExists("me.pandamods.fallingtrees.FallingTrees");
    private static final TagKey<Block> C_LOGS = TagKey.create(Registries.BLOCK, new ResourceLocation("c", "logs"));

    private int crouchedTicks;
    private BlockPos veinCheckedPos; // the block last checked for an unsafe vein, and the result
    private boolean veinUnsafe;

    public InputOverrideHandler(Baritone baritone) {
        super(baritone);
        this.blockBreakHelper = new BlockBreakHelper(baritone.getPlayerContext());
        this.blockPlaceHelper = new BlockPlaceHelper(baritone.getPlayerContext());
    }

    /**
     * Returns whether or not we are forcing down the specified {@link Input}.
     *
     * @param input The input
     * @return Whether or not it is being forced down
     */
    @Override
    public final boolean isInputForcedDown(Input input) {
        return input == null ? false : this.inputForceStateMap.getOrDefault(input, false);
    }

    /**
     * Sets whether or not the specified {@link Input} is being forced down.
     *
     * @param input  The {@link Input}
     * @param forced Whether or not the state is being forced
     */
    @Override
    public final void setInputForceState(Input input, boolean forced) {
        this.inputForceStateMap.put(input, forced);
    }

    /**
     * Clears the override state for all keys
     */
    @Override
    public final void clearAllKeys() {
        this.inputForceStateMap.clear();
    }

    @Override
    public final void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            return;
        }
        if (isInputForcedDown(Input.CLICK_LEFT)) {
            setInputForceState(Input.CLICK_RIGHT, false);
        }
        boolean breaking = isInputForcedDown(Input.CLICK_LEFT);
        if (!breaking) {
            veinCheckedPos = null; // recheck next time: the vein may have changed meanwhile
        }
        if (breaking && (wouldVeinMineUnsafely() || wouldFellTree())) {
            // Vein Mining would also break blocks we never checked, or Falling Trees would fell a whole tree that's
            // merely in the way: both stay off while sneaking, so sneak and only this block breaks
            setInputForceState(Input.SNEAK, true);
            breaking = crouchedTicks >= VeinMiningCompat.SUPPRESS_TICKS;
        }
        crouchedTicks = ctx.player().isCrouching() ? crouchedTicks + 1 : 0;
        blockBreakHelper.tick(breaking);
        blockPlaceHelper.tick(isInputForcedDown(Input.CLICK_RIGHT));

        if (inControl()) {
            if (ctx.player().input.getClass() != PlayerMovementInput.class) {
                ctx.player().input = new PlayerMovementInput(this);
            }
        } else {
            if (ctx.player().input.getClass() == PlayerMovementInput.class) { // allow other movement inputs that aren't this one, e.g. for a freecam
                ctx.player().input = new KeyboardInput(ctx.minecraft().options);
            }
        }
        // only set it if it was previously incorrect
        // gotta do it this way, or else it constantly thinks you're beginning a double tap W sprint lol
    }

    /**
     * @return Whether the block we're about to hit would make Falling Trees fell its whole tree, and it isn't what
     * we're mining (felling a tree being mined gets all of it at once)
     */
    private boolean wouldFellTree() {
        HitResult trace = ctx.objectMouseOver();
        if (!Baritone.settings().accountForFallingTrees.value || !FALLING_TREES || !(trace instanceof BlockHitResult) || trace.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockState state = ctx.world().getBlockState(((BlockHitResult) trace).getBlockPos());
        if (baritone.getMineProcess().isTarget(state)) {
            return false;
        }
        // as configured in Homestead: logs only fall to an axe; huge mushrooms to anything. Cactus, bamboo and chorus
        // already drop everything above them without the mod
        if (state.is(BlockTags.LOGS) || state.is(C_LOGS)) {
            return ctx.player().getMainHandItem().is(ItemTags.AXES);
        }
        Block block = state.getBlock();
        return block == Blocks.MUSHROOM_STEM || block == Blocks.BROWN_MUSHROOM_BLOCK || block == Blocks.RED_MUSHROOM_BLOCK;
    }

    /**
     * @return Whether the block we're about to hit would make Vein Mining also break a block that's unsafe to break:
     * one touching liquid (or otherwise avoided by {@link MovementHelper#avoidBreaking}), or one we're standing on
     */
    private boolean wouldVeinMineUnsafely() {
        HitResult trace = ctx.objectMouseOver();
        if (!Baritone.settings().accountForVeinMining.value || !(trace instanceof BlockHitResult) || trace.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos target = ((BlockHitResult) trace).getBlockPos();
        if (target.equals(veinCheckedPos)) {
            return veinUnsafe;
        }
        veinCheckedPos = target;
        veinUnsafe = false;
        Set<BlockPos> vein = VeinMiningCompat.vein(ctx.player().getMainHandItem(), target, ctx.world()::getBlockState);
        if (vein.isEmpty()) {
            return false;
        }
        AABB box = ctx.player().getBoundingBox();
        int supportY = Mth.floor(box.minY - 0.01);
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        for (BlockPos pos : vein) {
            boolean support = pos.getY() == supportY
                    && pos.getX() >= Mth.floor(box.minX) && pos.getX() <= Mth.floor(box.maxX)
                    && pos.getZ() >= Mth.floor(box.minZ) && pos.getZ() <= Mth.floor(box.maxZ);
            if (support || MovementHelper.avoidBreaking(bsi, pos.getX(), pos.getY(), pos.getZ(), bsi.get0(pos))) {
                Helper.HELPER.logDebug("Vein Mining would also break " + vein.size() + " blocks, unsafely at " + pos + ": sneaking to break only " + target);
                veinUnsafe = true;
                break;
            }
        }
        return veinUnsafe;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, InputOverrideHandler.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private boolean inControl() {
        for (Input input : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SNEAK, Input.JUMP}) {
            if (isInputForcedDown(input)) {
                return true;
            }
        }
        // if we are not primary (a bot) we should set the movementinput even when idle (not pathing)
        return baritone.getPathingBehavior().isPathing() || baritone != BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    public BlockBreakHelper getBlockBreakHelper() {
        return blockBreakHelper;
    }
}
