package com.stackmc.trowel.ui;

import com.stackmc.trowel.Session;
import com.stackmc.trowel.Trowel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * //physics off: blocks placed or broken by hand do not update their neighbours.
 *
 * <p>The updates whose source is such a block are cancelled during the tick it changed, and a
 * gravity block placed that way never starts falling, until it is broken or replaced.</p>
 */
public final class NoPhysics implements Listener {

    private final Trowel trowel;
    /** Changed this tick by a player without physics. */
    private final Set<Location> quiet = new HashSet<>();
    /** Placed by a player without physics: does not fall. */
    private final Set<Location> frozen = new HashSet<>();

    public NoPhysics(Trowel trowel) {
        this.trowel = trowel;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Location at = event.getBlockPlaced().getLocation();
        if (!off(event.getPlayer())) {
            frozen.remove(at);
            return;
        }
        frozen.add(at);
        quiet(at);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Location at = event.getBlock().getLocation();
        frozen.remove(at);
        if (off(event.getPlayer())) {
            quiet(at);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (!quiet.isEmpty() && quiet.contains(event.getSourceBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        // Also fired on landing: only the block starting to fall still has its own type in the world.
        if (event.getEntity() instanceof FallingBlock falling && !frozen.isEmpty()
                && frozen.contains(block.getLocation())
                && block.getType() == falling.getBlockData().getMaterial()) {
            event.setCancelled(true);
        }
    }

    private boolean off(Player player) {
        Session session = trowel.existingSession(player);
        return session != null && session.isNoPhysics();
    }

    private void quiet(Location at) {
        if (quiet.isEmpty()) {
            Bukkit.getScheduler().runTask(trowel.plugin(), quiet::clear);
        }
        quiet.add(at);
    }
}
