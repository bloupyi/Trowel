package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.Keys;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.bukkit.block.data.BlockData;

import java.util.Map;

/**
 * What an operation wants to place, computed off the main thread.
 *
 * <p>Safeguards act while computing, in this order: a block outside the buildable area never
 * gets in (it is counted, to report it); a block the filter rejects (global mask, protected
 * marker) does not get in either; finally exceeding the limit stops the computation at once.
 * The limit therefore only counts what will really be placed, and an oversized shape does not
 * fill the memory before being refused.</p>
 *
 * <p>Marker settings travel with the blocks: a pasted or moved marker keeps its own. A position
 * placed twice keeps the last value.</p>
 */
public final class ChangeSet {

    /** What an operation may place at a position, current block included. */
    @FunctionalInterface
    public interface Gate {
        boolean accept(int x, int y, int z, BlockData data);
    }

    private final Long2ObjectLinkedOpenHashMap<BlockData> blocks = new Long2ObjectLinkedOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Map<String, String>> params = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet named = new LongOpenHashSet();
    private final Box bounds;
    private final int limit;
    private final Gate gate;
    private Progress progress;
    private long outside;
    private long refused;

    /** No bounds, no limit. */
    public ChangeSet() {
        this(null, Integer.MAX_VALUE, null);
    }

    public ChangeSet(Box bounds, int limit) {
        this(bounds, limit, null);
    }

    /**
     * @param bounds buildable part, or {@code null}
     * @param limit  number of blocks beyond which computing stops
     * @param gate   extra filter, or {@code null}
     */
    /** Publishes progress and stops if the player cancels. */
    public ChangeSet track(Progress progress) {
        this.progress = progress;
        return this;
    }

    public ChangeSet(Box bounds, int limit, Gate gate) {
        this.bounds = bounds;
        this.limit = limit;
        this.gate = gate;
    }

    /** Places this block; {@code null} (a pattern that places nothing here) is ignored. */
    public void set(int x, int y, int z, BlockData data) {
        if (data == null) {
            return;
        }
        if (bounds != null && !bounds.contains(x, y, z)) {
            outside++;
            return;
        }
        if (gate != null && !gate.accept(x, y, z, data)) {
            refused++;
            return;
        }
        long key = Keys.pack(x, y, z);
        if (blocks.size() >= limit && !blocks.containsKey(key)) {
            throw new IllegalArgumentException("Too many blocks: the operation exceeds " + limit
                    + ". Shrink the selection, the radius or the height.");
        }
        blocks.put(key, data);
        params.remove(key);
        if (NamedMarkers.is(data)) {
            named.add(key);
        } else {
            named.remove(key);
        }
        if (progress != null && (blocks.size() & 1023) == 0) {
            progress.found(blocks.size());
            progress.check();
        }
    }

    public void set(long key, BlockData data) {
        set(Keys.x(key), Keys.y(key), Keys.z(key), data);
    }

    public BlockData get(long key) {
        return blocks.get(key);
    }

    /** The block placed at this position is a marker, with these settings. No effect if the block was not accepted. */
    public void params(long key, Map<String, String> values) {
        if (!blocks.containsKey(key)) {
            return;
        }
        params.put(key, values);
    }

    /** Positions where a marker was asked for by name. */
    public LongOpenHashSet named() {
        return named;
    }

    public Long2ObjectLinkedOpenHashMap<BlockData> blocks() {
        return blocks;
    }

    public Long2ObjectOpenHashMap<Map<String, String>> params() {
        return params;
    }

    /** Blocks dropped because they left the buildable area. */
    public long outside() {
        return outside;
    }

    /** Blocks dropped by the global mask or marker protection. */
    public long refused() {
        return refused;
    }

    public int size() {
        return blocks.size();
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && params.isEmpty();
    }
}
