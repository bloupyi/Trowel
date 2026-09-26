package com.stackmc.trowel.ui;

import com.stackmc.trowel.Chat;
import com.stackmc.trowel.Session;
import com.stackmc.trowel.Trowel;
import com.stackmc.trowel.TrowelItems;
import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.brush.BrushSettings;
import com.stackmc.trowel.brush.BrushType;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;

/**
 * The wand and brushes in hand.
 *
 * <p>Both work from afar: a click in the air aims at the block you look at. A click in the air
 * arrives already cancelled by the server, hence the listener without {@code ignoreCancelled}.</p>
 */
public final class ToolListener implements Listener {

    /** A right click on a block is sometimes followed by a click in the air: only one is kept. */
    private static final int DEBOUNCE_TICKS = 3;

    private final Trowel trowel;

    public ToolListener(Trowel trowel) {
        this.trowel = trowel;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() == Action.PHYSICAL) {
            return;
        }
        TrowelItems.Kind kind = trowel.items().kind(event.getItem());
        if (kind == null) {
            return;
        }
        Player player = event.getPlayer();
        String denied = trowel.host().denyEdit(player);
        if (denied != null) {
            Chat.bar(player, denied, NamedTextColor.RED);
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        if (kind == TrowelItems.Kind.WAND) {
            wand(player, event);
        } else {
            brush(player, event);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        trowel.forget(event.getPlayer());
    }

    // ----------------------------------------------------------------- wand

    private void wand(Player player, PlayerInteractEvent event) {
        Session session = trowel.session(player);
        int now = Bukkit.getCurrentTick();
        if (Session.recent(session.getLastWandTick(), now, DEBOUNCE_TICKS)) {
            return;
        }
        session.setLastWandTick(now);

        Block block = event.getClickedBlock();
        if (block == null) {
            RayTraceResult hit = player.rayTraceBlocks(trowel.settings().reach(), FluidCollisionMode.NEVER);
            block = hit == null ? null : hit.getHitBlock();
        }
        if (block == null) {
            Chat.bar(player, "No block in reach.", NamedTextColor.RED);
            return;
        }

        boolean left = event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_AIR;
        if (left && player.isSneaking()) {
            session.getPoints().removeIf(point -> !player.getWorld().equals(point.getWorld()));
            session.getPoints().add(block.getLocation());
            Chat.info(player, "Spline point " + session.getPoints().size() + ": ",
                    block.getX() + " " + block.getY() + " " + block.getZ());
        } else if (!left && player.isSneaking()) {
            Box current = session.selection(player.getWorld());
            Box point = new Box(block.getX(), block.getY(), block.getZ(), block.getX(), block.getY(), block.getZ());
            Box grown = current == null ? point : current.union(point);
            session.select(player.getWorld(), grown);
            Chat.info(player, "Selection expanded: ", grown.size() + " (" + grown.volume() + " blocks)");
        } else if (left) {
            session.setPos1(block.getLocation());
            describe(player, session, "First corner: ", block);
        } else {
            session.setPos2(block.getLocation());
            describe(player, session, "Second corner: ", block);
        }
        trowel.hud().showSelection(player);
    }

    private void describe(Player player, Session session, String label, Block block) {
        Box box = session.selection(player.getWorld());
        String where = block.getX() + " " + block.getY() + " " + block.getZ();
        Chat.info(player, label, box == null ? where : where + "  (" + box.size() + ", " + box.volume() + " blocks)");
    }

    // ------------------------------------------------------------------ brush

    private void brush(Player player, PlayerInteractEvent event) {
        Session session = trowel.session(player);
        int now = Bukkit.getCurrentTick();
        if (Session.recent(session.getLastBrushTick(), now, DEBOUNCE_TICKS)) {
            return;
        }
        session.setLastBrushTick(now);

        BrushSettings settings = trowel.items().brushOf(event.getItem());
        if (settings == null) {
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            trowel.dialogs().openBrush(player);
            return;
        }
        if (settings.type() == BrushType.LOFT) {
            if (player.isSneaking()) {
                trowel.commands().execute(player, "loft frame");
            }
            trowel.commands().execute(player, "loft point");
            return;
        }

        RayTraceResult hit = player.rayTraceBlocks(trowel.settings().reach(), FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null) {
            Chat.bar(player, "No block in reach.", NamedTextColor.RED);
            return;
        }
        BlockFace face = hit.getHitBlockFace() == null ? BlockFace.UP : hit.getHitBlockFace();
        Block target = player.isSneaking() ? hit.getHitBlock().getRelative(face) : hit.getHitBlock();
        trowel.paint(player, settings, target, face, player.getInventory().getHeldItemSlot());
    }
}
