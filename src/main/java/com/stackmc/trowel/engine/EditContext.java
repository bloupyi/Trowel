package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.MarkerSupport;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
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
 */
public record EditContext(BlockView view, Long2ObjectMap<Map<String, String>> params, MarkerSupport markers,
                          Box bounds, int limit, ChangeSet.Gate gate, BlockTransforms transforms, BlockData air,
                          Progress progress) {

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
}
