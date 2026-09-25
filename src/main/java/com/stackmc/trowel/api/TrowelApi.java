package com.stackmc.trowel.api;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Trowel as other plugins see it, published in Bukkit's services manager.
 *
 * <pre>{@code
 * TrowelApi trowel = TrowelApi.get();
 * trowel.registerHost(this, new MyHost());
 * }</pre>
 */
public interface TrowelApi {

    /** The running Trowel, or {@code null} if it is not installed or not enabled yet. */
    static TrowelApi get() {
        return Bukkit.getServicesManager().load(TrowelApi.class);
    }

    /**
     * Lets a plugin decide who builds where, in the worlds its host {@linkplain TrowelHost#handles handles}.
     * Hosts are asked in registration order; the first that handles a world wins. A plugin has at
     * most one host: registering again replaces it.
     */
    void registerHost(Plugin owner, TrowelHost host);

    void unregisterHost(Plugin owner);

    /** A fresh selection wand. */
    ItemStack wand();

    /** {@code true} for the wand and every brush. */
    boolean isTool(ItemStack stack);

    /** The player's selection in this world, or {@code null}. */
    Box selection(Player player, World world);

    void clearSelection(Player player);

    /** The Trowel home menu, as on the G key. */
    void openMenu(Player player);

    /** The brush picker. */
    void openBrushes(Player player);

    /** Reads the configuration again. */
    void reload();
}
