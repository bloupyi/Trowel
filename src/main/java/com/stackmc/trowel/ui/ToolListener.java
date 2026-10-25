package com.stackmc.trowel.ui;

import com.stackmc.trowel.Chat;
import com.stackmc.trowel.Session;
import com.stackmc.trowel.Trowel;
import com.stackmc.trowel.TrowelItems;
import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.engine.ChangeSet;
import com.stackmc.trowel.engine.Engine;
import com.stackmc.trowel.brush.BrushSettings;
import com.stackmc.trowel.brush.BrushType;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        } else if (kind == TrowelItems.Kind.POLYGON) {
            polygon(player, event);
        } else if (kind == TrowelItems.Kind.DEBUG) {
            debug(player, event);
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
            session.getPolygonPoints().clear();
            session.setPos1(block.getLocation());
            describe(player, session, "First corner: ", block);
        } else {
            session.getPolygonPoints().clear();
            session.setPos2(block.getLocation());
            describe(player, session, "Second corner: ", block);
        }
        trowel.hud().showSelection(player);
    }

    private void polygon(Player player, PlayerInteractEvent event) {
        Session session = trowel.session(player);
        int now = Bukkit.getCurrentTick();
        if (Session.recent(session.getLastWandTick(), now, DEBOUNCE_TICKS)) {
            return;
        }
        session.setLastWandTick(now);

        boolean left = event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_AIR;
        if (left && player.isSneaking()) {
            trowel.commands().execute(player, "poly clear");
        } else if (left) {
            trowel.commands().execute(player, "poly undo");
        } else {
            Block block = event.getClickedBlock();
            if (block == null) {
                RayTraceResult hit = player.rayTraceBlocks(trowel.settings().reach(), FluidCollisionMode.NEVER);
                block = hit == null ? null : hit.getHitBlock();
            }
            if (block == null) {
                Chat.bar(player, "No block in reach.", NamedTextColor.RED);
                return;
            }
            trowel.commands().execute(player, "poly add " + block.getX() + " " + block.getY() + " " + block.getZ());
        }
    }

    private void describe(Player player, Session session, String label, Block block) {
        Box box = session.selection(player.getWorld());
        String where = block.getX() + " " + block.getY() + " " + block.getZ();
        Chat.info(player, label, box == null ? where : where + "  (" + box.size() + ", " + box.volume() + " blocks)");
    }

    // ------------------------------------------------------------- debug stick

    /** Each block type's properties and their values, in the vanilla order. */
    private static final Map<Material, Map<String, List<String>>> PROPERTIES = new HashMap<>();

    /**
     * The vanilla debug stick needs op and creative: Trowel does it itself, inside the buildable
     * area and through the history. Left click picks the property, right click changes its value,
     * sneaking goes backwards.
     */
    private void debug(Player player, PlayerInteractEvent event) {
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
        Material type = block.getType();
        Map<String, List<String>> properties = PROPERTIES.computeIfAbsent(type, material -> properties(
                material.asBlockType().createBlockDataStates().stream().map(data -> data.getAsString(false)).toList()));
        if (properties.isEmpty()) {
            Chat.bar(player, type.getKey().getKey() + " has no properties", NamedTextColor.RED);
            return;
        }

        Map<String, String> values = values(block.getBlockData().getAsString(false));
        List<String> names = new ArrayList<>(properties.keySet());
        String selected = session.getDebugProperties().get(type);
        if (!names.contains(selected)) {
            selected = names.getFirst();
        }
        boolean left = event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_AIR;
        if (left) {
            String next = cycle(names, selected, player.isSneaking());
            session.getDebugProperties().put(type, next);
            Chat.bar(player, "Selected \"" + next + "\" (" + values.get(next) + ")", NamedTextColor.AQUA);
            return;
        }

        String property = selected;
        String value = cycle(properties.get(property), values.get(property), player.isSneaking());
        values.put(property, value);
        BlockData changed = type.createBlockData(state(values));
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        trowel.engine().submit(player, Engine.Job.of("Debug stick", block.getWorld(), new Box(x, y, z, x, y, z),
                        context -> {
                            ChangeSet changes = context.changes();
                            changes.set(x, y, z, changed);
                            return changes;
                        })
                .stroke("debug:" + x + ":" + y + ":" + z)
                .then(count -> Chat.bar(player, count == 0 ? "Not changed: outside the buildable area or protected."
                        : "\"" + property + "\" to " + value, count == 0 ? NamedTextColor.RED : NamedTextColor.AQUA)));
    }

    /** Properties and their values in the order the states list them: {@code minecraft:x[a=1,b=2]}. */
    static Map<String, List<String>> properties(Collection<String> states) {
        Map<String, Set<String>> seen = new LinkedHashMap<>();
        for (String state : states) {
            values(state).forEach((name, value) -> seen.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(value));
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        seen.forEach((name, all) -> out.put(name, List.copyOf(all)));
        return out;
    }

    static Map<String, String> values(String state) {
        Map<String, String> out = new LinkedHashMap<>();
        int open = state.indexOf('[');
        if (open < 0) {
            return out;
        }
        for (String pair : state.substring(open + 1, state.length() - 1).split(",")) {
            int equals = pair.indexOf('=');
            out.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        return out;
    }

    static String state(Map<String, String> values) {
        List<String> pairs = new ArrayList<>();
        values.forEach((name, value) -> pairs.add(name + "=" + value));
        return "[" + String.join(",", pairs) + "]";
    }

    static String cycle(List<String> options, String current, boolean backwards) {
        return options.get(Math.floorMod(options.indexOf(current) + (backwards ? -1 : 1), options.size()));
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
