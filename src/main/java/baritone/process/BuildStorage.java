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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * The building materials the builder takes from Tom's Simple Storage ({@link WirelessTerminal}), and puts back: what the
 * build still needs and the inventory lacks is taken out, the items of the lowest layers first, as much as fits; what
 * was taken out and isn't needed any more goes back. Only what was taken out: the player's own items stay.
 */
final class BuildStorage {

    /**
     * Inventory slots left free when taking things out, for what's picked up while building
     */
    private static final int KEEP_FREE = 2;
    /**
     * Something storage didn't have, or a terminal that didn't open, isn't tried again for this long
     */
    private static final int RETRY_TICKS = 20 * 60;
    /**
     * Ticks waited for the terminal's item list (an empty storage sends none)
     */
    private static final int LIST_TICKS = 40;
    /**
     * Once the inventory has stopped changing for this long, what was asked for has come
     */
    private static final int SETTLE_TICKS = 10;
    private static final int SETTLE_MAX_TICKS = 100;

    private final IPlayerContext ctx;
    private final WirelessTerminal terminal;
    /**
     * How many of each item were taken out and not put back
     */
    private final Map<Item, Integer> borrowed = new HashMap<>();
    private final Map<Item, Long> notStoredUntil = new HashMap<>();
    private long terminalFailedUntil;
    private String result;
    private Transfer transfer;

    BuildStorage(IPlayerContext ctx) {
        this.ctx = ctx;
        this.terminal = new WirelessTerminal(ctx);
    }

    boolean busy() {
        return transfer != null;
    }

    boolean borrowedAny() {
        return !borrowed.isEmpty();
    }

    /**
     * What was taken out and isn't held any more (placed, or deposited with a full inventory) isn't to be put back: what
     * comes into the inventory later is the player's own.
     */
    void forgetGone() {
        borrowed.replaceAll((item, count) -> Math.min(count, WirelessTerminal.count(ctx.player().getInventory(), item)));
        borrowed.values().removeIf(count -> count <= 0);
    }

    /**
     * Starts taking out what the build still needs ({@code needed}, the lowest layers' first) and the inventory lacks.
     *
     * @return Whether there's anything to ask storage for
     */
    boolean startFetch(Map<Item, Integer> needed) {
        long now = ctx.world().getGameTime();
        if (now < terminalFailedUntil || !WirelessTerminal.carried(ctx)) {
            return false;
        }
        Map<Item, Integer> wanted = new LinkedHashMap<>();
        needed.forEach((item, count) -> {
            int lacking = count - WirelessTerminal.count(ctx.player().getInventory(), item);
            if (lacking > 0 && notStoredUntil.getOrDefault(item, Long.MIN_VALUE) <= now) {
                wanted.put(item, lacking);
            }
        });
        if (wanted.isEmpty() || freeSlots() <= KEEP_FREE) {
            return false;
        }
        transfer = new Fetch(wanted);
        return true;
    }

    /**
     * Starts putting back what was taken out and isn't needed any more: beyond what the rest of the build ({@code
     * needed}) takes.
     *
     * @return Whether there's anything to put back
     */
    boolean startReturn(Map<Item, Integer> needed) {
        if (ctx.world().getGameTime() < terminalFailedUntil || !WirelessTerminal.carried(ctx)) {
            return false;
        }
        Map<Item, Integer> surplus = new HashMap<>();
        borrowed.forEach((item, count) -> {
            int spare = Math.min(count, WirelessTerminal.count(ctx.player().getInventory(), item) - needed.getOrDefault(item, 0));
            if (spare > 0) {
                surplus.put(item, spare);
            }
        });
        if (surplus.isEmpty()) {
            return false;
        }
        transfer = new Return(surplus);
        return true;
    }

    /**
     * Advances the transfer in progress by one tick.
     *
     * @return Whether it's over ({@link #result} says what came of it)
     */
    boolean tick() {
        switch (terminal.tick(transfer)) {
            case RUNNING:
                return false;
            case FAILED:
                terminalFailedUntil = ctx.world().getGameTime() + RETRY_TICKS;
                result = "Couldn't use Tom's Simple Storage: " + terminal.failure();
                break;
            default:
                result = transfer.result();
                break;
        }
        transfer = null;
        return true;
    }

    /**
     * @return What came of the last transfer, to tell the player, or null if nothing did
     */
    String result() {
        return result;
    }

    /**
     * Abandons a transfer in progress, closing the terminal.
     */
    void reset() {
        terminal.reset();
        transfer = null;
    }

    private int freeSlots() {
        return (int) ctx.player().getInventory().items.stream().filter(ItemStack::isEmpty).count();
    }

    private abstract class Transfer implements WirelessTerminal.Job {

        final Map<Item, Integer> before = new HashMap<>();
        int sentAt = -1;
        int lastChange;
        int lastTotal;

        /**
         * @return Whether the requests have gone out (else asked again next tick)
         */
        abstract boolean send(IPlayerContext ctx, AbstractContainerMenu menu, int ticks);

        abstract String result();

