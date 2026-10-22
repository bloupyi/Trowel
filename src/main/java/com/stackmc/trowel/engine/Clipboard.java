package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.MarkerSupport;
import com.stackmc.trowel.api.ParamKind;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.geom.MarkerTransform;
import com.stackmc.trowel.geom.Polygon;
import com.stackmc.trowel.geom.Transform;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

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
    private final Long2ObjectOpenHashMap<BlockState> tiles;
    private final Box bounds;
    private final UUID world;
    private final int originX;
    private final int originY;
    private final int originZ;

    private Clipboard(Long2ObjectOpenHashMap<BlockData> cells, Long2ObjectOpenHashMap<Map<String, String>> params,
                      Long2ObjectOpenHashMap<BlockState> tiles, Box bounds, UUID world, int originX, int originY,
                      int originZ) {
        this.cells = cells;
        this.params = params;
        this.tiles = tiles;
        this.bounds = bounds;
        this.world = world;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
    }

    /** A clipboard read from the library: it comes from no world. */
    public static Clipboard of(Long2ObjectOpenHashMap<BlockData> cells, Long2ObjectOpenHashMap<Map<String, String>> params,
                               Box bounds) {
        return of(cells, params, new Long2ObjectOpenHashMap<>(), bounds);
    }

    public static Clipboard of(Long2ObjectOpenHashMap<BlockData> cells, Long2ObjectOpenHashMap<Map<String, String>> params,
                               Long2ObjectOpenHashMap<BlockState> tiles, Box bounds) {
        return new Clipboard(cells, params, tiles, bounds, null, 0, 0, 0);
    }

    /** Data of the blocks that have some (sign text, chest content...), by relative position. */
    public Long2ObjectOpenHashMap<BlockState> tiles() {
        return tiles;
    }

    /** Blocks, by position relative to the origin. */
    public Long2ObjectOpenHashMap<BlockData> cells() {
        return cells;
    }

    /** Markers and their settings, by relative position. */
    public Long2ObjectOpenHashMap<Map<String, String>> params() {
        return params;
    }

    /**
     * Copies the box, relative to the given origin, only its columns inside {@code polygon} if not
     * {@code null}. Off the main thread.
     */
    public static Clipboard copy(EditContext context, Box box, Polygon polygon, UUID world, int originX, int originY,
                                 int originZ) {
        Long2ObjectOpenHashMap<BlockData> cells = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<Map<String, String>> params = new Long2ObjectOpenHashMap<>();
        Long2ObjectOpenHashMap<BlockState> tiles = new Long2ObjectOpenHashMap<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    if (polygon != null && !polygon.contains(x, z)) {
                        continue;
                    }
                    long relative = Keys.pack(x - originX, y - originY, z - originZ);
                    BlockData data = context.view().get(x, y, z);
                    cells.put(relative, data);
                    BlockState tile = context.tileAt(Keys.pack(x, y, z));
                    if (tile != null) {
                        tiles.put(relative, tile);
                    }
                    if (context.view().marker(x, y, z)) {
                        params.put(relative, new LinkedHashMap<>(context.paramsAt(Keys.pack(x, y, z))));
                    }
                }
            }
        }
        return new Clipboard(cells, params, tiles, box.shift(-originX, -originY, -originZ), world, originX, originY,
                originZ);
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

        Long2ObjectOpenHashMap<BlockState> turnedTiles = new Long2ObjectOpenHashMap<>(tiles.size());
        for (Long2ObjectMap.Entry<BlockState> entry : tiles.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int[] p = transform.apply(Keys.x(key), Keys.y(key), Keys.z(key));
            turnedTiles.put(Keys.pack(p[0], p[1], p[2]), entry.getValue());
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
        return new Clipboard(turned, turnedParams, turnedTiles, new Box(a[0], a[1], a[2], b[0], b[1], b[2]),
                world, originX, originY, originZ);
    }

    /**
     * The same clipboard, rotated by any angle around the origin, clockwise seen from above.
     *
     * <p>The nearest quarter turn is done first, so block states and marker settings turn with
     * it; the rest (at most 45 degrees) moves blocks only, see {@link #turnFreely}. Markers and
     * blocks with data (chests, signs) are placed once, where their center lands.</p>
     */
    public Clipboard rotated(double degrees, MarkerSupport markers, BlockTransforms blocks) {
        int quarters = (int) Math.round(degrees / 90.0);
        double rest = degrees - quarters * 90.0;
        Transform turn = Transform.rotation(quarters * 90);
        Clipboard base = turn == null ? this : transformed(turn, markers, blocks);
        if (Math.abs(rest) < 1e-6) {
            return base;
        }
        LongOpenHashSet fixed = new LongOpenHashSet(base.params.keySet());
        fixed.addAll(base.tiles.keySet());
        Long2ObjectOpenHashMap<BlockData> turned = turnFreely(base.cells, rest, 0, 0,
                data -> data.getMaterial().isAir(), data -> data.getMaterial().isSolid(), fixed);
        Long2ObjectOpenHashMap<Map<String, String>> turnedParams = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<Map<String, String>> entry : base.params.long2ObjectEntrySet()) {
            BlockData data = base.cells.get(entry.getLongKey());
            if (data == null) {
                continue;
            }
            long target = turnPoint(entry.getLongKey(), rest);
            turned.put(target, data);
            turnedParams.put(target, entry.getValue());
        }
        Long2ObjectOpenHashMap<BlockState> turnedTiles = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<BlockState> entry : base.tiles.long2ObjectEntrySet()) {
            BlockData data = base.cells.get(entry.getLongKey());
            if (data == null) {
                continue;
            }
            long target = turnPoint(entry.getLongKey(), rest);
            turned.put(target, data);
            turnedTiles.put(target, entry.getValue());
        }
        return new Clipboard(turned, turnedParams, turnedTiles, boundsOf(turned), world, originX, originY, originZ);
    }

    /** Samples per side of a column when rotating: 5 x 5 points decide what it holds. */
    private static final int SAMPLES = 5;

    /**
     * Cells rotated by any angle around the vertical axis through (cx, cz), clockwise seen from above.
     *
     * <ol>
     *   <li>Each target column is sampled 5 x 5 times through the inverse rotation; a place takes the
     *       block covering at least half of it, the one covering most, so outlines do not fray.</li>
     *   <li>A block that no place took goes where its center lands: a lone post or torch is never lost.</li>
     *   <li>Two solid blocks touching only by an edge, close in the original, get the block between
     *       them: a wall turned by 45 degrees stays a wall, not a row of blocks held by their corners.</li>
     *   <li>A detail (torch, ladder, flower...) that was held by a solid block and no longer is gets
     *       removed instead of floating.</li>
     * </ol>
     *
     * @param empty  air: fills a place without being a block
     * @param solid  blocks that hold details and must stay joined
     * @param fixed  cells left out: the caller places them once, where their center lands (markers, chests)
     */
    static <T> Long2ObjectOpenHashMap<T> turnFreely(Long2ObjectOpenHashMap<T> cells, double degrees, double cx,
                                                    double cz, Predicate<T> empty, Predicate<T> solid, LongSet fixed) {
        double a = Math.toRadians(degrees);
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        Box from = boundsOf(cells);
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double x : new double[]{from.minX() - 0.5 - cx, from.maxX() + 0.5 - cx}) {
            for (double z : new double[]{from.minZ() - 0.5 - cz, from.maxZ() + 0.5 - cz}) {
                double tx = x * cos - z * sin + cx;
                double tz = x * sin + z * cos + cz;
                minX = Math.min(minX, tx);
                maxX = Math.max(maxX, tx);
                minZ = Math.min(minZ, tz);
                maxZ = Math.max(maxZ, tz);
            }
        }

        T air = null;
        for (T value : cells.values()) {
            if (empty.test(value)) {
                air = value;
                break;
            }
        }
        Long2ObjectOpenHashMap<T> turned = new Long2ObjectOpenHashMap<>(cells.size());
        // What each place holds comes from this source cell.
        Long2LongOpenHashMap origin = new Long2LongOpenHashMap();
        // A place left without a solid block: the solid block that covered most of it, and how much.
        Long2ObjectOpenHashMap<T> runnerUp = new Long2ObjectOpenHashMap<>();
        Long2IntOpenHashMap runnerUpWeight = new Long2IntOpenHashMap();
        int total = SAMPLES * SAMPLES;
        int[] columnX = new int[total];
        int[] columnZ = new int[total];
        int[] columnWeight = new int[total];
        List<T> values = new ArrayList<>(total);
        int[] valueWeight = new int[total];
        long[] valueSource = new long[total];
        int[] valueSourceWeight = new int[total];

        for (int x = (int) Math.round(minX); x <= (int) Math.round(maxX); x++) {
            for (int z = (int) Math.round(minZ); z <= (int) Math.round(maxZ); z++) {
                int columns = 0;
                for (int i = -1; i < total; i++) {
                    // First the center: on a tie, the block under it wins.
                    double px = i < 0 ? 0 : (i % SAMPLES + 0.5) / SAMPLES - 0.5;
                    double pz = i < 0 ? 0 : (i / SAMPLES + 0.5) / SAMPLES - 0.5;
                    double ux = x + px - cx;
                    double uz = z + pz - cz;
                    int sx = (int) Math.round(ux * cos + uz * sin + cx);
                    int sz = (int) Math.round(-ux * sin + uz * cos + cz);
                    int c = 0;
                    while (c < columns && (columnX[c] != sx || columnZ[c] != sz)) {
                        c++;
                    }
                    if (c == columns) {
                        columnX[c] = sx;
                        columnZ[c] = sz;
                        columnWeight[c] = 0;
                        columns++;
                    }
                    if (i >= 0) {
                        columnWeight[c]++;
                    }
                }
                for (int y = from.minY(); y <= from.maxY(); y++) {
                    values.clear();
                    int present = 0;
                    int full = 0;
                    for (int c = 0; c < columns; c++) {
                        long source = Keys.pack(columnX[c], y, columnZ[c]);
                        T value = cells.get(source);
                        if (value == null || columnWeight[c] == 0 || fixed.contains(source)) {
                            continue;
                        }
                        present += columnWeight[c];
                        if (!empty.test(value)) {
                            full += columnWeight[c];
                        }
                        int v = values.indexOf(value);
                        if (v < 0) {
                            v = values.size();
                            values.add(value);
                            valueWeight[v] = 0;
                            valueSourceWeight[v] = 0;
                        }
                        valueWeight[v] += columnWeight[c];
                        if (columnWeight[c] > valueSourceWeight[v]) {
                            valueSource[v] = source;
                            valueSourceWeight[v] = columnWeight[c];
                        }
                    }
                    int best = -1;
                    int bestEmpty = -1;
                    for (int v = 0; v < values.size(); v++) {
                        if (empty.test(values.get(v))) {
                            if (bestEmpty < 0 || valueWeight[v] > valueWeight[bestEmpty]) {
                                bestEmpty = v;
                            }
                        } else if (best < 0 || valueWeight[v] > valueWeight[best]) {
                            best = v;
                        }
                    }
                    long target = Keys.pack(x, y, z);
                    if (2 * full >= total) {
                        turned.put(target, values.get(best));
                        origin.put(target, valueSource[best]);
                        continue;
                    }
                    if (2 * present >= total && bestEmpty >= 0) {
                        turned.put(target, values.get(bestEmpty));
                    }
                    if (best >= 0 && solid.test(values.get(best))) {
                        runnerUp.put(target, values.get(best));
                        runnerUpWeight.put(target, valueWeight[best]);
                        origin.put(target, valueSource[best]);
                    }
                }
            }
        }

        LongOpenHashSet used = new LongOpenHashSet();
        for (Long2ObjectMap.Entry<T> entry : turned.long2ObjectEntrySet()) {
            if (!empty.test(entry.getValue())) {
                used.add(origin.get(entry.getLongKey()));
            }
        }
        for (Long2ObjectMap.Entry<T> entry : cells.long2ObjectEntrySet()) {
            long source = entry.getLongKey();
            if (empty.test(entry.getValue()) || fixed.contains(source) || used.contains(source)) {
                continue;
            }
            double ux = Keys.x(source) - cx;
            double uz = Keys.z(source) - cz;
            long target = Keys.pack((int) Math.round(ux * cos - uz * sin + cx), Keys.y(source),
                    (int) Math.round(ux * sin + uz * cos + cz));
            T there = turned.get(target);
            if (there == null || empty.test(there)) {
                turned.put(target, entry.getValue());
                origin.put(target, source);
            }
        }

        for (int pass = 0; pass < 4; pass++) {
            boolean changed = false;
            for (long key : turned.keySet().toLongArray()) {
                T value = turned.get(key);
                if (!solid.test(value)) {
                    continue;
                }
                int x = Keys.x(key);
                int y = Keys.y(key);
                int z = Keys.z(key);
                for (int dx = -1; dx <= 1; dx += 2) {
                    long diagonal = Keys.pack(x + dx, y, z + 1);
                    T other = turned.get(diagonal);
                    long side = Keys.pack(x + dx, y, z);
                    long below = Keys.pack(x, y, z + 1);
                    if (other == null || !solid.test(other) || isSolid(turned, side, solid)
                            || isSolid(turned, below, solid)) {
                        continue;
                    }
                    long a1 = origin.get(key);
                    long a2 = origin.get(diagonal);
                    if (Math.abs(Keys.x(a1) - Keys.x(a2)) + Math.abs(Keys.z(a1) - Keys.z(a2)) > 2) {
                        continue;
                    }
                    long fill = runnerUpWeight.get(side) >= runnerUpWeight.get(below) ? side : below;
                    T filler = runnerUp.get(fill);
                    turned.put(fill, filler != null ? filler : value);
                    origin.put(fill, filler != null ? origin.get(fill) : a1);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }

        LongOpenHashSet heldBefore = held(cells, empty, solid);
        LongOpenHashSet heldNow = held(turned, empty, solid);
        for (long key : turned.keySet().toLongArray()) {
            T value = turned.get(key);
            if (empty.test(value) || solid.test(value) || heldNow.contains(key) || !origin.containsKey(key)
                    || !heldBefore.contains(origin.get(key))) {
                continue;
            }
            if (air != null) {
                turned.put(key, air);
            } else {
                turned.remove(key);
            }
        }
        return turned;
    }

    private static <T> boolean isSolid(Long2ObjectOpenHashMap<T> cells, long key, Predicate<T> solid) {
        T value = cells.get(key);
        return value != null && solid.test(value);
    }

    /** Details (neither air nor solid) held by a solid block, directly or through other details. */
    private static <T> LongOpenHashSet held(Long2ObjectOpenHashMap<T> cells, Predicate<T> empty, Predicate<T> solid) {
        LongOpenHashSet held = new LongOpenHashSet();
        LongArrayList pending = new LongArrayList();
        for (Long2ObjectMap.Entry<T> entry : cells.long2ObjectEntrySet()) {
            if (solid.test(entry.getValue())) {
                pending.add(entry.getLongKey());
            }
        }
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!pending.isEmpty()) {
            long key = pending.removeLong(pending.size() - 1);
            for (int[] d : around) {
                long next = Keys.offset(key, d[0], d[1], d[2]);
                T value = cells.get(next);
                if (value != null && !empty.test(value) && !solid.test(value) && held.add(next)) {
                    pending.add(next);
                }
            }
        }
        return held;
    }

    /** A relative position rotated by any angle around the vertical axis, rounded to a block. */
    static long turnPoint(long key, double degrees) {
        double a = Math.toRadians(degrees);
        int x = Keys.x(key);
        int z = Keys.z(key);
        return Keys.pack((int) Math.round(x * Math.cos(a) - z * Math.sin(a)), Keys.y(key),
                (int) Math.round(x * Math.sin(a) + z * Math.cos(a)));
    }

    private static Box boundsOf(Long2ObjectOpenHashMap<?> cells) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long key : cells.keySet()) {
            minX = Math.min(minX, Keys.x(key));
            minY = Math.min(minY, Keys.y(key));
            minZ = Math.min(minZ, Keys.z(key));
            maxX = Math.max(maxX, Keys.x(key));
            maxY = Math.max(maxY, Keys.y(key));
            maxZ = Math.max(maxZ, Keys.z(key));
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
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
            changes.tile(target, tiles.get(key));
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
        return new Clipboard(carrying, params, tiles, bounds, world, originX, originY, originZ);
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
