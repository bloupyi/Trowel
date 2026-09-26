package com.stackmc.trowel.ui;

import com.stackmc.trowel.Session;
import com.stackmc.trowel.Trowel;
import com.stackmc.trowel.TrowelItems;
import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.brush.BrushSettings;
import com.stackmc.trowel.brush.BrushType;
import com.stackmc.trowel.engine.BlockView;
import com.stackmc.trowel.engine.Clipboard;
import com.stackmc.trowel.engine.Terrain;
import com.stackmc.trowel.engine.Views;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the builder sees of their tools, as particles sent to them alone.
 *
 * <ul>
 *   <li>the selection, its two corners in color, while they hold a tool;</li>
 *   <li>the exact shape of the brush where they aim, hugging the relief for terrain
 *       brushes, like goPaint;</li>
 *   <li>the outline of what an operation just changed, for a few seconds.</li>
 * </ul>
 */
public final class Hud {

    private static final int PERIOD = 3;
    private static final int FLASH_TICKS = 50;
    private static final int SELECTION_TICKS = 80;
    private static final int MAX_EDGE_POINTS = 420;

    private static final Particle.DustOptions SELECTION = dust(80, 220, 255, 0.8f);
    private static final Particle.DustOptions FIRST = dust(90, 255, 120, 1.3f);
    private static final Particle.DustOptions SECOND = dust(90, 140, 255, 1.3f);
    private static final Particle.DustOptions FLASH = dust(120, 255, 150, 0.9f);
    private static final Particle.DustOptions TARGET = dust(255, 255, 255, 0.6f);
    private static final Particle.DustOptions LEVEL = dust(255, 220, 90, 0.7f);
    private static final Particle.DustOptions POINT = dust(255, 190, 40, 1.2f);
    private static final Particle.DustOptions CURVE = dust(255, 210, 120, 0.6f);
    private static final Particle.DustOptions FRAME = dust(120, 255, 230, 0.8f);
    private static final Particle.DustOptions LOFT = dust(120, 255, 230, 0.5f);
    private static final int LOFT_SAMPLES = 48;
    private static final int LOFT_RAILS = 8;

    private final Trowel trowel;
    private final Map<UUID, Flash> flashes = new HashMap<>();
    private BukkitTask task;
    private int ticks;

    private record Flash(UUID world, Box box, int until) {
    }

    public Hud(Trowel trowel) {
        this.trowel = trowel;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(trowel.plugin(), this::tick, PERIOD, PERIOD);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        flashes.clear();
    }

    public void flash(Player player, World world, Box box) {
        flashes.put(player.getUniqueId(), new Flash(world.getUID(), box, Bukkit.getCurrentTick() + FLASH_TICKS));
    }

    public void showSelection(Player player) {
        trowel.session(player).setShowSelectionUntil(Bukkit.getCurrentTick() + SELECTION_TICKS);
    }

    public void forget(Player player) {
        flashes.remove(player.getUniqueId());
    }

