package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.MarkerSupport;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;

import java.util.Map;

/**
 * Everything an operation may read while computing, frozen when it starts.
 *
 * @param params  the real markers of the read area and their settings, by position (empty: no settings)
 * @param bounds  buildable part, or {@code null}
 * @param limit   blocks an operation may change
 * @param gate    global mask and marker protection, or {@code null}
 * @param progress progress, to advance during long loops
 * @param tiles   the blocks with data of their own (signs, chests, banners, heads) of the read area,
 *                frozen, by position
 */
public record EditContext(BlockView view, Long2ObjectMap<Map<String, String>> params, MarkerSupport markers,
                          Box bounds, int limit, ChangeSet.Gate gate, BlockTransforms transforms, BlockData air,
                          Progress progress, Long2ObjectMap<BlockState> tiles) {

    public EditContext(BlockView view, Long2ObjectMap<Map<String, String>> params, MarkerSupport markers, Box bounds,
                       int limit, ChangeSet.Gate gate, BlockTransforms transforms, BlockData air, Progress progress) {
        this(view, params, markers, bounds, limit, gate, transforms, air, progress, new Long2ObjectOpenHashMap<>());
    }

    /** An empty batch that drops what leaves the area or the filter rejects, and stops at the limit. */
    public ChangeSet changes() {
        return new ChangeSet(bounds, limit, gate).track(progress);
    }

    /** The view for relief algorithms: markers are air there. Masks see everything. */
    public BlockView terrain() {
        return Views.hideMarkers(view, air);
    }

    public Map<String, String> paramsAt(long key) {
        Map<String, String> values = params.get(key);
        return values == null ? Map.of() : values;
    }

    /** The data of the block at this position (sign text, chest content...), or {@code null}. */
    public BlockState tileAt(long key) {
        return tiles.get(key);
    }
}
