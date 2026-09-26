package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.MarkerSupport;
import com.stackmc.trowel.api.ParamKind;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.geom.MarkerTransform;
import com.stackmc.trowel.geom.Transform;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.bukkit.block.data.BlockData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A clipboard: blocks around an origin, and the settings of markers.
 *
 * <p>The origin is where the player stood when copying; pasting puts the origin at their feet,
 * and rotating turns around it, like WorldEdit. Air is kept: pasting a hollowed room must
 * empty it.</p>
 */
public final class Clipboard {

    private final Long2ObjectOpenHashMap<BlockData> cells;
    private final Long2ObjectOpenHashMap<Map<String, String>> params;
    private final Box bounds;
    private final UUID world;
    private final int originX;
    private final int originY;
    private final int originZ;

    private Clipboard(Long2ObjectOpenHashMap<BlockData> cells, Long2ObjectOpenHashMap<Map<String, String>> params,
                      Box bounds, UUID world, int originX, int originY, int originZ) {
        this.cells = cells;
        this.params = params;
        this.bounds = bounds;
        this.world = world;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
    }

    /** A clipboard read from the library: it comes from no world. */
    public static Clipboard of(Long2ObjectOpenHashMap<BlockData> cells, Long2ObjectOpenHashMap<Map<String, String>> params,
                               Box bounds) {
        return new Clipboard(cells, params, bounds, null, 0, 0, 0);
    }

    /** Blocks, by position relative to the origin. */
    public Long2ObjectOpenHashMap<BlockData> cells() {
        return cells;
    }

    /** Markers and their settings, by relative position. */
    public Long2ObjectOpenHashMap<Map<String, String>> params() {
        return params;
    }

