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

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

/**
 * Sophisticated Backpacks' tool swapper upgrade: when a block is hit with a tool in hand, the server swaps it for the
 * backpack's fastest tool for that block, if it has a faster one. With one in a carried backpack, the tool swapping is
 * left to it: a tool is held when breaking (the best of the hotbar; it swaps nothing else), and its tools count towards
 * how fast blocks break. An optional dependency, reached by reflection.
 * <p>
 * The backpack's upgrades and contents are kept on the server; the client asks for them, as the backpack's tooltip
 * does, and reads them from its copy of the backpack storage.
 */
public final class BackpackToolSwapper {

    /**
     * How long what's carried is trusted
     */
    private static final int REFRESH_TICKS = 20;
    /**
     * How often the server is asked again for a backpack's contents (upgrades get added, toggled, tools taken out)
     */
    private static final int REQUEST_TICKS = 200;
    private static final Set<String> TOOL_SWAPPERS = Set.of("sophisticatedbackpacks:tool_swapper_upgrade", "sophisticatedbackpacks:advanced_tool_swapper_upgrade");

    private static boolean missing;
    private static Object inventoryProvider;
    private static Method runOnBackpacks;
    private static Class<?> slotConsumer;
    private static Method storage;
    private static Method contentsOf;
    private static Constructor<?> requestContents;
    private static Method sendToServer;

    private static final Map<UUID, Long> requestedAt = new HashMap<>();
    /**
     * When each backpack's contents were first asked for: not there yet, what it has is unknown for this long
     */
    private static final Map<UUID, Long> firstAsked = new HashMap<>();
    private static final int ANSWER_TICKS = 100;
    private static volatile boolean pending;
    private static long checkedAt = Long.MIN_VALUE / 2;
    private static volatile boolean active;
    /**
     * Whether a sword in hand gets swapped too (the swapper's "any" mode, not "only tools")
     */
    private static volatile boolean swords;
    private static volatile List<ItemStack> tools = Collections.emptyList();

    private BackpackToolSwapper() {}

    /**
     * @return Whether a carried backpack's tool swapper swaps the tool in hand for its best when a block is hit
     */
    public static boolean active(LocalPlayer player) {
        refresh(player);
        return active;
    }

    /**
     * @return The tools in the backpacks with an active tool swapper
     */
    public static List<ItemStack> tools(LocalPlayer player) {
        refresh(player);
        return tools;
    }

    /**
     * @return Whether a carried backpack's contents haven't come from the server yet (just after joining): whether it
     * has a tool swapper, and what tools, isn't known
     */
    public static boolean pending(LocalPlayer player) {
        refresh(player);
        return pending;
    }

    /**
     * @return Whether the tool swapper swaps this when it's in hand (it leaves anything that isn't a tool alone, and
     * swords in its "only tools" mode)
     */
    public static boolean isTool(ItemStack stack) {
        return stack.getItem() instanceof DiggerItem || stack.getItem() instanceof ShearsItem || (swords && stack.getItem() instanceof SwordItem);
    }

    /**
     * @return What's in hand once {@code held} hits {@code state}: the swapper puts in the backpack tool that's best at
     * the block (right for it and fastest, where right is the game's "correct tool for drops": never shears on
     * leaves, a hoe is) in place of a tool in hand that's slower at it or not right for it. {@code held} itself if it
     * stays
     */
    public static ItemStack swappedFor(LocalPlayer player, ItemStack held, BlockState state) {
        if (!active(player) || !isTool(held)) {
            return held;
        }
        ItemStack in = held;
        float best = goodAt(held, state) ? held.getDestroySpeed(state) : 0;
        for (ItemStack tool : tools) {
            if (goodAt(tool, state) && tool.getDestroySpeed(state) > best) {
                best = tool.getDestroySpeed(state);
                in = tool;
            }
        }
        return in;
    }

    /**
     * The swapper's own test of a tool for a block
     */
    private static boolean goodAt(ItemStack stack, BlockState state) {
        return stack.isCorrectToolForDrops(state) && stack.getDestroySpeed(state) > 1.5F;
    }

