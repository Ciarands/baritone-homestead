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

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.utils.BlockStateInterface;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The world as it will be: what's there now, with the changes the plan makes so far laid over it. Readable by
 * Baritone's movement rules (it's a {@link BlockStateInterface}) and by the game's own placement logic (through
 * {@link #level()}), so a plan is checked with the same rules the game will apply, before anything is done. Changes
 * are journaled, so a tentative placement can be looked at and taken back.
 */
public final class VirtualWorld extends BlockStateInterface {

    private final Long2ObjectOpenHashMap<BlockState> overlay = new Long2ObjectOpenHashMap<>();
    private final LongArrayList journalKeys = new LongArrayList();
    private final List<BlockState> journalPrevious = new ArrayList<>();
    private final List<BlockPos> journalPositions = new ArrayList<>();
    private final Level real;
    private PlanLevel level;

    public VirtualWorld(IPlayerContext ctx) {
        super(ctx);
        this.real = ctx.world();
    }

    @Override
    public BlockState get0(int x, int y, int z) {
        BlockState planned = overlay.get(BetterBlockPos.longHash(x, y, z));
        return planned != null ? planned : super.get0(x, y, z);
    }

    public BlockState get(BlockPos pos) {
        return get0(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * What's there in the real world, whatever the plan does
     */
    public BlockState real(BlockPos pos) {
        return super.get0(pos.getX(), pos.getY(), pos.getZ());
    }

    public void set(BlockPos pos, BlockState state) {
        long key = BetterBlockPos.longHash(pos.getX(), pos.getY(), pos.getZ());
        journalKeys.add(key);
        journalPrevious.add(overlay.put(key, state));
        journalPositions.add(pos.immutable());
    }

    /**
     * @return A point to see changes since, or go back to
     */
    public int mark() {
        return journalKeys.size();
    }

    /**
     * @return The cells set since {@code mark}
     */
    public List<BlockPos> changedSince(int mark) {
        return new ArrayList<>(journalPositions.subList(mark, journalPositions.size()));
    }

    /**
     * Undoes every change since {@code mark}
     */
    public void revert(int mark) {
        for (int i = journalKeys.size() - 1; i >= mark; i--) {
            long key = journalKeys.getLong(i);
            BlockState previous = journalPrevious.remove(i);
            if (previous == null) {
                overlay.remove(key);
            } else {
                overlay.put(key, previous);
            }
            journalKeys.removeLong(i);
            journalPositions.remove(i);
        }
    }

    /**
     * This world as a {@link Level}, for the game's placement logic
     */
    public Level level() {
        if (level == null) {
            level = PlanLevel.of(this, real);
        }
        return level;
    }
}
