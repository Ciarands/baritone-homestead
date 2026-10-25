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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Empties the inventory into a Tom's Simple Storage network through an Advanced Wireless Terminal: using the item opens
 * its bound storage terminal remotely, and shift-clicking an inventory slot in that screen stores the stack.
 * <p>
 * Driven one game tick at a time by {@link #tick}, while the caller keeps pathing paused.
 */
final class WirelessTerminalDeposit {

    private static final ResourceLocation TERMINAL = new ResourceLocation("toms_storage", "ts.adv_wireless_terminal");
    /**
     * The server opens the terminal within a tick or two. Out of range or with the terminal's chunk unloaded it
     * never does (it only sends a chat message), so give up after this long.
     */
    private static final int OPEN_TIMEOUT_TICKS = 40;
    /**
     * Ticks to wait after shift-clicking before closing, so the server's inventory updates have arrived and the caller
     * sees the freed slots.
     */
    private static final int SYNC_TICKS = 20;

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
    private InteractionHand hand;
    private int previousSelected;
    private int swappedFrom; // main inventory slot the terminal was swapped out of into the hotbar, or -1
    private String failure;

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
                if (++ticks < SYNC_TICKS) {
                    return Status.RUNNING;
                }
                finish();
                return Status.DONE;
            default:
                throw new IllegalStateException();
        }
    }

    /**
     * Abandons a deposit in progress: closes the terminal if we opened it and puts the hotbar back.
     */
    void reset() {
        if (state != State.IDLE && ctx.player() != null) {
            finish();
        }
        state = State.IDLE;
    }

    private Status start() {
        Inventory inv = ctx.player().getInventory();
        previousSelected = inv.selected;
        swappedFrom = -1;
        if (isTerminal(ctx.player().getOffhandItem())) {
            hand = InteractionHand.OFF_HAND;
        } else {
            int slot = -1;
            for (int i = 0; i < inv.items.size(); i++) {
                if (isTerminal(inv.items.get(i))) {
                    slot = i;
                    break;
                }
            }
            if (slot == -1) {
                return fail("no Advanced Wireless Terminal in the inventory");
            }
            hand = InteractionHand.MAIN_HAND;
            if (Inventory.isHotbarSlot(slot)) {
                inv.selected = slot;
            } else {
                // inventory slot ids in the player's own menu: main inventory 9-35 matches, hotbar 0-8 is at 36-44
                ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, slot, inv.selected, ClickType.SWAP, ctx.player());
                swappedFrom = slot;
            }
        }
        state = State.OPENING;
        ticks = 0;
        return Status.RUNNING;
    }

    private Status awaitOpen(Predicate<ItemStack> isTarget) {
        if (ticks++ == 0) {
            // useItem sends the selected hotbar slot first if it changed
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand);
            return Status.RUNNING;
        }
        if (!terminalOpen()) {
            if (ticks > OPEN_TIMEOUT_TICKS) {
                finish();
                return fail("the terminal didn't open (out of range, or its chunk isn't loaded)");
            }
            return Status.RUNNING;
        }
        AbstractContainerMenu menu = ctx.player().containerMenu;
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
        if (terminalOpen()) {
            ctx.player().closeContainer();
        }
        if (swappedFrom != -1) {
            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, swappedFrom, previousSelected, ClickType.SWAP, ctx.player());
        }
        ctx.player().getInventory().selected = previousSelected;
        state = State.IDLE;
    }

    private Status fail(String reason) {
        failure = reason;
        state = State.IDLE;
        return Status.FAILED;
    }

    private boolean terminalOpen() {
        AbstractContainerMenu menu = ctx.player().containerMenu;
        return menu != ctx.player().inventoryMenu && menu.getClass().getName().startsWith("com.tom.storagemod.");
    }

    private static boolean isTerminal(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(TERMINAL);
    }
}
