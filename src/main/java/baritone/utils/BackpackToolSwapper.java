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
    private static long checkedAt = Long.MIN_VALUE / 2;
    private static volatile boolean active;
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
     * @return Whether the tool swapper swaps this when it's in hand (it leaves anything that isn't a tool alone)
     */
    public static boolean isTool(ItemStack stack) {
        return stack.getItem() instanceof DiggerItem || stack.getItem() instanceof ShearsItem;
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
            return;
        }
        boolean found = false;
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
                if (now - requestedAt.getOrDefault(id, Long.MIN_VALUE / 2) >= REQUEST_TICKS || now < requestedAt.get(id)) {
                    requestedAt.put(id, now);
                    sendToServer.invoke(null, requestContents.newInstance(id));
                }
                if (swapsTools(contents.getCompound("upgradeInventory"))) {
                    found = true;
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
        tools = backpackTools;
    }

    /**
     * @return Whether among these upgrades is a tool swapper that's on and swaps tools
     */
    private static boolean swapsTools(CompoundTag upgrades) {
        for (Tag entry : upgrades.getList("Items", Tag.TAG_COMPOUND)) {
            CompoundTag upgrade = (CompoundTag) entry;
            if (!TOOL_SWAPPERS.contains(upgrade.getString("id"))) {
                continue;
            }
            CompoundTag settings = upgrade.getCompound("tag");
            boolean enabled = !settings.contains("enabled") || settings.getBoolean("enabled");
            if (enabled && !"noSwap".equals(settings.getString("toolSwapMode"))) {
                return true;
            }
        }
        return false;
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
