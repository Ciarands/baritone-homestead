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

package baritone.utils.pathing;

import baritone.Baritone;
import baritone.api.cache.IWorldData;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockUtils;
import baritone.api.utils.IPlayerContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.level.block.Blocks;

public class Avoidance {

    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final double coefficient;
    private final int radius;
    private final int radiusSq;

    public Avoidance(BlockPos center, double coefficient, int radius) {
        this(center.getX(), center.getY(), center.getZ(), coefficient, radius);
    }

    public Avoidance(int centerX, int centerY, int centerZ, double coefficient, int radius) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.coefficient = coefficient;
        this.radius = radius;
        this.radiusSq = radius * radius;
    }

    public double coefficient(int x, int y, int z) {
        int xDiff = x - centerX;
        int yDiff = y - centerY;
        int zDiff = z - centerZ;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff <= radiusSq ? coefficient : 1.0D;
    }

    /**
     * A sculk sensor relays vibrations within 8 blocks of it to shriekers within 8 blocks of it, and enough shrieks
     * summon a Warden: anything done within this distance of a shrieker can set it off
     */
    public static final int SCULK_SHRIEKER_RADIUS = 16;
    private static final double SCULK_SHRIEKER_COEFFICIENT = 10;
    /**
     * Only shriekers this close to us matter for a path, and many zones are expensive to build
     */
    private static final int SCULK_SHRIEKER_SEARCH_DISTANCE = 128;
    private static final int SCULK_SHRIEKER_MAX = 32;

    public static List<Avoidance> create(IPlayerContext ctx) {
        List<Avoidance> res = new ArrayList<>();
        if (Baritone.settings().avoidSculkShriekers.value) {
            // not tied to the avoidance setting: a Warden is lethal
            sculkShriekers(ctx.worldData(), ctx.playerFeet()).forEach(shrieker -> res.add(new Avoidance(shrieker, SCULK_SHRIEKER_COEFFICIENT, SCULK_SHRIEKER_RADIUS)));
        }
        if (!Baritone.settings().avoidance.value) {
            return res;
        }
        double mobSpawnerCoeff = Baritone.settings().mobSpawnerAvoidanceCoefficient.value;
        double mobCoeff = Baritone.settings().mobAvoidanceCoefficient.value;
        if (mobSpawnerCoeff != 1.0D) {
            // the cache names blocks by their id, which for spawners has been "spawner" since 1.13, not "mob_spawner"
            ctx.worldData().getCachedWorld().getLocationsOf(BlockUtils.blockToString(Blocks.SPAWNER), 1, ctx.playerFeet().x, ctx.playerFeet().z, 2)
                    .forEach(mobspawner -> res.add(new Avoidance(mobspawner, mobSpawnerCoeff, Baritone.settings().mobSpawnerAvoidanceRadius.value)));
        }
        if (mobCoeff != 1.0D) {
            ctx.entitiesStream()
                    // only what attacks: passive and ambient mobs (bats, squid, animals, villagers) are no threat, and
                    // in caves they're everywhere, which made every route look expensive
                    .filter(entity -> entity instanceof Enemy)
                    .filter(entity -> (!(entity instanceof Spider)) || ctx.player().getLightLevelDependentMagicValue() < 0.5)
                    .filter(entity -> !(entity instanceof ZombifiedPiglin) || ((ZombifiedPiglin) entity).getLastHurtByMob() != null)
                    .filter(entity -> !(entity instanceof EnderMan) || ((EnderMan) entity).isCreepy())
                    .forEach(entity -> res.add(new Avoidance(entity.blockPosition(), mobCoeff, Baritone.settings().mobAvoidanceRadius.value)));
        }
        return res;
    }

    /**
     * @return The cached sculk shriekers nearest {@code near}, at most {@link #SCULK_SHRIEKER_MAX} within
     * {@link #SCULK_SHRIEKER_SEARCH_DISTANCE} blocks
     */
    public static List<BlockPos> sculkShriekers(IWorldData worldData, BlockPos near) {
        if (worldData == null) {
            return Collections.emptyList();
        }
        int maxDistSq = SCULK_SHRIEKER_SEARCH_DISTANCE * SCULK_SHRIEKER_SEARCH_DISTANCE;
        return worldData.getCachedWorld().getLocationsOf(BlockUtils.blockToString(Blocks.SCULK_SHRIEKER), Integer.MAX_VALUE, near.getX(), near.getZ(), 1)
                .stream()
                .filter(pos -> pos.distSqr(near) <= maxDistSq)
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(near)))
                .limit(SCULK_SHRIEKER_MAX)
                .collect(Collectors.toList());
    }

    /**
     * Applies this avoidance to every position within its radius. Where avoidances overlap, the strongest one counts
     * rather than all of them multiplied: a group of mobs isn't exponentially worse than one, and compounding (1.5^n)
     * made areas with a few mobs so expensive that path searches timed out without reaching any goal.
     */
    public void applySpherical(Long2DoubleOpenHashMap map) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z <= radius * radius) {
                        long hash = BetterBlockPos.longHash(centerX + x, centerY + y, centerZ + z);
                        double existing = map.get(hash);
                        if (Math.abs(Math.log(coefficient)) > Math.abs(Math.log(existing))) {
                            map.put(hash, coefficient);
                        }
                    }
                }
            }
        }
    }
}
