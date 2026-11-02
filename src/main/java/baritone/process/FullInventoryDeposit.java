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

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * For processes that pick up what they break or harvest (farming, clearing, building): once no inventory slot is free,
 * the main inventory goes into Tom's Simple Storage through the wireless terminal (depositToTomsSimpleStorageWirelessTerminal),
 * but for what the process still uses. Pathing waits meanwhile.
 */
final class FullInventoryDeposit {

    /**
     * After a deposit that failed or freed nothing, the next try waits this long
     */
    private static final int RETRY_TICKS = 20 * 60;

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private final WirelessTerminal terminal;
    private WirelessTerminal.Job job;
    private long retryAt;
    private boolean toldStillFull;

    FullInventoryDeposit(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.terminal = new WirelessTerminal(ctx);
    }

    /**
     * @param kind What a stack counts as, for {@code keep}
     * @param keep How many of each kind stay in the inventory, worked out when it's full
     * @param log  Where to tell the player
     * @return The command while depositing, else null
     */
    PathingCommand tick(boolean isSafeToCancel, Function<ItemStack, Object> kind, Supplier<Map<Object, Integer>> keep, Consumer<String> log) {
        if (job == null) {
            if (!Baritone.settings().depositToTomsSimpleStorageWirelessTerminal.value || ctx.player().getInventory().getFreeSlot() != -1
                    || ctx.world().getGameTime() < retryAt || !WirelessTerminal.carried(ctx)) {
                return null;
            }
            if (!(isSafeToCancel && ctx.player().onGround())) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE); // finish the current movement first
            }
            job = WirelessTerminal.depositKeeping(kind, keep.get());
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        switch (terminal.tick(job)) {
            case RUNNING:
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            case FAILED:
                log.accept("Inventory full, couldn't deposit into the wireless terminal: " + terminal.failure());
                retryAt = ctx.world().getGameTime() + RETRY_TICKS;
                break;
            default:
                long free = ctx.player().getInventory().items.stream().filter(ItemStack::isEmpty).count();
                if (free > 0) {
                    log.accept("Deposited into the wireless terminal, " + free + " slots free now");
                    toldStillFull = false;
                } else {
                    if (!toldStillFull) {
                        log.accept("Inventory still full after depositing into the wireless terminal: the rest is in use (or storage is full)");
                        toldStillFull = true;
                    }
                    retryAt = ctx.world().getGameTime() + RETRY_TICKS;
                }
                break;
        }
        job = null;
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /**
     * Abandons a deposit in progress, closing the terminal.
     */
    void reset() {
        terminal.reset();
        job = null;
        retryAt = 0;
        toldStillFull = false;
    }
}
