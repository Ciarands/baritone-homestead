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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.function.Predicate;

/**
 * Empties the inventory into a Tom's Simple Storage network through an Advanced Wireless Terminal: the terminal's bound
 * storage terminal is opened remotely, and shift-clicking an inventory slot in its menu stores the stack.
 * <p>
 * Opening sends the same request as Tom's "Open Terminal" key, so the server finds the terminal wherever it is
 * carried: hands, inventory or a trinket slot. The menu is used in the background ({@link BackgroundMenus}): no screen
 * opens, so it works unfocused, paused, or with another screen showing, and the player sees nothing. Tom's Simple
 * Storage is an optional dependency, reached by reflection.
 * <p>
 * Driven one game tick at a time by {@link #tick}, while the caller keeps pathing paused.
 */
final class WirelessTerminalDeposit {

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

    private enum State {
        IDLE,
        OPENING,
        DEPOSITED
    }

    private final IPlayerContext ctx;
    private State state = State.IDLE;
    private int ticks;
    private int attempts;
    private String failure;
    private static Method openTerminal; // NetworkHandler.openTerminal(), sends the "open terminal" key's request

    WirelessTerminalDeposit(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    boolean inProgress() {
        return state != State.IDLE;
    }

    String failure() {
        return failure;
    }

    /**
     * Advances the deposit by one tick.
     *
     * @param isTarget The mining targets: these are deposited from the hotbar too, everything else only from the
     *                 main inventory
     */
    Status tick(Predicate<ItemStack> isTarget) {
        switch (state) {
            case IDLE:
                return start();
            case OPENING:
                return awaitOpen(isTarget);
            case DEPOSITED:
                ticks++;
                // done once the server has freed a slot, or there's nothing more to wait for
                boolean freed = ticks >= SYNC_MIN_TICKS && ctx.player().getInventory().getFreeSlot() != -1;
                if (!freed && ticks < SYNC_MAX_TICKS && terminalMenu() != null) {
                    return Status.RUNNING;
                }
                finish();
                return Status.DONE;
            default:
                throw new IllegalStateException();
        }
    }

    /**
     * Abandons a deposit in progress, closing the terminal if we opened it.
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

    private Status awaitOpen(Predicate<ItemStack> isTarget) {
        AbstractContainerMenu menu = terminalMenu();
        if (menu == null) {
            if (++ticks < OPEN_RETRY_TICKS) {
                return Status.RUNNING;
            }
            if (attempts < OPEN_ATTEMPTS) {
                return requestOpen();
            }
            finish();
            return fail("the terminal didn't open after " + attempts + " tries (none carried, out of range, or its chunk isn't loaded)");
        }
        Inventory inv = ctx.player().getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container != inv || slot.getContainerSlot() >= inv.items.size()) {
                continue; // not one of our 36 inventory slots (armor and offhand stay)
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && shouldDeposit(stack, slot.getContainerSlot(), isTarget)) {
                ctx.playerController().windowClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, ctx.player());
            }
        }
        state = State.DEPOSITED;
        ticks = 0;
        return Status.RUNNING;
    }

    private static boolean shouldDeposit(ItemStack stack, int inventorySlot, Predicate<ItemStack> isTarget) {
        if (isTerminal(stack) || stack.isDamageableItem() || stack.isEdible()) {
            return false; // tools, weapons, armor and food are needed to keep going
        }
        // the hotbar holds what's in use (tools, throwaway blocks for placing), so only targets are taken from it
        return !Inventory.isHotbarSlot(inventorySlot) || isTarget.test(stack);
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

    private static boolean isTerminal(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(TERMINAL);
    }
}
