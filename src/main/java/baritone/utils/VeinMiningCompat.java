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

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.util.*;

/**
 * The Vein Mining mod breaks every connected block of the same kind together with the one mined, server side, as long
 * as its activation condition holds. Baritone vets only the block it mines, so the rest of the vein can let liquid in
 * or pull the floor away. This computes the vein the mod would break, using the mod's own rules (reached by
 * reflection; the mod is an optional dependency).
 */
public final class VeinMiningCompat {

    private static final ResourceLocation ENCHANTMENT = new ResourceLocation("veinmining", "vein_mining");
    /**
     * Vein Mining's client sends whether it's active every 5 ticks; after sneaking this long it has been told it isn't
     */
    public static final int SUPPRESS_TICKS = 8;

    private static boolean initialized;
    private static Object server; // VeinMiningConfig.SERVER
    private static Object client; // VeinMiningConfig.CLIENT
    private static Method isValidTarget; // BlockProcessor.isValidTarget(BlockState, Block)

    private VeinMiningCompat() {}

    /**
     * @param tool   The tool that will break {@code origin}
     * @param origin The block about to be broken
     * @param get    Block state lookup
     * @return The other blocks Vein Mining would break along with {@code origin} if we don't sneak; empty if it isn't
     * installed or wouldn't activate
     */
    public static Set<BlockPos> vein(ItemStack tool, BlockPos origin, java.util.function.Function<BlockPos, BlockState> get) {
        if (!init()) {
            return Collections.emptySet();
        }
        try {
            int maxBase = (Integer) value(server, "maxBlocksBase");
            Enchantment enchantment = BuiltInRegistries.ENCHANTMENT.get(ENCHANTMENT);
            int level = enchantment == null ? 0 : EnchantmentHelper.getItemEnchantmentLevel(enchantment, tool);
            // same choice as the mod's client: with no base allowance the enchantment is required
            if (maxBase == 0 && level == 0) {
                return Collections.emptySet();
            }
            String activation = ((Enum<?>) value(client, maxBase == 0 ? "activationState" : "activationStateWithoutEnchantment")).name();
            if (!activation.equals("STANDING")) {
                return Collections.emptySet(); // only active while crouching or holding its key, which Baritone doesn't
            }
            int max = maxBase + (Integer) value(server, "maxBlocksPerLevel") * level;
            boolean diagonal = (Boolean) value(server, "diagonalMining");
            Block originBlock = get.apply(origin).getBlock();
            if (max <= 0 || !(Boolean) isValidTarget.invoke(null, get.apply(origin), originBlock)) {
                return Collections.emptySet();
            }
            Set<BlockPos> vein = new LinkedHashSet<>();
            Set<BlockPos> seen = new HashSet<>();
            seen.add(origin);
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(origin);
            while (!queue.isEmpty() && vein.size() < max) {
                BlockPos pos = queue.poll();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if ((dx == 0 && dy == 0 && dz == 0) || (!diagonal && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1)) {
                                continue;
                            }
                            BlockPos next = pos.offset(dx, dy, dz);
                            if (seen.add(next) && (Boolean) isValidTarget.invoke(null, get.apply(next), originBlock)) {
                                vein.add(next);
                                queue.add(next);
                                if (vein.size() >= max) {
                                    return vein;
                                }
                            }
                        }
                    }
                }
            }
            return vein;
        } catch (ReflectiveOperationException | ClassCastException e) {
            return Collections.emptySet();
        }
    }

    private static boolean init() {
        if (!initialized) {
            initialized = true;
            try {
                Class<?> config = Class.forName("com.illusivesoulworks.veinmining.common.config.VeinMiningConfig");
                server = config.getField("SERVER").get(null);
                client = config.getField("CLIENT").get(null);
                isValidTarget = Class.forName("com.illusivesoulworks.veinmining.common.veinmining.logic.BlockProcessor")
                        .getMethod("isValidTarget", BlockState.class, Block.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                server = null;
            }
        }
        return server != null;
    }

    /**
     * @return The current value of a config entry (a SpectreConfigSpec value, read through its get())
     */
    private static Object value(Object config, String name) throws ReflectiveOperationException {
        Object entry = config.getClass().getField(name).get(config);
        return entry.getClass().getMethod("get").invoke(entry);
    }
}
