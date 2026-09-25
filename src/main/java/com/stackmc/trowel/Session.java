package com.stackmc.trowel;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.engine.Batch;
import com.stackmc.trowel.engine.Clipboard;
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
    /** Co-editing: see the selection and cursor of the other builders in the same world. */
    private boolean coeditShow = true;
    /** Co-editing: show ours to the others. */
    private boolean coeditShare = true;
    /** Progress of the running operation, for //cancel; {@code null} when idle. */
    private com.stackmc.trowel.engine.Progress running;
    private final Deque<Batch> undo = new ArrayDeque<>();
    private final Deque<Batch> redo = new ArrayDeque<>();
    private Clipboard clipboard;

    private Location pos1;
    private Location pos2;
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
    }
}