    /** Copies the box, relative to the given origin. Off the main thread. */
    public static Clipboard copy(EditContext context, Box box, UUID world, int originX, int originY, int originZ) {
        Long2ObjectOpenHashMap<BlockData> cells = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<Map<String, String>> params = new Long2ObjectOpenHashMap<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    long relative = Keys.pack(x - originX, y - originY, z - originZ);
                    BlockData data = context.view().get(x, y, z);
                    cells.put(relative, data);
                    if (context.view().marker(x, y, z)) {
                        params.put(relative, new LinkedHashMap<>(context.paramsAt(Keys.pack(x, y, z))));
                    }
                }
            }
        }
        return new Clipboard(cells, params, box.shift(-originX, -originY, -originZ), world, originX, originY, originZ);
    }

    /**
     * The same clipboard, rotated or flipped around the origin.
     *
     * <p>A marker keeps its block; its zone sizes become signed so it still covers the same
     * blocks, and its settings (direction, facing, path, width and depth) rotate with it.</p>
     */
    public Clipboard transformed(Transform transform, MarkerSupport markers, BlockTransforms blocks) {
        Long2ObjectOpenHashMap<BlockData> turned = new Long2ObjectOpenHashMap<>(cells.size());
        for (Long2ObjectMap.Entry<BlockData> entry : cells.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int[] p = transform.apply(Keys.x(key), Keys.y(key), Keys.z(key));
            turned.put(Keys.pack(p[0], p[1], p[2]), blocks.apply(entry.getValue(), transform));
        }

        Long2ObjectOpenHashMap<Map<String, String>> turnedParams = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<Map<String, String>> entry : params.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            BlockData data = cells.get(key);
            if (data == null) {
                continue;
            }
            Map<String, String> values = entry.getValue();
            Map<String, ParamKind> kinds = markers.kinds(data.getMaterial());
            int x = Keys.x(key);
            int y = Keys.y(key);
            int z = Keys.z(key);
            int[] p = transform.apply(x, y, z);
            long target = Keys.pack(p[0], p[1], p[2]);

            if (kinds.isEmpty()) {
                turnedParams.put(target, values);
                continue;
            }

            Map<String, String> effective = markers.effective(data.getMaterial(), values);
            Map<String, String> changed = MarkerTransform.params(effective, kinds, transform);
            Map<String, String> kept = new LinkedHashMap<>();
            changed.forEach((name, value) -> {
                ParamKind kind = kinds.get(name);
                boolean size = kind == ParamKind.SIZE_X || kind == ParamKind.SIZE_Y || kind == ParamKind.SIZE_Z;
                if (values.containsKey(name) || (size && !"1".equals(value))) {
                    kept.put(name, value);
                }
            });

            int w = size(effective, kinds, ParamKind.SIZE_X);
            int h = size(effective, kinds, ParamKind.SIZE_Y);
            int d = size(effective, kinds, ParamKind.SIZE_Z);
            if (w != 1 || h != 1 || d != 1) {
                int[] spans = MarkerTransform.spans(x, y, z, w, h, d, transform);
                ParamKind[] axes = {ParamKind.SIZE_X, ParamKind.SIZE_Y, ParamKind.SIZE_Z};
                for (int axis = 0; axis < 3; axis++) {
                    for (Map.Entry<String, ParamKind> kind : kinds.entrySet()) {
                        if (kind.getValue() == axes[axis]) {
                            if (spans[axis] == 1 && !values.containsKey(kind.getKey())) {
                                kept.remove(kind.getKey());
                            } else {
                                kept.put(kind.getKey(), String.valueOf(spans[axis]));
                            }
                        }
                    }
                }
            }
            turnedParams.put(target, kept);
        }

        int[] a = transform.apply(bounds.minX(), bounds.minY(), bounds.minZ());
        int[] b = transform.apply(bounds.maxX(), bounds.maxY(), bounds.maxZ());
        return new Clipboard(turned, turnedParams, new Box(a[0], a[1], a[2], b[0], b[1], b[2]),
                world, originX, originY, originZ);
    }

    private static int size(Map<String, String> values, Map<String, ParamKind> kinds, ParamKind axis) {
        for (Map.Entry<String, ParamKind> entry : kinds.entrySet()) {
            if (entry.getValue() == axis) {
                try {
                    int size = Integer.parseInt(values.getOrDefault(entry.getKey(), "1").trim());
                    return size == 0 ? 1 : size;
                } catch (NumberFormatException e) {
                    return 1;
                }
            }
        }
        return 1;
    }

    /** Places the clipboard, its origin at (x, y, z). */
    public void pasteInto(ChangeSet changes, MarkerSupport markers, int x, int y, int z, boolean skipAir) {
        for (Long2ObjectMap.Entry<BlockData> entry : cells.long2ObjectEntrySet()) {
            BlockData data = entry.getValue();
            if (skipAir && data.getMaterial().isAir()) {
                continue;
            }
            long key = entry.getLongKey();
            long target = Keys.pack(Keys.x(key) + x, Keys.y(key) + y, Keys.z(key) + z);
            changes.set(target, data);
            Map<String, String> values = params.get(key);
            if (values != null) {
                changes.params(target, values);
            }
        }
    }

    /** The block at this position relative to the origin, or {@code null} outside the clipboard. */
    public BlockData at(int dx, int dy, int dz) {
        return cells.get(Keys.pack(dx, dy, dz));
    }

    /** The same clipboard where each marker block carries its settings, for patterns that place it. */
    public Clipboard withCarriedMarkers() {
        if (params.isEmpty()) {
            return this;
        }
        Long2ObjectOpenHashMap<BlockData> carrying = new Long2ObjectOpenHashMap<>(cells);
        for (Long2ObjectMap.Entry<Map<String, String>> entry : params.long2ObjectEntrySet()) {
            BlockData data = cells.get(entry.getLongKey());
            if (data != null) {
                carrying.put(entry.getLongKey(), NamedMarkers.carrying(data, entry.getValue()));
            }
        }
        return new Clipboard(carrying, params, bounds, world, originX, originY, originZ);
    }

    /** The clipboard repeated forever, like tiling: for the {@code #clipboard} pattern. */
    public BlockData tiled(int x, int y, int z) {
        int dx = bounds.minX() + Math.floorMod(x, bounds.width());
        int dy = bounds.minY() + Math.floorMod(y, bounds.height());
        int dz = bounds.minZ() + Math.floorMod(z, bounds.depth());
        return cells.get(Keys.pack(dx, dy, dz));
    }

    public Box boundsAt(int x, int y, int z) {
        return bounds.shift(x, y, z);
    }

    public Box bounds() {
        return bounds;
    }

    public UUID world() {
        return world;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public int markerCount() {
        return params.size();
    }

    public String size() {
        return bounds.size();
    }
}
