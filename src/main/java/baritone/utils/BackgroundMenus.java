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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * Container menus Baritone uses without any screen: when the server opens a menu we asked for, it's set up as the
 * player's open container (so slot clicks go through as usual) but no screen is created. Whatever screen is showing,
 * if any, stays, nothing needs focus, and the player sees nothing.
 * <p>
 * Called from the client packet listener on the main thread.
 */
public final class BackgroundMenus {

    private static String wantedNamespace; // menu types from this mod are taken in the background, or null
    private static AbstractContainerMenu menu; // the one taken, until closed

    private BackgroundMenus() {}

    /**
     * Take the next menu the server opens from this mod's menu types in the background
     */
    public static void expect(String namespace) {
        wantedNamespace = namespace;
    }

    /**
     * @return The menu taken in the background, if it's still open
     */
    public static AbstractContainerMenu menu() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && menu != null && player.containerMenu == menu ? menu : null;
    }

    /**
     * Close the menu taken in the background, if any, and stop expecting one. The screen showing is left alone.
     */
    public static void close() {
        wantedNamespace = null;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && menu != null && player.containerMenu == menu) {
            player.connection.send(new ServerboundContainerClosePacket(menu.containerId));
            player.containerMenu = player.inventoryMenu;
        }
        menu = null;
    }

    /**
     * @return Whether the menu was taken in the background, so no screen should be opened for it
     */
    public static boolean onOpenScreen(MenuType<?> type, int containerId) {
        LocalPlayer player = Minecraft.getInstance().player;
        ResourceLocation id = BuiltInRegistries.MENU.getKey(type);
        if (wantedNamespace == null || player == null || id == null || !id.getNamespace().equals(wantedNamespace)) {
            return false;
        }
        wantedNamespace = null;
        menu = type.create(containerId, player.getInventory());
        player.containerMenu = menu;
        return true;
    }

    /**
     * @return Whether the server closed the menu taken in the background, which is then handled without touching
     * the screen showing
     */
    public static boolean onCloseContainer(int containerId) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || menu == null || menu.containerId != containerId || player.containerMenu != menu) {
            return false;
        }
        player.containerMenu = player.inventoryMenu;
        menu = null;
        return true;
    }
}