    /**
     * Co-editing: everyone sees, in the other's color, the selection and aimed block of the
     * other builders in the same world. Each chooses to see and to show (G menu, Settings).
     */
    private void drawOthers() {
        // Every other run: the task runs every PERIOD ticks from a start tick that is not always a multiple of it.
        if (ticks++ % 2 != 0) {
            return;
        }
        Map<java.util.UUID, List<Player>> byWorld = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (trowel.host().denyEdit(player) == null) {
                byWorld.computeIfAbsent(player.getWorld().getUID(), id -> new ArrayList<>()).add(player);
            }
        }
        for (List<Player> builders : byWorld.values()) {
            if (builders.size() < 2) {
                continue;
            }
            for (Player shown : builders) {
                Session theirs = trowel.existingSession(shown);
                if (theirs == null || !theirs.isCoeditShare()) {
                    continue;
                }
                Particle.DustOptions color = colorFor(shown);
                Box selection = theirs.selection(shown.getWorld());
                Block aimed = trowel.items().kind(shown.getInventory().getItemInMainHand()) == null ? null
                        : shown.getTargetBlockExact(trowel.settings().reach());
                for (Player viewer : builders) {
                    if (viewer.equals(shown)) {
                        continue;
                    }
                    Session mine = trowel.existingSession(viewer);
                    if (mine == null || !mine.isCoeditShow()) {
                        continue;
                    }
                    if (selection != null && selection.volume() <= 2_000_000L) {
                        box(viewer, selection, color);
                    }
                    if (aimed != null) {
                        box(viewer, new Box(aimed.getX(), aimed.getY(), aimed.getZ(), aimed.getX(), aimed.getY(),
                                aimed.getZ()), color);
                    }
                }
            }
        }
    }

    /** One color per player, always the same. */
    private static Particle.DustOptions colorFor(Player player) {
        float hue = (player.getUniqueId().hashCode() & 0xffff) / 65535f;
        java.awt.Color rgb = java.awt.Color.getHSBColor(hue, 0.75f, 1f);
        return new Particle.DustOptions(Color.fromRGB(rgb.getRed(), rgb.getGreen(), rgb.getBlue()), 1.1f);
    }

    private void tick() {
        int now = Bukkit.getCurrentTick();
        drawOthers();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Flash flash = flashes.get(player.getUniqueId());
            if (flash != null && flash.until() < now) {
                flashes.remove(player.getUniqueId());
                flash = null;
            }
            ItemStack hand = player.getInventory().getItemInMainHand();
            TrowelItems.Kind kind = trowel.items().kind(hand);
            Session session = trowel.existingSession(player);
            boolean recentSelection = session != null && session.getShowSelectionUntil() > now;
            if (kind == null && flash == null && !recentSelection) {
                continue;
            }
            if (trowel.host().denyEdit(player) != null) {
                continue;
            }
            World world = player.getWorld();
            if (session != null && (kind != null || recentSelection)) {
                drawSelection(player, session, world);
            }
            if (kind == TrowelItems.Kind.BRUSH) {
                BrushSettings settings = trowel.items().brushOf(hand);
                if (settings != null) {
                    preview(player, settings, session);
                }
            }
            if (flash != null && flash.world().equals(world.getUID())) {
                box(player, flash.box(), FLASH);
            }
        }
    }

    // ---------------------------------------------------------------- selection

    private void drawSelection(Player player, Session session, World world) {
        Box box = session.selection(world);
        if (box != null) {
            box(player, box, SELECTION);
        }
        if (session.getPos1() != null && world.equals(session.getPos1().getWorld())) {
            cube(player, session.getPos1(), FIRST);
        }
        if (session.getPos2() != null && world.equals(session.getPos2().getWorld())) {
            cube(player, session.getPos2(), SECOND);
        }
        drawPoints(player, session, world);
        for (java.util.List<Location> frame : session.getFrames()) {
            Location previous = null;
            for (Location point : frame) {
                if (!world.equals(point.getWorld())) {
                    continue;
                }
                cube(player, point, FRAME);
                if (previous != null) {
                    line(player, previous.getBlockX() + 0.5, previous.getBlockY() + 0.5, previous.getBlockZ() + 0.5,
                            point.getBlockX() + 0.5, point.getBlockY() + 0.5, point.getBlockZ() + 0.5, FRAME);
                }
                previous = point;
            }
            if (previous != null && frame.size() > 2 && world.equals(frame.get(0).getWorld())) {
                Location first = frame.get(0);
                line(player, previous.getBlockX() + 0.5, previous.getBlockY() + 0.5, previous.getBlockZ() + 0.5,
                        first.getBlockX() + 0.5, first.getBlockY() + 0.5, first.getBlockZ() + 0.5, FRAME);
            }
        }
        drawLoft(player, session, world);
    }

    /** The loft as //loft set will stretch it: each frame's closed curve, and rails from frame to frame. */
    private void drawLoft(Player player, Session session, World world) {
        java.util.List<double[][]> rings = new java.util.ArrayList<>();
        for (java.util.List<Location> frame : session.getFrames()) {
            if (frame.size() < 2 || !world.equals(frame.get(0).getWorld())) {
                continue;
            }
            java.util.List<double[]> points = new java.util.ArrayList<>();
            for (Location l : frame) {
                points.add(new double[]{l.getBlockX() + 0.5, l.getBlockY() + 0.5, l.getBlockZ() + 0.5});
            }
            try {
                rings.add(ToolCommands.ring(points, LOFT_SAMPLES, false));
            } catch (IllegalArgumentException e) {
                return;
            }
        }
        for (double[][] ring : rings) {
            for (double[] p : ring) {
                player.spawnParticle(Particle.DUST, p[0], p[1], p[2], 1, 0, 0, 0, 0, FRAME);
            }
        }
        if (rings.size() < 2) {
            return;
        }
        for (int m = 0; m < LOFT_SAMPLES; m += LOFT_SAMPLES / LOFT_RAILS) {
            java.util.List<double[]> rail = new java.util.ArrayList<>();
            for (double[][] ring : rings) {
                rail.add(ring[m]);
            }
            java.util.List<double[]> dense = com.stackmc.trowel.geom.Shapes.spline(rail);
            int step = Math.max(1, dense.size() / 60);
            for (int i = 0; i < dense.size(); i += step) {
                double[] p = dense.get(i);
                player.spawnParticle(Particle.DUST, p[0], p[1], p[2], 1, 0, 0, 0, 0, LOFT);
            }
        }
    }

    /** Spline points, and the curve that will join them. */
    private void drawPoints(Player player, Session session, World world) {
        java.util.List<double[]> points = new java.util.ArrayList<>();
        for (Location point : session.getPoints()) {
            if (world.equals(point.getWorld())) {
                cube(player, point, POINT);
                points.add(new double[]{point.getBlockX() + 0.5, point.getBlockY() + 0.5, point.getBlockZ() + 0.5});
            }
        }
        if (points.size() < 2) {
            return;
        }
        com.stackmc.trowel.spline.Path curve;
        try {
            curve = com.stackmc.trowel.spline.Path.build(points, 0, 0, 0, false,
                    com.stackmc.trowel.spline.Path.Normal.CONSISTENT, 0.5);
        } catch (IllegalArgumentException e) {
            return;
        }
        int step = Math.max(1, curve.size() / 200);
        for (int i = 0; i < curve.size(); i += step) {
            double[] p = curve.point(i);
            player.spawnParticle(Particle.DUST, p[0], p[1], p[2], 1, 0, 0, 0, 0, CURVE);
        }
    }

    private void cube(Player player, Location corner, Particle.DustOptions color) {
        box(player, new Box(corner.getBlockX(), corner.getBlockY(), corner.getBlockZ(),
                corner.getBlockX(), corner.getBlockY(), corner.getBlockZ()), color);
    }

    // -------------------------------------------------------------------- brush

    private void preview(Player player, BrushSettings s, Session session) {
        if (s.type() == BrushType.LOFT) {
            return;
        }
        RayTraceResult hit = player.rayTraceBlocks(trowel.settings().reach(), FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null) {
            return;
        }
        BlockFace face = hit.getHitBlockFace() == null ? BlockFace.UP : hit.getHitBlockFace();
        Block target = player.isSneaking() ? hit.getHitBlock().getRelative(face) : hit.getHitBlock();
        double cx = target.getX() + 0.5;
        double cy = target.getY() + 0.5;
        double cz = target.getZ() + 0.5;
        double r = s.size() + 0.5;
        Particle.DustOptions color = colorOf(s);

        box(player, new Box(target.getX(), target.getY(), target.getZ(), target.getX(), target.getY(), target.getZ()),
                TARGET);
        switch (s.type()) {
            case SPHERE, PAINT, SPLATTER, ERODE, BLEND, BLOB, BUCKET, FRACTURE, DRAIN, SHELL, BOULDER, IVY, STALACTITE,
                 CRACKS -> {
                circle(player, cx, cy, cz, r, 'y', color);
                circle(player, cx, cy, cz, r, 'x', color);
                circle(player, cx, cy, cz, r, 'z', color);
            }
            case CYLINDER -> {
                double bottom = target.getY() - (s.height() - 1) / 2;
                circle(player, cx, bottom, cz, r, 'y', color);
                circle(player, cx, bottom + s.height(), cz, r, 'y', color);
                for (int i = 0; i < 4; i++) {
                    double a = i * Math.PI / 2;
                    line(player, cx + Math.cos(a) * r, bottom, cz + Math.sin(a) * r,
                            cx + Math.cos(a) * r, bottom + s.height(), cz + Math.sin(a) * r, color);
                }
            }
            case CUBE -> box(player, Box.around(target.getX(), target.getY(), target.getZ(), s.size()), color);
            case DISK, RING, EXTRUDE -> {
                char axis = face.getModX() != 0 ? 'x' : face.getModZ() != 0 ? 'z' : 'y';
                circle(player, cx, cy, cz, r, axis, color);
                if (s.type() == com.stackmc.trowel.brush.BrushType.RING) {
                    circle(player, cx, cy, cz, Math.max(0.5, r - s.height()), axis, color);
                }
                if (s.type() == com.stackmc.trowel.brush.BrushType.EXTRUDE) {
                    line(player, cx, cy, cz, cx + face.getModX() * s.height(), cy + face.getModY() * s.height(),
                            cz + face.getModZ() * s.height(), color);
                }
            }
            case OVERLAY, SCATTER, SMOOTH, RAISE, LOWER, FLATTEN, UNDERLAY, FILLDOWN, SNOWCONE, SCREE -> {
                terrainRing(player, target, s, color);
                if (s.type() == com.stackmc.trowel.brush.BrushType.FLATTEN) {
                    circle(player, cx, target.getY() + 1.02, cz, r, 'y', LEVEL);
                }
            }
            case STAMP -> {
                Clipboard clipboard = session == null ? null : session.getClipboard();
                if (clipboard != null) {
                    box(player, clipboard.boundsAt(target.getX() + face.getModX(), target.getY() + face.getModY(),
                            target.getZ() + face.getModZ()), color);
                }
            }
            case SPIKE -> {
                double bx = cx + face.getModX();
                double by = cy + face.getModY();
                double bz = cz + face.getModZ();
                char axis = face.getModX() != 0 ? 'x' : face.getModZ() != 0 ? 'z' : 'y';
                circle(player, bx, by, bz, r, axis, color);
                line(player, bx, by, bz, bx + face.getModX() * s.height(), by + face.getModY() * s.height(),
                        bz + face.getModZ() * s.height(), color);
            }
        }
    }

    /** A ring laid on the relief, to see where a terrain brush will bite. */
    private void terrainRing(Player player, Block target, BrushSettings s, Particle.DustOptions color) {
        int reach = s.size() + 5;
        Box around = new Box(target.getX() - reach, target.getY() - reach, target.getZ() - reach,
                target.getX() + reach, target.getY() + reach, target.getZ() + reach);
        BlockView view = Views.hideMarkers(Views.live(target.getWorld(),
                trowel.host().markers().within(target.getWorld(), around).keySet()), Material.AIR.createBlockData());
        double r = s.size() + 0.5;
        int points = Math.max(16, Math.min(96, (int) (r * 8)));
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            double px = target.getX() + 0.5 + Math.cos(a) * r;
            double pz = target.getZ() + 0.5 + Math.sin(a) * r;
            int top = Terrain.surface(view, (int) Math.floor(px), (int) Math.floor(pz),
                    target.getY() + s.size() + 4, target.getY() - s.size() - 4);
            double py = top == Terrain.NONE ? target.getY() + 1.05 : top + 1.05;
            player.spawnParticle(Particle.DUST, px, py, pz, 1, 0, 0, 0, 0, color);
        }
    }

    private static Particle.DustOptions colorOf(BrushSettings s) {
        return switch (s.type()) {
            case SPHERE, CUBE, CYLINDER -> dust(90, 220, 255, 0.8f);
            case PAINT, SPLATTER -> dust(255, 150, 60, 0.8f);
            case OVERLAY, SCATTER -> dust(140, 230, 90, 0.8f);
            case SMOOTH, BLEND -> dust(200, 160, 255, 0.8f);
            case RAISE -> dust(120, 255, 170, 0.8f);
            case LOWER, ERODE -> dust(255, 110, 110, 0.8f);
            case FLATTEN -> dust(255, 220, 90, 0.8f);
            case STAMP, LOFT -> dust(255, 255, 255, 0.8f);
            case SPIKE -> dust(190, 120, 255, 0.8f);
            case BLOB, BOULDER -> dust(170, 140, 110, 0.8f);
            case BUCKET -> dust(90, 200, 255, 0.8f);
            case DISK, RING -> dust(110, 200, 255, 0.8f);
            case UNDERLAY, FILLDOWN -> dust(160, 120, 80, 0.8f);
            case FRACTURE, SHELL -> dust(230, 230, 150, 0.8f);
            case DRAIN -> dust(60, 120, 255, 0.8f);
            case SNOWCONE -> dust(240, 250, 255, 0.9f);
            case EXTRUDE -> dust(255, 170, 60, 0.8f);
            case IVY -> dust(70, 180, 60, 0.8f);
            case STALACTITE -> dust(190, 150, 120, 0.8f);
            case CRACKS -> dust(120, 120, 120, 0.8f);
            case SCREE -> dust(150, 140, 130, 0.8f);
        };
    }

    // ------------------------------------------------------------------ drawing

    /** The twelve edges of a box, blocks included. */
    private void box(Player player, Box box, Particle.DustOptions color) {
        double x1 = box.minX();
        double y1 = box.minY();
        double z1 = box.minZ();
        double x2 = box.maxX() + 1;
        double y2 = box.maxY() + 1;
        double z2 = box.maxZ() + 1;
        double perimeter = 4 * ((x2 - x1) + (y2 - y1) + (z2 - z1));
        double spacing = Math.max(0.5, perimeter / MAX_EDGE_POINTS);
        edge(player, x1, y1, z1, x2, y1, z1, spacing, color);
        edge(player, x1, y1, z2, x2, y1, z2, spacing, color);
        edge(player, x1, y2, z1, x2, y2, z1, spacing, color);
        edge(player, x1, y2, z2, x2, y2, z2, spacing, color);
        edge(player, x1, y1, z1, x1, y1, z2, spacing, color);
        edge(player, x2, y1, z1, x2, y1, z2, spacing, color);
        edge(player, x1, y2, z1, x1, y2, z2, spacing, color);
        edge(player, x2, y2, z1, x2, y2, z2, spacing, color);
        edge(player, x1, y1, z1, x1, y2, z1, spacing, color);
        edge(player, x2, y1, z1, x2, y2, z1, spacing, color);
        edge(player, x1, y1, z2, x1, y2, z2, spacing, color);
        edge(player, x2, y1, z2, x2, y2, z2, spacing, color);
    }

    private void line(Player player, double x1, double y1, double z1, double x2, double y2, double z2,
                      Particle.DustOptions color) {
        edge(player, x1, y1, z1, x2, y2, z2, 0.4, color);
    }

    private void edge(Player player, double x1, double y1, double z1, double x2, double y2, double z2,
                      double spacing, Particle.DustOptions color) {
        double length = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        int steps = (int) Math.max(1, Math.min(160, length / spacing));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            player.spawnParticle(Particle.DUST, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t,
                    1, 0, 0, 0, 0, color);
        }
    }

    /** A circle in the plane perpendicular to the given axis. */
    private void circle(Player player, double cx, double cy, double cz, double r, char axis,
                        Particle.DustOptions color) {
        int points = Math.max(16, Math.min(90, (int) (r * 7)));
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            double u = Math.cos(a) * r;
            double v = Math.sin(a) * r;
            double x = cx;
            double y = cy;
            double z = cz;
            switch (axis) {
                case 'x' -> {
                    y += u;
                    z += v;
                }
                case 'z' -> {
                    x += u;
                    y += v;
                }
                default -> {
                    x += u;
                    z += v;
                }
            }
            player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, color);
        }
    }

    private static Particle.DustOptions dust(int r, int g, int b, float size) {
        return new Particle.DustOptions(Color.fromRGB(r, g, b), size);
    }
}
