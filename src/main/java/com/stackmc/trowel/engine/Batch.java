package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.Keys;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import org.bukkit.block.data.BlockData;

import java.util.Map;
import java.util.UUID;

/**
 * An undoable operation: the state before and after, blocks and markers (a {@code null}
 * setting: no marker at that position).
 *
 * <p>Undo places the state before, redo the state after: the same batch moves from one stack to
 * the other without being computed again. Brush strokes of the same gesture merge into it.</p>
 */
public final class Batch {

    final UUID world;
    final String stroke;
    final String label;
    final long createdAt = System.currentTimeMillis();
    int lastTick;
    final Long2ObjectOpenHashMap<BlockData> before = new Long2ObjectOpenHashMap<>();
    final Long2ObjectOpenHashMap<BlockData> after = new Long2ObjectOpenHashMap<>();
    final Long2ObjectOpenHashMap<Map<String, String>> paramsBefore = new Long2ObjectOpenHashMap<>();
    final Long2ObjectOpenHashMap<Map<String, String>> paramsAfter = new Long2ObjectOpenHashMap<>();

    Batch(UUID world, String stroke, int tick, String label) {
        this.world = world;
        this.stroke = stroke;
        this.lastTick = tick;
        this.label = label;
    }

    public String label() {
        return label;
    }

    public long createdAt() {
        return createdAt;
    }

    public UUID world() {
        return world;
    }

    /** A brush stroke: {@code brush:<slot>:<type>}. */
    public String stroke() {
        return stroke;
    }

    /** Where the operation placed blocks, or {@code null}. */
    public Box bounds() {
        return bounds(after.isEmpty() ? paramsAfter.keySet() : after.keySet());
    }

    boolean isEmpty() {
        return after.isEmpty() && paramsAfter.isEmpty();
    }

    /** Adds a following brush stroke: the oldest before state wins, the newest after state too. */
    void absorb(Batch next) {
        next.before.forEach(before::putIfAbsent);
        after.putAll(next.after);
        for (Long2ObjectMap.Entry<Map<String, String>> entry : next.paramsBefore.long2ObjectEntrySet()) {
            if (!paramsBefore.containsKey(entry.getLongKey())) {
                paramsBefore.put(entry.getLongKey(), entry.getValue());
            }
        }
        paramsAfter.putAll(next.paramsAfter);
        lastTick = next.lastTick;
    }

    public int size() {
        return Math.max(after.size(), paramsAfter.size());
    }

    static Box bounds(it.unimi.dsi.fastutil.longs.LongCollection keys) {
        if (keys.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        LongIterator it = keys.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            minX = Math.min(minX, Keys.x(key));
            minY = Math.min(minY, Keys.y(key));
            minZ = Math.min(minZ, Keys.z(key));
            maxX = Math.max(maxX, Keys.x(key));
            maxY = Math.max(maxY, Keys.y(key));
            maxZ = Math.max(maxZ, Keys.z(key));
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
