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

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Chunks claimed by others through Open Parties and Claims (an optional dependency, reached by reflection). The server
 * refuses breaking and placing there, so a path that relies on it never completes, and it's somebody else's build.
 */
public final class ClaimsCompat {

    /**
     * Claims whose owner left long ago: Open Parties and Claims leaves them unprotected
     */
    private static final UUID EXPIRED_CLAIM = new UUID(0, 1);

    private static boolean initialized;
    private static Method getClaimsManager; // OpenPACClientAPI.getClaimsManager()
    private static Method get; // IClientClaimsManagerAPI.get(ResourceLocation, int, int)
    private static Method getPlayerId; // IPlayerChunkClaimAPI.getPlayerId()
    private static Object api; // OpenPACClientAPI.get()

    /**
     * Contexts are created several times a tick; claims change rarely, so a snapshot is reused for a while
     */
    private static final long CACHE_MS = 1000;
    private static String cachedKey;
    private static long cachedAt;
    private static LongSet cached;

    private ClaimsCompat() {}

    /**
     * Must be called on the main thread: the mod's client claim data isn't safe to read elsewhere.
     *
     * @param me     Our player: our own claims are fine
     * @param radius In chunks around the center
     * @return Keys ({@link ChunkPos#asLong}) of the chunks within {@code radius} claimed by anyone but us, server
     * claims included; empty if the mod isn't installed
     */
    public static synchronized LongSet claimedByOthers(ResourceLocation dimension, UUID me, int centerChunkX, int centerChunkZ, int radius) {
        String key = dimension + " " + me + " " + centerChunkX + " " + centerChunkZ + " " + radius;
        long now = System.currentTimeMillis();
        if (key.equals(cachedKey) && now - cachedAt < CACHE_MS) {
            return cached;
        }
        LongSet claimed = new LongOpenHashSet();
        if (!init()) {
            return claimed;
        }
        try {
            Object manager = getClaimsManager.invoke(api);
            for (int x = centerChunkX - radius; x <= centerChunkX + radius; x++) {
                for (int z = centerChunkZ - radius; z <= centerChunkZ + radius; z++) {
                    Object claim = get.invoke(manager, dimension, x, z);
                    if (claim == null) {
                        continue;
                    }
                    UUID owner = (UUID) getPlayerId.invoke(claim);
                    if (!me.equals(owner) && !EXPIRED_CLAIM.equals(owner)) {
                        claimed.add(ChunkPos.asLong(x, z));
                    }
                }
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            claimed.clear();
        }
        cachedKey = key;
        cachedAt = now;
        cached = claimed;
        return claimed;
    }

    private static boolean init() {
        if (!initialized) {
            initialized = true;
            try {
                Class<?> apiClass = Class.forName("xaero.pac.client.api.OpenPACClientAPI");
                api = apiClass.getMethod("get").invoke(null);
                getClaimsManager = apiClass.getMethod("getClaimsManager");
                get = Class.forName("xaero.pac.client.claims.api.IClientClaimsManagerAPI").getMethod("get", ResourceLocation.class, int.class, int.class);
                getPlayerId = Class.forName("xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI").getMethod("getPlayerId");
            } catch (ReflectiveOperationException | LinkageError e) {
                api = null;
            }
        }
        return api != null;
    }
}
