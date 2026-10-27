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

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * A {@link VirtualWorld} seen as a {@link Level}, so the game's own placement logic (which takes a level) can be asked
 * what a click would place in a world that isn't there yet. Only what placement reads is answered: blocks, fluids,
 * heights and registries; there are no entities or block entities, and nothing is ever obstructed (standing clear of
 * what's placed is the planner's business). What placing does besides the clicked block goes into the planned world,
 * as in the game: a door's upper half, a bed's head, and the neighbours adjusting their shapes (fences connecting, a
 * chest pairing up). The rest of what a level does on a change (redstone updates, rendering, sounds, events) doesn't
 * apply.
 * <p>
 * Never constructed: a level's constructor sets up chunk sources, renderers and the like, none of which this needs, so
 * the instance is allocated bare and only the overridden methods are ever used.
 */
final class PlanLevel extends ClientLevel {

    private static final Object UNSAFE;
    private static final Method ALLOCATE;

    static {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = field.get(null);
            ALLOCATE = unsafeClass.getMethod("allocateInstance", Class.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Can't set up planning levels", e);
        }
    }

    private VirtualWorld world;
    private Level real;

    @SuppressWarnings("ConstantConditions")
    private PlanLevel() {
        super(null, null, null, null, 0, 0, null, null, false, 0); // never called, see the class comment
    }

    static PlanLevel of(VirtualWorld world, Level real) {
        try {
            PlanLevel level = (PlanLevel) ALLOCATE.invoke(UNSAFE, PlanLevel.class);
            level.world = world;
            level.real = real;
            return level;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Can't make a planning level", e);
        }
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return world.get0(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public boolean isUnobstructed(BlockState state, BlockPos pos, CollisionContext context) {
        return true;
    }

    @Override
    public List<Entity> getEntities(Entity except, AABB area, Predicate<? super Entity> predicate) {
        return Collections.emptyList();
    }

    @Override
    public DimensionType dimensionType() {
        return real.dimensionType();
    }

    @Override
    public RegistryAccess registryAccess() {
        return real.registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return real.enabledFeatures();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return real.getWorldBorder();
    }

    @Override
    public ClientLevelData getLevelData() {
        return ((ClientLevel) real).getLevelData(); // block ticks scheduled by shape updates read the game time
    }

    @Override
    public net.minecraft.util.RandomSource getRandom() {
        return real.getRandom(); // sound pitches when opening a door and the like
    }

    /**
     * As the game does it: the block, and then the neighbours adjust their shapes to it (fences connect, a chest
     * pairs up with the one placed next to it, a door's halves stay together, something that can't stay any more
     * pops off)
     */
    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        BlockState old = getBlockState(pos);
        if (old == state) {
            return false;
        }
        world.set(pos.immutable(), state);
        if ((flags & 16) == 0 && recursionLeft > 0) {
            int neighbourFlags = flags & -34;
            old.updateIndirectNeighbourShapes(this, pos, neighbourFlags, recursionLeft - 1);
            state.updateNeighbourShapes(this, pos, neighbourFlags, recursionLeft - 1);
            state.updateIndirectNeighbourShapes(this, pos, neighbourFlags, recursionLeft - 1);
        }
        return true;
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean drop, Entity breaker, int recursionLeft) {
        world.set(pos.immutable(), getBlockState(pos).getFluidState().createLegacyBlock());
        return true;
    }

    @Override
    public void updateNeighborsAt(BlockPos pos, Block block) {}

    @Override
    public void neighborShapeChanged(Direction direction, BlockState state, BlockPos pos, BlockPos neighborPos, int flags, int recursionLeft) {
        NeighborUpdater.executeShapeUpdate(this, direction, state, pos, neighborPos, flags, recursionLeft);
    }

    @Override
    public void updateNeighbourForOutputSignal(BlockPos pos, Block block) {}

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {}

    @Override
    public void setBlocksDirty(BlockPos pos, BlockState oldState, BlockState newState) {}

    @Override
    public void levelEvent(Player player, int type, BlockPos pos, int data) {}

    @Override
    public void gameEvent(GameEvent event, Vec3 position, GameEvent.Context context) {}

    @Override
    public void playSound(Player player, double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch) {}

    @Override
    public void playSound(Player player, Entity entity, SoundEvent sound, SoundSource source, float volume, float pitch) {}
}
