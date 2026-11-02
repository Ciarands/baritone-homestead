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

import baritone.api.utils.IPlayerContext;
import baritone.utils.BackgroundMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Works a Tom's Simple Storage network through an Advanced Wireless Terminal: the terminal's bound storage terminal is
 * opened remotely, and its menu used to store stacks from the inventory (shift-clicking an inventory slot) or take them
 * out (the request Tom's screen sends when an item in it is shift-clicked).
 * <p>
 * Opening sends the same request as Tom's "Open Terminal" key, so the server finds the terminal wherever it is
 * carried: hands, inventory or a trinket slot. The menu is used in the background ({@link BackgroundMenus}): no screen
 * opens, so it works unfocused, paused, or with another screen showing, and the player sees nothing. Tom's Simple
 * Storage is an optional dependency, reached by reflection.
 * <p>
 * Driven one game tick at a time, while the caller keeps pathing paused.
 */
final class WirelessTerminal {

    private static final ResourceLocation TERMINAL = new ResourceLocation("toms_storage", "ts.adv_wireless_terminal");
    private static final String TOMS_STORAGE = "toms_storage";
    /**
     * The server opens the terminal within a tick or two. A request can get lost (opened while the server was busy with
     * another of our containers, a lag spike), so it's sent again after this long, a few times
     */
    private static final int OPEN_RETRY_TICKS = 40;
    private static final int OPEN_ATTEMPTS = 3;
    /**
     * After shift-clicking, the server's inventory updates are waited for until a slot is free, at most this long
     */
    private static final int SYNC_MAX_TICKS = 100;
    private static final int SYNC_MIN_TICKS = 2;

    enum Status {
        RUNNING,
        DONE,
        FAILED
    }

    /**
     * What's done with the open terminal, a tick at a time
     */
    interface Job {
        /**
         * @param ticks Ticks since the terminal opened, from 1
         * @return Whether it's done (the terminal is then closed)
         */
        boolean work(IPlayerContext ctx, AbstractContainerMenu menu, int ticks);
    }

    private enum State {
        IDLE,
        OPENING,
        WORKING
    }

    private final IPlayerContext ctx;
    private State state = State.IDLE;
    private int ticks;
    private int attempts;
    private String failure;
    private static Method openTerminal; // NetworkHandler.openTerminal(), sends the "open terminal" key's request

    WirelessTerminal(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    boolean inProgress() {
        return state != State.IDLE;
    }

    String failure() {
        return failure;
    }

    /**
     * Advances storing the inventory by one tick: everything in the main inventory, and from the hotbar only what
     * {@code isTarget} accepts; never tools, weapons, armor, food or the terminal itself.
     */
    Status deposit(Predicate<ItemStack> isTarget) {
        return tick((ctx, menu, ticks) -> {
            if (ticks == 1) {
                shiftClick(ctx, menu, (stack, inventorySlot) -> shouldDeposit(stack, inventorySlot, isTarget));
                return false;
            }
            // done once the server has freed a slot, or there's nothing more to wait for
            return ticks > SYNC_MIN_TICKS && ctx.player().getInventory().getFreeSlot() != -1 || ticks >= SYNC_MAX_TICKS;
        });
    }

    /**
     * Advances a job by one tick, opening the terminal first.
     */
    Status tick(Job job) {
        switch (state) {
            case IDLE:
                return start();
            case OPENING:
                return awaitOpen();
            case WORKING:
                AbstractContainerMenu menu = terminalMenu();
                if (menu == null) {
                    finish();
                    return fail("the terminal closed");
                }
                if (job.work(ctx, menu, ++ticks)) {
                    finish();
                    return Status.DONE;
                }
                return Status.RUNNING;
            default:
                throw new IllegalStateException();
        }
    }

    /**
     * Abandons what's in progress, closing the terminal if we opened it.
     */
    void reset() {
        if (state != State.IDLE && ctx.player() != null) {
            finish();
        }
        state = State.IDLE;
    }

    private Status start() {
        if (openTerminal == null) {
            try {
                openTerminal = Class.forName("com.tom.storagemod.network.NetworkHandler").getMethod("openTerminal");
            } catch (ReflectiveOperationException | LinkageError e) {
                return fail("Tom's Simple Storage isn't installed");
            }
        }
        attempts = 0;
        state = State.OPENING;
        return requestOpen();
    }

    /**
     * Sends the request Tom's "Open Terminal" key sends: the server looks for the terminal in hands, inventory and
     * trinket slots, and opens its storage terminal. It's taken in the background, without a screen.
     */
    private Status requestOpen() {
        DataRelay.install();
        BackgroundMenus.expect(TOMS_STORAGE);
        try {
            openTerminal.invoke(null);
        } catch (ReflectiveOperationException e) {
            finish();
            return fail("couldn't ask Tom's Simple Storage to open the terminal: " + e);
        }
        attempts++;
        ticks = 0;
        return Status.RUNNING;
    }

    private Status awaitOpen() {
        if (terminalMenu() == null) {
            if (++ticks < OPEN_RETRY_TICKS) {
                return Status.RUNNING;
            }
            if (attempts < OPEN_ATTEMPTS) {
                return requestOpen();
            }
            finish();
            return fail("the terminal didn't open after " + attempts + " tries (none carried, out of range, or its chunk isn't loaded)");
        }
        state = State.WORKING;
        ticks = 0;
        return Status.RUNNING;
    }

    private static boolean shouldDeposit(ItemStack stack, int inventorySlot, Predicate<ItemStack> isTarget) {
        if (stack.isDamageableItem() || stack.isEdible()) {
            return false; // tools, weapons, armor and food are needed to keep going
        }
        // the hotbar holds what's in use (tools, throwaway blocks for placing), so only targets are taken from it
        return !Inventory.isHotbarSlot(inventorySlot) || isTarget.test(stack);
    }

    interface SlotFilter {
        boolean test(ItemStack stack, int inventorySlot);
    }

    /**
     * Shift-clicks the player's inventory slots (the 36, not armor or offhand) that {@code store} accepts: the
     * terminal stores their stacks. Never the terminal itself.
     */
    static void shiftClick(IPlayerContext ctx, AbstractContainerMenu menu, SlotFilter store) {
        Inventory inv = ctx.player().getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container != inv || slot.getContainerSlot() >= inv.items.size()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && !isTerminal(stack) && store.test(stack, slot.getContainerSlot())) {
                ctx.playerController().windowClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, ctx.player());
            }
        }
    }

    /**
     * @return How many of each item the storage holds, as the open terminal has it (empty until its list arrives).
     * The keys are Tom's stored stacks, to ask for with {@link #pullStack}
     */
    static Map<Object, Long> stored(AbstractContainerMenu menu) {
        Map<Object, Long> result = new LinkedHashMap<>();
        try {
            Object sync = menu.getClass().getField("sync").get(menu);
            for (Object stored : (List<?>) sync.getClass().getMethod("getAsList").invoke(sync)) {
                result.put(stored, (Long) sync.getClass().getMethod("getAmount", stored.getClass()).invoke(sync, stored));
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            return Collections.emptyMap();
        }
        return result;
    }

    /**
     * @return The item stack a stored stack from {@link #stored} stands for
     */
    static ItemStack stackOf(Object stored) {
        try {
            return (ItemStack) stored.getClass().getMethod("getStack").invoke(stored);
        } catch (ReflectiveOperationException e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * Asks for a stack of {@code stored} (one from {@link #stored}) to be moved into the inventory, as shift-clicking
     * it in Tom's screen does: up to a full stack, and what doesn't fit stays in storage
     */
    static void pullStack(AbstractContainerMenu menu, Object stored) {
        try {
            Object sync = menu.getClass().getField("sync").get(menu);
            Class<?> action = Class.forName("com.tom.storagemod.gui.StorageTerminalMenu$SlotAction");
            Object shiftPull = action.getField("SHIFT_PULL").get(null);
            sync.getClass().getMethod("sendInteract", stored.getClass(), action, boolean.class).invoke(sync, stored, shiftPull, false);
        } catch (ReflectiveOperationException e) {
            // (Tom's changed: nothing comes of it, which the caller sees in the inventory)
        }
    }

    private void finish() {
        BackgroundMenus.close();
        if (isTerminalMenu(ctx.player().containerMenu)) {
            // opened with a screen after all (another mod handling the packet first): close that one normally
            ctx.player().closeContainer();
        }
        state = State.IDLE;
    }

    private Status fail(String reason) {
        failure = reason;
        state = State.IDLE;
        return Status.FAILED;
    }

    /**
     * @return The open storage terminal menu: normally the one taken in the background, else one opened with a screen
     */
    private AbstractContainerMenu terminalMenu() {
        AbstractContainerMenu background = BackgroundMenus.menu();
        if (background != null) {
            return background;
        }
        AbstractContainerMenu open = ctx.player().containerMenu;
        return isTerminalMenu(open) ? open : null;
    }

    private boolean isTerminalMenu(AbstractContainerMenu menu) {
        return menu != ctx.player().inventoryMenu && menu.getClass().getName().startsWith("com.tom.storagemod.");
    }

    static boolean isTerminal(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(TERMINAL);
    }

    /**
     * @return Whether an Advanced Wireless Terminal is carried where Tom's looks for it when opening one (hands,
     * inventory, a trinket slot): without, there's no use trying
     */
    static boolean carried(IPlayerContext ctx) {
        try {
            Predicate<ItemStack> isTerminal = WirelessTerminal::isTerminal;
            return Class.forName("com.tom.storagemod.util.PlayerInvUtil")
                    .getMethod("findItem", net.minecraft.world.entity.player.Player.class, Predicate.class, Object.class, Function.class)
                    .invoke(null, ctx.player(), isTerminal, null, Function.identity()) != null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false; // Tom's Simple Storage isn't installed
        }
    }

    static String relayDiagnosis() {
        return DataRelay.diagnosis();
    }

    /**
     * @return How many of {@code item} the main inventory (the 36 slots) holds
     */
    static int count(Inventory inventory, Item item) {
        int count = 0;
        for (ItemStack stack : inventory.items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Tom's client hands the storage terminal's item list (and its updates) only to an open terminal screen. Opened in
     * the background there's none, so without this the menu never learns what's in storage, and nothing can be asked
     * for. Its channel is taken over on the current connection by a handler that does what Tom's does for a screen, and
     * otherwise hands the data to the terminal menu open in the background.
     */
    private static final class DataRelay {

        private static ClientPacketListener installedOn;
        private static String state = "not tried";
        private static int received;
        private static int delivered;

        /**
         * @return How handing Tom's data to the background menu is going, for when nothing arrives
         */
        static String diagnosis() {
            return "relay " + state + ", " + received + " data packets, " + delivered + " handed to the terminal";
        }

        static void install() {
            Minecraft mc = Minecraft.getInstance();
            ClientPacketListener connection = mc.getConnection();
            if (connection == null || connection == installedOn) {
                return;
            }
            try {
                Class<?> networking = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
                Class<?> handler = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayChannelHandler");
                ResourceLocation channel = (ResourceLocation) Class.forName("com.tom.storagemod.network.NetworkHandler").getField("DATA_S2C").get(null);
                Class<?> receiver = Class.forName("com.tom.storagemod.util.IDataReceiver");
                Method receive = receiver.getMethod("receive", CompoundTag.class);
                Object relay = Proxy.newProxyInstance(WirelessTerminal.class.getClassLoader(), new Class<?>[]{handler}, (proxy, method, args) -> {
                    if (!method.getName().equals("receive") || args == null || args.length != 4) {
                        switch (method.getName()) {
                            case "equals":
                                return proxy == args[0];
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "toString":
                                return "Baritone's relay for Tom's terminal data";
                            default:
                                return null;
                        }
                    }
                    received++;
                    CompoundTag tag = ((FriendlyByteBuf) args[2]).readAnySizeNbt();
                    ((Minecraft) args[0]).execute(() -> deliver((Minecraft) args[0], tag, receiver, receive));
                    return null;
                });
                networking.getMethod("unregisterReceiver", ResourceLocation.class).invoke(null, channel);
                networking.getMethod("registerReceiver", ResourceLocation.class, handler).invoke(null, channel, relay);
                installedOn = connection;
                state = "installed";
            } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                installedOn = connection; // (not Fabric, or Tom's changed: tried once per connection)
                state = "not installed: " + e;
            }
        }

        private static void deliver(Minecraft mc, CompoundTag tag, Class<?> receiver, Method receive) {
            try {
                if (receiver.isInstance(mc.screen)) {
                    receive.invoke(mc.screen, tag); // as Tom's does
                    return;
                }
                AbstractContainerMenu menu = BackgroundMenus.menu();
                if (menu != null && menu.getClass().getName().startsWith("com.tom.storagemod.")) {
                    menu.getClass().getMethod("receiveClientNBTPacket", CompoundTag.class).invoke(menu, tag);
                    delivered++;
                } else {
                    state = "data with nowhere to go (menu " + (menu == null ? "none" : menu.getClass().getSimpleName()) + ")";
                }
            } catch (ReflectiveOperationException e) {
                state = "couldn't hand over the data: " + e;
            }
        }
    }
}