        @Override
        public boolean work(IPlayerContext ctx, AbstractContainerMenu menu, int ticks) {
            Inventory inv = ctx.player().getInventory();
            if (sentAt < 0) {
                if (!send(ctx, menu, ticks)) {
                    return false;
                }
                sentAt = ticks;
                lastChange = ticks;
                lastTotal = total(inv);
                return false;
            }
            int total = total(inv);
            if (total != lastTotal) {
                lastTotal = total;
                lastChange = ticks;
            }
            return ticks - lastChange >= SETTLE_TICKS || ticks - sentAt >= SETTLE_MAX_TICKS;
        }

        int change(Item item) {
            return WirelessTerminal.count(ctx.player().getInventory(), item) - before.getOrDefault(item, 0);
        }

        private int total(Inventory inv) {
            return inv.items.stream().mapToInt(ItemStack::getCount).sum();
        }
    }

    private final class Fetch extends Transfer {

        private final Map<Item, Integer> wanted;
        private final List<Item> notStored = new ArrayList<>();

        Fetch(Map<Item, Integer> wanted) {
            this.wanted = wanted;
        }

        @Override
        boolean send(IPlayerContext ctx, AbstractContainerMenu menu, int ticks) {
            Map<Object, Long> stored = WirelessTerminal.stored(menu);
            if (stored.isEmpty() && ticks < LIST_TICKS) {
                return false; // the list hasn't come yet
            }
            Inventory inv = ctx.player().getInventory();
            int free = freeSlots() - KEEP_FREE;
            for (Map.Entry<Item, Integer> want : wanted.entrySet()) {
                if (free <= 0) {
                    break;
                }
                Item item = want.getKey();
                before.put(item, WirelessTerminal.count(inv, item));
                Object match = null;
                for (Map.Entry<Object, Long> entry : stored.entrySet()) {
                    ItemStack stack = WirelessTerminal.stackOf(entry.getKey());
                    // the plain item rather than one with data on it (a named one, a filled shulker box)
                    if (stack.is(item) && entry.getValue() > 0 && (match == null || !stack.hasTag())) {
                        match = entry.getKey();
                    }
                }
                if (match == null) {
                    notStored.add(item);
                    continue;
                }
                int max = Math.max(1, WirelessTerminal.stackOf(match).getMaxStackSize());
                long take = Math.min(want.getValue(), stored.get(match));
                int pulls = (int) Math.min((take + max - 1) / max, free);
                for (int i = 0; i < pulls; i++) {
                    WirelessTerminal.pullStack(menu, match); // a stack each, what doesn't fit stays stored
                }
                free -= pulls;
            }
            return true;
        }

        @Override
        String result() {
            long until = ctx.world().getGameTime() + RETRY_TICKS;
            notStored.forEach(item -> notStoredUntil.put(item, until));
            StringBuilder got = new StringBuilder();
            before.keySet().forEach(item -> {
                int taken = change(item);
                if (taken > 0) {
                    borrowed.merge(item, taken, Integer::sum);
                    got.append(got.length() == 0 ? "" : ", ").append(taken).append(' ').append(item);
                }
            });
            return got.length() == 0 ? (notStored.isEmpty() ? null : "Tom's Simple Storage has none of " + notStored + " (" + WirelessTerminal.relayDiagnosis() + ")")
                    : "Took from Tom's Simple Storage: " + got + (notStored.isEmpty() ? "" : " (it has none of " + notStored + ")");
        }
    }

    private final class Return extends Transfer {

        private final Map<Item, Integer> amounts;

        Return(Map<Item, Integer> amounts) {
            this.amounts = amounts;
        }

        @Override
        boolean send(IPlayerContext ctx, AbstractContainerMenu menu, int ticks) {
            Inventory inv = ctx.player().getInventory();
            amounts.keySet().forEach(item -> before.put(item, WirelessTerminal.count(inv, item)));
            // whole stacks, the biggest first, as many as were taken out
            Set<Integer> slots = new HashSet<>();
            amounts.forEach((item, amount) -> {
                List<Integer> holding = new ArrayList<>();
                for (int slot = 0; slot < inv.items.size(); slot++) {
                    if (inv.items.get(slot).is(item)) {
                        holding.add(slot);
                    }
                }
                holding.sort(Comparator.comparingInt((Integer slot) -> -inv.items.get(slot).getCount()));
                int left = amount;
                for (int slot : holding) {
                    int count = inv.items.get(slot).getCount();
                    if (count <= left) {
                        slots.add(slot);
                        left -= count;
                    }
                }
            });
            WirelessTerminal.shiftClick(ctx, menu, (stack, inventorySlot) -> slots.contains(inventorySlot));
            return true;
        }

        @Override
        String result() {
            StringBuilder put = new StringBuilder();
            amounts.keySet().forEach(item -> {
                int returned = -change(item);
                if (returned > 0) {
                    borrowed.computeIfPresent(item, (k, count) -> count - returned > 0 ? count - returned : null);
                    put.append(put.length() == 0 ? "" : ", ").append(returned).append(' ').append(item);
                }
            });
            return put.length() == 0 ? null : "Put back into Tom's Simple Storage: " + put;
        }
    }
}