    /**
     * @return Whether a carried backpack's tool swapper has a tool of its own for {@code state} and gets it in hand: one
     * in the hotbar it keeps there (swapped in already), or one in the backpack it swaps in for something in the hotbar
     * it swaps (a tool, or a sword in its "any" mode)
     */
    public static boolean handles(LocalPlayer player, BlockState state) {
        if (!active(player)) {
            return false;
        }
        boolean swappable = false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isTool(stack)) {
                if (goodAt(stack, state)) {
                    return true;
                }
                swappable = true;
            }
        }
        return swappable && tools.stream().anyMatch(tool -> goodAt(tool, state));
    }

    private static boolean load() {
        if (missing) {
            return false;
        }
        if (runOnBackpacks != null) {
            return true;
        }
        try {
            Class<?> provider = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider");
            inventoryProvider = provider.getMethod("get").invoke(null);
            slotConsumer = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider$BackpackInventorySlotConsumer");
            Class<?> backpackStorage = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage");
            storage = backpackStorage.getMethod("get");
            contentsOf = backpackStorage.getMethod("getOrCreateBackpackContents", UUID.class);
            requestContents = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.network.RequestBackpackInventoryContentsMessage").getConstructor(UUID.class);
            sendToServer = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.network.SBPPacketHandler").getMethod("sendToServer", Object.class);
            runOnBackpacks = provider.getMethod("runOnBackpacks", Player.class, slotConsumer);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            missing = true; // not installed
            return false;
        }
    }

    private static void refresh(LocalPlayer player) {
        // (on the client thread only: elsewhere, e.g. path calculation, what was found last stands)
        long now = player.level().getGameTime();
        if ((now - checkedAt < REFRESH_TICKS && now >= checkedAt) || !Minecraft.getInstance().isSameThread()) {
            return;
        }
        checkedAt = now;
        if (!load()) {
            active = false;
            pending = false;
            return;
        }
        boolean unknown = false;
        boolean found = false;
        boolean anySwapsSwords = false;
        List<ItemStack> backpackTools = new ArrayList<>();
        try {
            Object contentsStore = storage.invoke(null);
            for (ItemStack backpack : carried(player)) {
                CompoundTag tag = backpack.getTag();
                if (tag == null || !tag.hasUUID("contentsUuid")) {
                    continue; // never opened: empty, no upgrades
                }
                UUID id = tag.getUUID("contentsUuid");
                CompoundTag contents = (CompoundTag) contentsOf.invoke(contentsStore, id);
                firstAsked.putIfAbsent(id, now);
                if (!contents.contains("inventory") && !contents.contains("upgradeInventory") && now - firstAsked.get(id) < ANSWER_TICKS) {
                    unknown = true;
                }
                if (now - requestedAt.getOrDefault(id, Long.MIN_VALUE / 2) >= REQUEST_TICKS || now < requestedAt.get(id)) {
                    requestedAt.put(id, now);
                    sendToServer.invoke(null, requestContents.newInstance(id));
                }
                int mode = swapMode(contents.getCompound("upgradeInventory"));
                if (mode != NO_SWAP) {
                    found = true;
                    anySwapsSwords |= mode == ANY;
                    for (Tag item : contents.getCompound("inventory").getList("Items", Tag.TAG_COMPOUND)) {
                        ItemStack stack = ItemStack.of((CompoundTag) item);
                        if (isTool(stack)) {
                            backpackTools.add(stack);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            found = false;
        }
        active = found;
        swords = anySwapsSwords;
        tools = backpackTools;
        pending = unknown;
    }

    private static final int NO_SWAP = 0;
    private static final int ONLY_TOOLS = 1;
    private static final int ANY = 2;

    /**
     * @return How the tool swappers among these upgrades swap, the most they do: not at all, only tools, or any
     * (swords too)
     */
    private static int swapMode(CompoundTag upgrades) {
        int mode = NO_SWAP;
        for (Tag entry : upgrades.getList("Items", Tag.TAG_COMPOUND)) {
            CompoundTag upgrade = (CompoundTag) entry;
            if (!TOOL_SWAPPERS.contains(upgrade.getString("id"))) {
                continue;
            }
            CompoundTag settings = upgrade.getCompound("tag");
            if (settings.contains("enabled") && !settings.getBoolean("enabled")) {
                continue;
            }
            String swapMode = settings.getString("toolSwapMode");
            if (!swapMode.equals("noSwap")) {
                mode = Math.max(mode, swapMode.equals("onlyTools") ? ONLY_TOOLS : ANY);
            }
        }
        return mode;
    }

    /**
     * @return The backpacks the player carries: inventory, armor, a trinket slot
     */
    private static List<ItemStack> carried(LocalPlayer player) throws ReflectiveOperationException {
        List<ItemStack> backpacks = new ArrayList<>();
        Object consumer = Proxy.newProxyInstance(BackpackToolSwapper.class.getClassLoader(), new Class<?>[]{slotConsumer}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "accept":
                    backpacks.add((ItemStack) args[0]);
                    return false; // (on to the next)
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        });
        runOnBackpacks.invoke(inventoryProvider, player, consumer);
        return backpacks;
    }
}
