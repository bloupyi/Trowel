package com.stackmc.trowel;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.engine.Batch;
import com.stackmc.trowel.engine.Clipboard;
import com.stackmc.trowel.geom.Polygon;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.Deque;

/** What Trowel remembers about a player: selection, history, clipboard. */
@Getter
@Setter
public final class Session {

    private boolean busy;
    /** Co-editing: see the selection and cursor of the other builders in the same world (off until asked). */
    private boolean coeditShow;
    /** Co-editing: show ours to the others. */
    private boolean coeditShare = true;
    /** Progress of the running operation, for //cancel; {@code null} when idle. */
    private com.stackmc.trowel.engine.Progress running;
    private final Deque<Batch> undo = new ArrayDeque<>();
    private final Deque<Batch> redo = new ArrayDeque<>();
    private Clipboard clipboard;
    /**
     * //rotate always turns the clipboard as it was before the first rotation, by the total angle:
     * free angles are approximate, and turning an already turned copy would add the errors up.
     */
    private Clipboard unrotated;
    private Clipboard rotated;
    private double rotation;

    private Location pos1;
    private Location pos2;
    /**
     * Points of a polygonal selection, in the order they were placed. The selection box is their
     * bounds; the polygon holds while the box keeps them horizontally (see {@link #polygon}).
     */
    private final java.util.List<Location> polygonPoints = new java.util.ArrayList<>();
    /** Spline waypoints, in the order they were placed. */
    private final java.util.List<Location> points = new java.util.ArrayList<>();
    /** The frames of a loft: each is a loop of points. */
    private final java.util.List<java.util.List<Location>> frames = new java.util.ArrayList<>();
    /** The //text font. */
    private String font = "SansSerif";

    /** Mask applied to every operation, or {@code null}. */
    private String globalMask;
    /** {@code false}: operations never replace a marker. */
    private boolean editMarkers;
    /** Walk and fly through blocks in creative. */
    private boolean noclip;

    private String lastPattern = "stone";
    private String lastMask = "";
    private int lastBrushTick = Integer.MIN_VALUE;
    private int lastWandTick = Integer.MIN_VALUE;
    private int showSelectionUntil = Integer.MIN_VALUE;

    /**
     * {@code true} if {@code last} is less than {@code window} ticks old.
     *
     * <p>As a {@code long}: the first call starts from {@link Integer#MIN_VALUE}, and an
     * {@code int} subtraction would overflow into negatives, making every click look like the
     * previous one just happened.</p>
     */
    public static boolean recent(int last, int now, int window) {
        return (long) now - last < window;
    }

    /** The selection, if both its corners are set in this world. */
    public Box selection(World world) {
        if (pos1 == null || pos2 == null || world == null
                || !world.equals(pos1.getWorld()) || !world.equals(pos2.getWorld())) {
            return null;
        }
        return new Box(pos1.getBlockX(), pos1.getBlockY(), pos1.getBlockZ(),
                pos2.getBlockX(), pos2.getBlockY(), pos2.getBlockZ());
    }

    public void select(World world, Box box) {
        pos1 = new Location(world, box.minX(), box.minY(), box.minZ());
        pos2 = new Location(world, box.maxX(), box.maxY(), box.maxZ());
    }

    public void clearSelection() {
        pos1 = null;
        pos2 = null;
        polygonPoints.clear();
    }

    /**
     * {@code true} if the selection is a polygon: its box has the horizontal bounds of the points.
     * //expand vert keeps the polygon; pushing a side or clicking a corner makes it a box again.
     */
    public boolean polygonal(World world) {
        Box box = selection(world);
        if (box == null || polygonPoints.isEmpty()) {
            return false;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Location point : polygonPoints) {
            if (!world.equals(point.getWorld())) {
                return false;
            }
            minX = Math.min(minX, point.getBlockX());
            maxX = Math.max(maxX, point.getBlockX());
            minZ = Math.min(minZ, point.getBlockZ());
            maxZ = Math.max(maxZ, point.getBlockZ());
        }
        return box.minX() == minX && box.maxX() == maxX && box.minZ() == minZ && box.maxZ() == maxZ;
    }

    /** The polygon of the selection, or {@code null} when it is a plain box. */
    public Polygon polygon(World world) {
        if (!polygonal(world)) {
            return null;
        }
        int[] xs = new int[polygonPoints.size()];
        int[] zs = new int[polygonPoints.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = polygonPoints.get(i).getBlockX();
            zs[i] = polygonPoints.get(i).getBlockZ();
        }
        return new Polygon(xs, zs);
    }

    /** Adds a point to the polygon, starting a new one if the selection was a box. Returns the count. */
    public int addPolygonPoint(Location point) {
        World world = point.getWorld();
        boolean going = polygonal(world);
        if (!going) {
            polygonPoints.clear();
        }
        if (polygonPoints.size() >= Polygon.MAX_POINTS) {
            throw new IllegalArgumentException("A polygon has at most " + Polygon.MAX_POINTS
                    + " points: left click removes the last one.");
        }
        polygonPoints.add(point.toBlockLocation());
        Box added = new Box(point.getBlockX(), point.getBlockY(), point.getBlockZ(),
                point.getBlockX(), point.getBlockY(), point.getBlockZ());
        select(world, going ? selection(world).union(added) : added);
        return polygonPoints.size();
    }

    /** Removes the last point of the polygon, keeping the height of the selection. Returns the count left. */
    public int removePolygonPoint(World world) {
        if (!polygonal(world)) {
            throw new IllegalArgumentException("No polygon selection: //poly gives its wand.");
        }
        Box box = selection(world);
        polygonPoints.removeLast();
        if (polygonPoints.isEmpty()) {
            clearSelection();
            return 0;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Location point : polygonPoints) {
            minX = Math.min(minX, point.getBlockX());
            maxX = Math.max(maxX, point.getBlockX());
            minZ = Math.min(minZ, point.getBlockZ());
            maxZ = Math.max(maxZ, point.getBlockZ());
        }
        select(world, new Box(minX, box.minY(), minZ, maxX, box.maxY(), maxZ));
        return polygonPoints.size();
    }
}
