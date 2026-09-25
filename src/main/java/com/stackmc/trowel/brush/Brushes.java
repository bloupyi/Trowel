package com.stackmc.trowel.brush;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.engine.BlockView;
import com.stackmc.trowel.engine.ChangeSet;
import com.stackmc.trowel.engine.Clipboard;
import com.stackmc.trowel.engine.EditContext;
import com.stackmc.trowel.engine.RegionOps;
import com.stackmc.trowel.engine.Terrain;
import com.stackmc.trowel.engine.Views;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.geom.Noise;
import com.stackmc.trowel.geom.Shapes;
import com.stackmc.trowel.geom.Transform;
import com.stackmc.trowel.pattern.Mask;
import com.stackmc.trowel.pattern.Pattern;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What a brush stroke does. Computed off the main thread, like every operation.
 *
 * <p>Two views of the world are used: the real one, for the player's masks (which may target
 * markers), and the relief one, where markers are air: a marker glass is neither a ground to
 * flatten nor a material to copy.</p>
 *
 * <p>The settings are those the brush type uses: a setting inherited from another type, and
 * invisible in its window, stays neutral.</p>
 */
public final class Brushes {

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    /** Flatten looks for the ground up to this many blocks above the radius, and this many below the target. */
    private static final int FLATTEN_UP = 24;
    private static final int FLATTEN_DOWN = 32;

    private Brushes() {
    }

    /** What a stroke needs to read around the aimed block. */
    public static Box reads(BrushSettings settings, int x, int y, int z, BlockFace face, Clipboard clipboard) {
        BrushSettings s = settings.effective();
        int r = s.size();
        return switch (s.type()) {
            case FLATTEN -> new Box(x - r, y - FLATTEN_DOWN - 4, z - r, x + r, y + r + FLATTEN_UP + 2, z + r);
            case SMOOTH -> new Box(x - r - 1, y - smoothReach(r) - 2, z - r - 1, x + r + 1, y + smoothReach(r) + 2, z + r + 1);
            case CYLINDER -> Box.around(x, y, z, r).union(new Box(x, y - s.height(), z, x, y + s.height(), z));
            case RAISE, LOWER, OVERLAY, SCATTER -> new Box(x - r, y - r - 2 * s.intensity() - s.height() - 10,
                    z - r, x + r, y + r + 2 * s.intensity() + 10, z + r);
            case BLOB, BOULDER -> Box.around(x, y, z, (int) Math.ceil(r * (1 + s.falloff() / 100.0)) + 1);
            case UNDERLAY -> new Box(x - r, y - r - s.height() - 2, z - r, x + r, y + r + 2, z + r);
            case SNOWCONE -> new Box(x - r, y - r - 2, z - r, x + r, y + r + s.height() + 2, z + r);
            case IVY -> new Box(x - r - 1, y - r - s.height() - 2, z - r - 1, x + r + 1, y + r + 2, z + r + 1);
            case STALACTITE -> new Box(x - r - 1, y - r - s.height() - 2, z - r - 1, x + r + 1,
                    y + r + s.height() + 2, z + r + 1);
            case CRACKS -> Box.around(x, y, z, r + s.height() + 2);
            case SCREE -> new Box(x - 2 * r - 2, y - 2 * r - 12, z - 2 * r - 2, x + 2 * r + 2, y + r + 4, z + 2 * r + 2);
            case EXTRUDE -> Box.around(x, y, z, r + 1).union(Box.around(x + face.getModX() * s.height(),
                    y + face.getModY() * s.height(), z + face.getModZ() * s.height(), r + 1));
            case SPIKE -> Box.around(x, y, z, r + 1).union(Box.around(x + face.getModX() * s.height(),
                    y + face.getModY() * s.height(), z + face.getModZ() * s.height(), r + 1));
            case STAMP -> {
                if (clipboard == null) {
                    yield Box.around(x, y, z, 1);
                }
                Box b = clipboard.bounds();
                int reach = Math.max(Math.max(Math.abs(b.minX()), Math.abs(b.maxX())),
                        Math.max(Math.abs(b.minZ()), Math.abs(b.maxZ())));
                yield new Box(x - reach - 1, y + b.minY() - 1, z - reach - 1, x + reach + 1,
                        y + b.maxY() + 2, z + reach + 1);
            }
            default -> Box.around(x, y, z, r + 1);
        };
    }

    private static int smoothReach(int r) {
        return 2 * r + 8;
    }

    public static ChangeSet compute(EditContext context, BrushSettings settings, Pattern pattern, Mask mask,
                                    int x, int y, int z, BlockFace face, Clipboard clipboard) {
        BrushSettings s = settings.effective();
        Stroke stroke = new Stroke(context, s, pattern, mask, context.changes(), context.view(), context.terrain());
        int r = s.size();
        switch (s.type()) {
            case SPHERE -> {
                Pattern within = pattern.within(y - r, y + r);
                Shapes.ellipsoid(x, y, z, r, r, r, false, (a, b, c) -> {
                    if (s.surface() && (stroke.terrain.air(a, b, c) || !stroke.terrain.exposed(a, b, c))) {
                        return;
                    }
                    if (stroke.allowed(a, b, c) && stroke.keep(distance(a - x, b - y, c - z), r)) {
                        stroke.changes.set(a, b, c, within.at(a, b, c));
                    }
                });
            }
            case CYLINDER -> {
                int bottom = y - (s.height() - 1) / 2;
                Pattern within = pattern.within(bottom, bottom + s.height() - 1);
                Shapes.cylinder(x, bottom, z, r, r, s.height(), false, (a, b, c) -> {
                    if (stroke.allowed(a, b, c) && stroke.keep(distance(a - x, 0, c - z), r)) {
                        stroke.changes.set(a, b, c, within.at(a, b, c));
                    }
                });
            }
            case CUBE -> {
                Pattern within = pattern.within(y - r, y + r);
                for (int a = x - r; a <= x + r; a++) {
                    for (int b = y - r; b <= y + r; b++) {
                        for (int c = z - r; c <= z + r; c++) {
                            if (stroke.allowed(a, b, c) && stroke.keep(0, r)) {
                                stroke.changes.set(a, b, c, within.at(a, b, c));
                            }
                        }
                    }
                }
            }
            case PAINT -> {
                Pattern within = pattern.within(y - r, y + r);
                Shapes.ellipsoid(x, y, z, r, r, r, false, (a, b, c) -> {
                    if (!stroke.terrain.air(a, b, c) && stroke.terrain.exposed(a, b, c) && stroke.allowed(a, b, c)
                            && stroke.keep(distance(a - x, b - y, c - z), r)) {
                        stroke.changes.set(a, b, c, within.at(a, b, c));
                    }
                });
            }
            case SPLATTER -> splatter(stroke, x, y, z);
            case OVERLAY, SCATTER -> surfaceColumns(stroke, x, y, z);
            case SMOOTH -> smooth(stroke, x, y, z);
            case RAISE, LOWER -> relief(stroke, x, y, z);
            case FLATTEN -> flatten(stroke, x, y, z);
            case ERODE -> erode(stroke, x, y, z);
            case BLEND -> blend(stroke, x, y, z);
            case STAMP -> {
                if (clipboard == null) {
                    throw new IllegalArgumentException("Empty clipboard: //copy first.");
                }
                Clipboard placed = clipboard;
                if (s.random()) {
                    Transform turn = Transform.rotation(90 * ThreadLocalRandom.current().nextInt(4));
                    if (turn != null) {
                        placed = placed.transformed(turn, context.markers(), context.transforms(), context.air());
                    }
                }
                placed.pasteInto(stroke.changes, context.markers(), x + face.getModX(), y + face.getModY(),
                        z + face.getModZ(), true);
            }
            case SPIKE -> spike(stroke, x, y, z, face);
            case BLOB -> blob(stroke, x, y, z);
            case BUCKET -> bucket(stroke, x, y, z);
            case DISK, RING -> disk(stroke, x, y, z, face);
            case UNDERLAY -> underlay(stroke, x, y, z);
            case FRACTURE -> fracture(stroke, x, y, z);
            case DRAIN -> drain(stroke, x, y, z);
            case FILLDOWN -> fillDown(stroke, x, y, z);
            case SHELL -> shell(stroke, x, y, z);
            case SNOWCONE -> snowcone(stroke, x, y, z);
            case BOULDER -> boulder(stroke, x, y, z);
            case EXTRUDE -> extrude(stroke, x, y, z, face);
            case IVY -> ivy(stroke, x, y, z);
            case STALACTITE -> stalactites(stroke, x, y, z);
            case CRACKS -> cracks(stroke, x, y, z);
            case SCREE -> scree(stroke, x, y, z);
        }
        return stroke.changes;
    }

    /** What a stroke has at hand: settings, pattern, mask, batch, and both views. */
    private record Stroke(EditContext context, BrushSettings s, Pattern pattern, Mask mask, ChangeSet changes,
                          BlockView real, BlockView terrain) {

        /** The player's mask, read on the real world: it may target markers. */
        boolean allowed(int x, int y, int z) {
            return mask == null || mask.test(x, y, z, real);
        }

        /** goPaint randomness: the chance, reduced towards the edge by the falloff. */
        boolean keep(double distance, double radius) {
            double p = s.chance() / 100.0;
            if (s.falloff() > 0) {
                double t = Math.min(1, distance / (radius + 0.5));
                p *= 1 - s.falloff() / 100.0 * t * t;
            }
            return p >= 1 || ThreadLocalRandom.current().nextDouble() < p;
        }
    }

    private static double distance(int dx, int dy, int dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Random seeds, then each pass grows the patches towards their neighbours. */
    private static void splatter(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = s.size();
        LongArrayList candidates = new LongArrayList();
        Shapes.ellipsoid(x, y, z, r, r, r, false, (a, b, c) -> {
            boolean ok = s.surface() ? !view.air(a, b, c) && view.exposed(a, b, c) : !view.air(a, b, c);
            if (ok && stroke.allowed(a, b, c)) {
                candidates.add(Keys.pack(a, b, c));
            }
        });
        LongOpenHashSet chosen = new LongOpenHashSet();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (long key : candidates) {
            if (random.nextInt(100) < s.chance()) {
                chosen.add(key);
            }
        }
        for (int pass = 0; pass < s.iterations(); pass++) {
            LongArrayList grown = new LongArrayList();
            for (long key : candidates) {
                if (chosen.contains(key)) {
                    continue;
                }
                int around = 0;
                for (int[] f : FACES) {
                    if (chosen.contains(Keys.offset(key, f[0], f[1], f[2]))) {
                        around++;
                    }
                }
                if (around > 0 && random.nextDouble() < 0.2 + 0.12 * around) {
                    grown.add(key);
                }
            }
            chosen.addAll(grown);
        }
        Pattern within = stroke.pattern.within(y - r, y + r);
        for (long key : chosen) {
            stroke.changes.set(key, within.at(Keys.x(key), Keys.y(key), Keys.z(key)));
        }
    }

    /** Overlay and scatter: column by column, on the relief under the disc. */
    private static void surfaceColumns(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = s.size();
        double limit = (r + 0.5) * (r + 0.5);
        Pattern within = stroke.pattern.within(y - r - s.height(), y + r + 1);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > limit) {
                    continue;
                }
                int cx = x + dx;
                int cz = z + dz;
                int top = Terrain.surface(view, cx, cz, y + r + 2, y - r - 2);
                if (top == Terrain.NONE || !stroke.keep(Math.sqrt(dx * dx + dz * dz), r)) {
                    continue;
                }
                if (s.type() == BrushType.SCATTER) {
                    if (view.air(cx, top + 1, cz) && stroke.allowed(cx, top, cz)) {
                        stroke.changes.set(cx, top + 1, cz, within.at(cx, top + 1, cz));
                    }
                    continue;
                }
                for (int i = 0; i < s.height(); i++) {
                    int cy = top - i;
                    if (!view.air(cx, cy, cz) && stroke.allowed(cx, cy, cz)) {
                        stroke.changes.set(cx, cy, cz, within.at(cx, cy, cz));
                    }
                }
            }
        }
    }

    /**
     * Ground height of a column for the relief brushes.
     *
     * <p>A column solid up to the top of the search (a pillar, a cliff) has its surface above:
     * it is taken at the top of the search rather than ignored.</p>
     */
    private static int ground(BlockView view, int x, int z, int top, int bottom) {
        int h = Terrain.surface(view, x, z, top, bottom);
        return h == Terrain.NONE && view.solid(x, top, z) ? top : h;
    }

    private static void smooth(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = s.size();
        int reach = smoothReach(r);
        int n = 2 * r + 1;
        int[][] original = new int[n][n];
        double[][] heights = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                original[i][j] = ground(view, x - r + i, z - r + j, y + reach, y - reach);
                heights[i][j] = original[i][j];
            }
        }
        for (int pass = 0; pass < s.iterations(); pass++) {
            heights = RegionOps.blur(heights, original);
        }
        double limit = r + 0.5;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int h = original[i][j];
                double d = Math.sqrt((i - r) * (i - r) + (j - r) * (j - r));
                if (h == Terrain.NONE || d > limit) {
                    continue;
                }
                int cx = x - r + i;
                int cz = z - r + j;
                if (!stroke.allowed(cx, h, cz)) {
                    continue;
                }
                double t = d / limit;
                double weight = 1 - s.falloff() / 100.0 * t * t;
                int target = (int) Math.round(h + (heights[i][j] - h) * weight);
                Terrain.reshape(stroke.changes, view, cx, cz, h, target, null, stroke.context.air());
            }
        }
    }

    /** Raise and lower: goBrush, without a height map but with its bump shapes. */
    private static void relief(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = s.size();
        double limit = r + 0.5;
        Pattern fill = s.type() == BrushType.RAISE && !s.pattern().isBlank()
                ? stroke.pattern.within(y - r, y + r + s.intensity()) : null;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > limit) {
                    continue;
                }
                int cx = x + dx;
                int cz = z + dz;
                int h = Terrain.surface(view, cx, cz, y + r + 2 * s.intensity() + 8, y - r - 2 * s.intensity() - 8);
                if (h == Terrain.NONE || !stroke.allowed(cx, h, cz)) {
                    continue;
                }
                int delta = (int) Math.round(s.intensity() * profile(s.profile(), d / limit, cx, cz));
                if (delta == 0) {
                    continue;
                }
                int target = s.type() == BrushType.RAISE ? h + delta : h - delta;
                Terrain.reshape(stroke.changes, view, cx, cz, h, target, fill, stroke.context.air());
            }
        }
    }

    /**
     * Flatten: each column of the disc is brought to the height of the aimed block.
     *
     * <p>The ground is searched far: a column whose surface exceeds the read area (a pillar) is
     * cut from the top of the area, and a column without ground (a pit deeper than the search)
     * gets a four layer crust, made of the most common top and bottom blocks around. Before,
     * both cases were skipped: those were the holes.</p>
     */
    private static void flatten(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = s.size();
        double limit = r + 0.5;
        int top = y + r + FLATTEN_UP;
        int bottom = y - FLATTEN_DOWN;
        String mode = s.profile();
        int n = 2 * r + 1;
        int[][] heights = new int[n][n];
        Map<BlockData, Integer> tops = new HashMap<>();
        Map<BlockData, Integer> unders = new HashMap<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int cx = x - r + i;
                int cz = z - r + j;
                int h = ground(view, cx, cz, top, bottom);
                heights[i][j] = h;
                if (h != Terrain.NONE) {
                    tops.merge(view.get(cx, h, cz), 1, Integer::sum);
                    if (view.solid(cx, h - 1, cz)) {
                        unders.merge(view.get(cx, h - 1, cz), 1, Integer::sum);
                    }
                }
            }
        }
        BlockData topSample = mostCommon(tops);
        BlockData underSample = unders.isEmpty() ? topSample : mostCommon(unders);

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double d = Math.sqrt((i - r) * (i - r) + (j - r) * (j - r));
                if (d > limit || !stroke.keep(d, r)) {
                    continue;
                }
                int cx = x - r + i;
                int cz = z - r + j;
                int h = heights[i][j];
                if (h == Terrain.NONE) {
                    if (mode.equals("cut") || topSample == null || !stroke.allowed(cx, y, cz)) {
                        continue;
                    }
                    for (int cy = y - 3; cy < y; cy++) {
                        stroke.changes.set(cx, cy, cz, underSample);
                    }
                    stroke.changes.set(cx, y, cz, topSample);
                    continue;
                }
                if (!stroke.allowed(cx, h, cz)
                        || (mode.equals("fill") && h >= y) || (mode.equals("cut") && h <= y)) {
                    continue;
                }
                Terrain.reshape(stroke.changes, view, cx, cz, h, y, null, stroke.context.air());
            }
        }
    }

    private static BlockData mostCommon(Map<BlockData, Integer> counts) {
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** Relative height of a goBrush bump, by its shape, at distance {@code t} from the center (0 to 1). */
    private static double profile(String shape, double t, int x, int z) {
        double dome = Math.max(0, 1 - t * t);
        return switch (shape) {
            case "cone" -> Math.max(0, 1 - t);
            case "plateau" -> t < 0.6 ? 1 : Math.max(0, smoothstep((1 - t) / 0.4));
            case "noise" -> dome * dome * (0.3 + 1.4 * Noise.fractal(x / 5.0, 0, z / 5.0));
            default -> dome * dome;
        };
    }

    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }

    /**
     * VoxelSniper's blob: a ball whose edge ripples with noise. The irregularity says how far the
     * edge may stray from the sphere.
     */
    private static void blob(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        Noise.Kind kind = Noise.Kind.parse(s.noise()) == null ? Noise.Kind.FRACTAL : Noise.Kind.parse(s.noise());
        double r = Math.max(0.5, s.size());
        double irregular = s.falloff() / 100.0;
        double scale = Math.max(1.5, r * 0.6);
        int reach = (int) Math.ceil(r * (1 + irregular)) + 1;
        Pattern within = stroke.pattern.within(y - reach, y + reach);
        for (int a = x - reach; a <= x + reach; a++) {
            for (int b = y - reach; b <= y + reach; b++) {
                for (int c = z - reach; c <= z + reach; c++) {
                    double d = distance(a - x, b - y, c - z) / (r + 0.5);
                    double edge = 1 + (Noise.sample(kind, a / scale, b / scale, c / scale) - 0.5) * 2 * irregular;
                    if (d <= edge && stroke.allowed(a, b, c)) {
                        stroke.changes.set(a, b, c, within.at(a, b, c));
                    }
                }
            }
        }
    }

    /**
     * goPaint's bucket: everything touching the aimed block with the same material, up to the
     * radius, takes the pattern. With surface only, only blocks open to the air count.
     */
    private static void bucket(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        Material material = view.type(x, y, z);
        if (material.isAir()) {
            return;
        }
        double limit = (s.size() + 0.5) * (s.size() + 0.5);
        Pattern within = stroke.pattern.within(y - s.size(), y + s.size());
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        long start = Keys.pack(x, y, z);
        seen.add(start);
        queue.enqueue(start);
        while (!queue.isEmpty()) {
            long key = queue.dequeueLong();
            int a = Keys.x(key);
            int b = Keys.y(key);
            int c = Keys.z(key);
            if (stroke.allowed(a, b, c)) {
                stroke.changes.set(a, b, c, within.at(a, b, c));
            }
            for (int[] f : FACES) {
                int na = a + f[0];
                int nb = b + f[1];
                int nc = c + f[2];
                long next = Keys.pack(na, nb, nc);
                if ((na - x) * (na - x) + (nb - y) * (nb - y) + (nc - z) * (nc - z) > limit || !seen.add(next)) {
                    continue;
                }
                if (view.type(na, nb, nc) == material && (!s.surface() || view.exposed(na, nb, nc))) {
                    queue.enqueue(next);
                }
            }
        }
    }

    /**
     * VoxelSniper's erosion: a block touching the void on enough faces falls, then a void
     * surrounded by enough blocks fills with the most common material around.
     *
     * <p>The void is anything not solid: air, but also water and plants. A bank therefore erodes
     * like a cliff.</p>
     */
    private static void erode(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        int[] preset = switch (s.preset()) {
            case "fill" -> new int[]{5, 1, 2, 1};
            case "smooth" -> new int[]{3, 1, 3, 1};
            case "lift" -> new int[]{6, 0, 1, 1};
            case "floatclean" -> new int[]{6, 1, 6, 1};
            default -> new int[]{2, 1, 5, 1};
        };
        int r = s.size();
        BlockView base = stroke.terrain;
        LongArrayList cells = new LongArrayList();
        Shapes.ellipsoid(x, y, z, r, r, r, false, (a, b, c) -> cells.add(Keys.pack(a, b, c)));
        Long2ObjectOpenHashMap<BlockData> work = new Long2ObjectOpenHashMap<>();
        BlockView current = Views.overlay(base, work);
        BlockData air = stroke.context.air();

        for (int pass = 0; pass < preset[1]; pass++) {
            LongArrayList removed = new LongArrayList();
            for (long key : cells) {
                int a = Keys.x(key);
                int b = Keys.y(key);
                int c = Keys.z(key);
                if (!current.solid(a, b, c)) {
                    continue;
                }
                int open = 0;
                for (int[] f : FACES) {
                    if (!current.solid(a + f[0], b + f[1], c + f[2])) {
                        open++;
                    }
                }
                if (open >= preset[0]) {
                    removed.add(key);
                }
            }
            for (long key : removed) {
                work.put(key, air);
            }
        }

        for (int pass = 0; pass < preset[3]; pass++) {
            Long2ObjectOpenHashMap<BlockData> filled = new Long2ObjectOpenHashMap<>();
            for (long key : cells) {
                int a = Keys.x(key);
                int b = Keys.y(key);
                int c = Keys.z(key);
                if (current.solid(a, b, c)) {
                    continue;
                }
                Map<Material, Integer> counts = new EnumMap<>(Material.class);
                Map<Material, BlockData> samples = new EnumMap<>(Material.class);
                int solid = 0;
                for (int[] f : FACES) {
                    BlockData near = current.get(a + f[0], b + f[1], c + f[2]);
                    if (near.getMaterial().isSolid()) {
                        solid++;
                        counts.merge(near.getMaterial(), 1, Integer::sum);
                        samples.putIfAbsent(near.getMaterial(), near);
                    }
                }
                if (solid >= preset[2]) {
                    Material best = counts.entrySet().stream().max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey).orElse(null);
                    if (best != null) {
                        filled.put(key, samples.get(best));
                    }
                }
            }
            work.putAll(filled);
        }

        for (Long2ObjectMap.Entry<BlockData> entry : work.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int a = Keys.x(key);
            int b = Keys.y(key);
            int c = Keys.z(key);
            if (!entry.getValue().equals(base.get(a, b, c)) && stroke.allowed(a, b, c)) {
                stroke.changes.set(key, entry.getValue());
            }
        }
    }

    /** VoxelSniper's blend: each block takes the majority material of its 26 neighbours. */
    private static void blend(Stroke stroke, int x, int y, int z) {
        BlockView view = stroke.terrain;
        int r = stroke.s.size();
        Shapes.ellipsoid(x, y, z, r, r, r, false, (a, b, c) -> {
            BlockData self = view.get(a, b, c);
            if (self.getMaterial().isAir() || !stroke.allowed(a, b, c)) {
                return;
            }
            Map<Material, Integer> counts = new EnumMap<>(Material.class);
            Map<Material, BlockData> samples = new EnumMap<>(Material.class);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockData near = view.get(a + dx, b + dy, c + dz);
                        if (!near.getMaterial().isAir()) {
                            counts.merge(near.getMaterial(), 1, Integer::sum);
                            samples.putIfAbsent(near.getMaterial(), near);
                        }
                    }
                }
            }
            Material best = null;
            int bestCount = 0;
            boolean tie = false;
            for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    best = entry.getKey();
                    bestCount = entry.getValue();
                    tie = false;
                } else if (entry.getValue() == bestCount) {
                    tie = true;
                }
            }
            if (best != null && !tie && best != self.getMaterial()) {
                stroke.changes.set(a, b, c, samples.get(best));
            }
        });
    }

    /** A disc (or ring) lying on the aimed face: the goPaint and VoxelSniper discs. */
    private static void disk(Stroke stroke, int x, int y, int z, BlockFace face) {
        BrushSettings s = stroke.s;
        int r = s.size();
        double outer = r + 0.5;
        double inner = s.type() == BrushType.RING ? Math.max(0, outer - s.height()) : -1;
        Pattern within = stroke.pattern.within(y - r, y + r);
        for (int a = -r; a <= r; a++) {
            for (int b = -r; b <= r; b++) {
                double d = Math.sqrt(a * a + b * b);
                if (d > outer || d < inner) {
                    continue;
                }
                int wx = face.getModX() != 0 ? x : x + a;
                int wy = face.getModY() != 0 ? y : face.getModX() != 0 ? y + a : y + b;
                int wz = face.getModZ() != 0 ? z : face.getModY() != 0 ? z + b : z + b;
                if (stroke.allowed(wx, wy, wz) && stroke.keep(d, r)) {
                    stroke.changes.set(wx, wy, wz, within.at(wx, wy, wz));
                }
            }
        }
    }

    /** Below the top layer, over {@code height} blocks: the goPaint and VoxelSniper underlay. */
    private static void underlay(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = s.size();
        double limit = r + 0.5;
        Pattern within = stroke.pattern.within(y - r - s.height(), y + r);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > limit) {
                    continue;
                }
                int cx = x + dx;
                int cz = z + dz;
                int top = Terrain.surface(view, cx, cz, y + r + 2, y - r - 2);
                if (top == Terrain.NONE || !stroke.keep(d, r)) {
                    continue;
                }
                for (int i = 1; i <= s.height(); i++) {
                    int cy = top - i;
                    if (view.solid(cx, cy, cz) && stroke.allowed(cx, cy, cz)) {
                        stroke.changes.set(cx, cy, cz, within.at(cx, cy, cz));
                    }
                }
            }
        }
    }

    /** Edges and corners: solid blocks touching a lot of air around them. */
    private static void fracture(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = s.size();
        Pattern within = stroke.pattern.within(y - r, y + r);
        Shapes.ellipsoid(x, y, z, r, r, r, false, (a, b, c) -> {
            if (!view.solid(a, b, c) || !view.exposed(a, b, c) || !stroke.allowed(a, b, c)) {
                return;
            }
            int air = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (!view.solid(a + dx, b + dy, c + dz)) {
                            air++;
                        }
                    }
                }
            }
            if (air >= 12 && stroke.keep(distance(a - x, b - y, c - z), r)) {
                stroke.changes.set(a, b, c, within.at(a, b, c));
            }
        });
    }

    // ---------------------------------------------------------------- details

    /**
     * Ivy: strands start from walls and ceilings and hang down. A vine or lichen clings to the
     * solid faces touching it; lower down, it keeps those of the strand above, like a hanging
     * vine.
     */
    private static void ivy(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = s.size();
        Pattern within = stroke.pattern.within(y - r - s.height(), y + r);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Shapes.ellipsoid(x, y, z, r, r, r, false, (a, b, c) -> {
            if (!view.air(a, b, c) || stroke.changes.get(Keys.pack(a, b, c)) != null) {
                return;
            }
            boolean ceiling = view.solid(a, b + 1, c);
            boolean wall = view.solid(a + 1, b, c) || view.solid(a - 1, b, c) || view.solid(a, b, c + 1)
                    || view.solid(a, b, c - 1);
            if (!(ceiling || wall) || !stroke.allowed(a, b, c) || !stroke.keep(distance(a - x, b - y, c - z), r)) {
                return;
            }
            int length = 1 + random.nextInt(Math.max(1, s.height()));
            BlockData above = null;
            for (int i = 0; i < length; i++) {
                int cy = b - i;
                if (!view.air(a, cy, c) || stroke.changes.get(Keys.pack(a, cy, c)) != null) {
                    break;
                }
                BlockData data = cling(within.at(a, cy, c), view, a, cy, c, above);
                if (data == null) {
                    break;
                }
                stroke.changes.set(a, cy, c, data);
                above = data;
            }
        });
    }

    /** Orients a vine or lichen towards neighbouring solid faces; {@code null} if it holds onto nothing. */
    private static BlockData cling(BlockData data, BlockView view, int x, int y, int z, BlockData above) {
        if (!(data instanceof org.bukkit.block.data.MultipleFacing facing)) {
            return data;
        }
        org.bukkit.block.data.MultipleFacing out = (org.bukkit.block.data.MultipleFacing) facing.clone();
        boolean any = false;
        for (BlockFace face : out.getAllowedFaces()) {
            boolean solid = view.solid(x + face.getModX(), y + face.getModY(), z + face.getModZ());
            out.setFace(face, solid);
            any |= solid;
        }
        if (!any && above instanceof org.bukkit.block.data.MultipleFacing hanging) {
            for (BlockFace face : out.getAllowedFaces()) {
                if (face != BlockFace.UP && face != BlockFace.DOWN && hanging.hasFace(face)) {
                    out.setFace(face, true);
                    any = true;
                }
            }
        }
        return any ? out : null;
    }

    /**
     * Stalactites and stalagmites: a column hangs under each randomly drawn ceiling, more rarely
     * another grows from the ground. In pointed dripstone, the thickness follows the real game:
     * base, middle, frustum, tip.
     */
    private static void stalactites(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = s.size();
        Pattern within = stroke.pattern.within(y - r - s.height(), y + r + s.height());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Shapes.ellipsoid(x, y, z, r, r, r, false, (a, b, c) -> {
            if (!view.solid(a, b, c) || !stroke.allowed(a, b, c)) {
                return;
            }
            if (view.air(a, b - 1, c) && stroke.keep(distance(a - x, b - y, c - z), r)) {
                hang(stroke, view, within, a, b - 1, c, -1, 1 + random.nextInt(Math.max(1, s.height())));
            } else if (view.air(a, b + 1, c) && random.nextInt(3) == 0 && stroke.keep(distance(a - x, b - y, c - z), r)) {
                hang(stroke, view, within, a, b + 1, c, 1, 1 + random.nextInt(Math.max(1, (s.height() + 1) / 2)));
            }
        });
    }

    private static void hang(Stroke stroke, BlockView view, Pattern within, int x, int y, int z, int dir, int length) {
        int n = 0;
        while (n < length && view.air(x, y + dir * n, z) && view.air(x, y + dir * (n + 1), z)
                && stroke.changes.get(Keys.pack(x, y + dir * n, z)) == null) {
            n++;
        }
        for (int i = 0; i < n; i++) {
            int cy = y + dir * i;
            BlockData data = within.at(x, cy, z);
            if (data instanceof org.bukkit.block.data.type.PointedDripstone drip) {
                org.bukkit.block.data.type.PointedDripstone out = (org.bukkit.block.data.type.PointedDripstone) drip.clone();
                out.setVerticalDirection(dir < 0 ? BlockFace.DOWN : BlockFace.UP);
                out.setThickness(thickness(i, n));
                data = out;
            }
            if (data != null) {
                stroke.changes.set(x, cy, z, data);
            }
        }
    }

    private static org.bukkit.block.data.type.PointedDripstone.Thickness thickness(int i, int n) {
        int fromTip = n - 1 - i;
        if (fromTip == 0) {
            return org.bukkit.block.data.type.PointedDripstone.Thickness.TIP;
        }
        if (fromTip == 1) {
            return org.bukkit.block.data.type.PointedDripstone.Thickness.FRUSTUM;
        }
        return i == 0 ? org.bukkit.block.data.type.PointedDripstone.Thickness.BASE
                : org.bukkit.block.data.type.PointedDripstone.Thickness.MIDDLE;
    }

    /**
     * Cracks: random walks over the skin of volumes, turning a little at each step and carving
     * (or repainting) to the chosen depth, inwards.
     */
    private static void cracks(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = Math.max(1, s.size());
        Pattern within = stroke.pattern.within(y - r - s.height(), y + r + s.height());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int crack = 0; crack < s.iterations(); crack++) {
            int[] cell = null;
            for (int attempt = 0; attempt < 60 && cell == null; attempt++) {
                int a = x + random.nextInt(-r, r + 1);
                int b = y + random.nextInt(-r, r + 1);
                int c = z + random.nextInt(-r, r + 1);
                if (view.solid(a, b, c) && view.exposed(a, b, c)) {
                    cell = new int[]{a, b, c};
                }
            }
            if (cell == null) {
                continue;
            }
            double[] dir = {random.nextGaussian(), random.nextGaussian(), random.nextGaussian()};
            double[] at = {cell[0] + 0.5, cell[1] + 0.5, cell[2] + 0.5};
            int length = 2 * r + random.nextInt(r + 1);
            for (int step = 0; step < length && cell != null; step++) {
                int[] normal = openFace(view, cell[0], cell[1], cell[2]);
                if (normal == null) {
                    break;
                }
                for (int d = 0; d < Math.max(1, s.height()); d++) {
                    int a = cell[0] - normal[0] * d;
                    int b = cell[1] - normal[1] * d;
                    int c = cell[2] - normal[2] * d;
                    if (view.solid(a, b, c) && stroke.allowed(a, b, c)) {
                        stroke.changes.set(a, b, c, within.at(a, b, c));
                    }
                }
                double along = dir[0] * normal[0] + dir[1] * normal[1] + dir[2] * normal[2];
                for (int k = 0; k < 3; k++) {
                    dir[k] = dir[k] - along * normal[k] + random.nextGaussian() * 0.35;
                }
                double length2 = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
                if (length2 < 1.0e-6) {
                    break;
                }
                for (int k = 0; k < 3; k++) {
                    dir[k] /= length2;
                    at[k] += dir[k];
                }
                cell = surfaceNear(view, (int) Math.floor(at[0]), (int) Math.floor(at[1]), (int) Math.floor(at[2]));
            }
        }
    }

    private static int[] openFace(BlockView view, int x, int y, int z) {
        for (int[] f : FACES) {
            if (view.air(x + f[0], y + f[1], z + f[2])) {
                return f;
            }
        }
        return null;
    }

    /** The skin block closest to this point, within one block. */
    private static int[] surfaceNear(BlockView view, int x, int y, int z) {
        if (view.solid(x, y, z) && view.exposed(x, y, z)) {
            return new int[]{x, y, z};
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (view.solid(x + dx, y + dy, z + dz) && view.exposed(x + dx, y + dy, z + dz)) {
                        return new int[]{x + dx, y + dy, z + dz};
                    }
                }
            }
        }
        return null;
    }

    /**
     * Scree: each stone falls randomly within the radius, rolls towards the lowest neighbouring
     * column as long as there is one, and settles. Piles grow, the next stones roll further:
     * stones gather at the foot of slopes, like a real talus.
     */
    private static void scree(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = Math.max(1, s.size());
        int top = y + r + 2;
        int bottom = y - 2 * r - 10;
        Pattern within = stroke.pattern.within(bottom, top);
        Map<Long, Integer> heights = new HashMap<>();
        java.util.function.LongUnaryOperator height = key -> heights.computeIfAbsent(key,
                k -> Terrain.surface(view, Keys.x(k), Keys.z(k), top, bottom));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int count = Math.max(4, r * r * Math.max(1, s.intensity()) / 2);
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int n = 0; n < count; n++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = Math.sqrt(random.nextDouble()) * r;
            int cx = x + (int) Math.round(Math.cos(angle) * radius);
            int cz = z + (int) Math.round(Math.sin(angle) * radius);
            long h = height.applyAsLong(Keys.pack(cx, 0, cz));
            if (h == Terrain.NONE) {
                continue;
            }
            for (int step = 0; step < 3 * r; step++) {
                int bestX = cx;
                int bestZ = cz;
                long best = h;
                int offset = random.nextInt(around.length);
                for (int i = 0; i < around.length; i++) {
                    int[] d = around[(i + offset) % around.length];
                    int nx = cx + d[0];
                    int nz = cz + d[1];
                    if ((nx - x) * (nx - x) + (nz - z) * (nz - z) > 4 * r * r) {
                        continue;
                    }
                    long nh = height.applyAsLong(Keys.pack(nx, 0, nz));
                    if (nh != Terrain.NONE && nh <= h - 1 && nh < best) {
                        best = nh;
                        bestX = nx;
                        bestZ = nz;
                    }
                }
                if (bestX == cx && bestZ == cz) {
                    break;
                }
                cx = bestX;
                cz = bestZ;
                h = best;
            }
            int cy = (int) h + 1;
            if (view.solid(cx, cy, cz) || !stroke.allowed(cx, cy, cz)) {
                continue;
            }
            BlockData data = within.at(cx, cy, cz);
            if (data != null) {
                stroke.changes.set(cx, cy, cz, data);
                heights.put(Keys.pack(cx, 0, cz), cy);
            }
        }
    }

    /** Drain: water and lava leave, waterlogged blocks give it back. */
    private static void drain(Stroke stroke, int x, int y, int z) {
        int r = stroke.s.size();
        BlockData air = stroke.context.air();
        Shapes.ellipsoid(x, y, z, r, r, r, false, (a, b, c) -> {
            BlockData data = stroke.real.get(a, b, c);
            Material type = data.getMaterial();
            if (type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN
                    || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS || type == Material.KELP
                    || type == Material.KELP_PLANT) {
                stroke.changes.set(a, b, c, air);
            } else if (data instanceof org.bukkit.block.data.Waterlogged logged && logged.isWaterlogged()) {
                org.bukkit.block.data.Waterlogged dry = (org.bukkit.block.data.Waterlogged) data.clone();
                dry.setWaterlogged(false);
                stroke.changes.set(a, b, c, dry);
            }
        });
    }

    /** Fill down: in each column, the void under the first solid block fills up. */
    private static void fillDown(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = s.size();
        double limit = (r + 0.5) * (r + 0.5);
        Pattern within = stroke.pattern.within(y - r, y + r);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > limit) {
                    continue;
                }
                boolean under = false;
                for (int cy = y + r; cy >= y - r; cy--) {
                    int cx = x + dx;
                    int cz = z + dz;
                    if (view.solid(cx, cy, cz)) {
                        under = true;
                    } else if (under && stroke.allowed(cx, cy, cz)) {
                        stroke.changes.set(cx, cy, cz, within.at(cx, cy, cz));
                    }
                }
            }
        }
    }

    /** Hollow: solid blocks surrounded by solid everywhere take the pattern (air by default). */
    private static void shell(Stroke stroke, int x, int y, int z) {
        BlockView view = stroke.terrain;
        int r = stroke.s.size();
        Pattern within = stroke.pattern.within(y - r, y + r);
        Shapes.ellipsoid(x, y, z, r, r, r, false, (a, b, c) -> {
            if (view.solid(a, b, c) && view.solid(a + 1, b, c) && view.solid(a - 1, b, c) && view.solid(a, b + 1, c)
                    && view.solid(a, b - 1, c) && view.solid(a, b, c + 1) && view.solid(a, b, c - 1)
                    && stroke.allowed(a, b, c)) {
                stroke.changes.set(a, b, c, within.at(a, b, c));
            }
        });
    }

    /** A snow pile: higher in the center, in snow layers down to the last eighth. */
    private static void snowcone(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        BlockView view = stroke.terrain;
        int r = Math.max(1, s.size());
        BlockData block = Material.SNOW_BLOCK.createBlockData();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > r + 0.5) {
                    continue;
                }
                int cx = x + dx;
                int cz = z + dz;
                int top = Terrain.surface(view, cx, cz, y + r + 2, y - r - 2);
                if (top == Terrain.NONE) {
                    continue;
                }
                int layers = (int) Math.round(s.height() * 8 * (1 - d / (r + 0.5)));
                for (int i = 0; layers > 0; i++) {
                    int cy = top + 1 + i;
                    if (!view.air(cx, cy, cz)) {
                        break;
                    }
                    if (layers >= 8) {
                        stroke.changes.set(cx, cy, cz, block);
                        layers -= 8;
                    } else {
                        org.bukkit.block.data.type.Snow snow = (org.bukkit.block.data.type.Snow) Material.SNOW.createBlockData();
                        snow.setLayers(Math.max(snow.getMinimumLayers(), Math.min(snow.getMaximumLayers(), layers)));
                        stroke.changes.set(cx, cy, cz, snow);
                        layers = 0;
                    }
                }
            }
        }
    }

    /**
     * Arceon's rock: an icosahedron, possibly subdivided, each vertex of which strays randomly
     * from the center; the flat faces give the cut look.
     */
    private static void boulder(Stroke stroke, int x, int y, int z) {
        BrushSettings s = stroke.s;
        double r = Math.max(1, s.size());
        double irregular = s.falloff() / 100.0;
        java.util.List<double[]> faces = Polyhedra.boulder(Math.max(0, Math.min(3, s.iterations() - 1)), irregular);
        int reach = (int) Math.ceil(r * (1 + irregular)) + 1;
        Pattern within = stroke.pattern.within(y - reach, y + reach);
        for (int a = -reach; a <= reach; a++) {
            for (int b = -reach; b <= reach; b++) {
                for (int c = -reach; c <= reach; c++) {
                    double px = a / (r + 0.5);
                    double py = b / (r + 0.5);
                    double pz = c / (r + 0.5);
                    if (Polyhedra.inside(faces, px, py, pz) && stroke.allowed(x + a, y + b, z + c)) {
                        stroke.changes.set(x + a, y + b, z + c, within.at(x + a, y + b, z + c));
                    }
                }
            }
        }
    }

    /** Extends the blocks of the aimed face's plane outwards, as long as there is air. */
    private static void extrude(Stroke stroke, int x, int y, int z, BlockFace face) {
        BrushSettings s = stroke.s;
        int r = s.size();
        int fx = face.getModX();
        int fy = face.getModY();
        int fz = face.getModZ();
        if (fx == 0 && fy == 0 && fz == 0) {
            fy = 1;
        }
        for (int a = -r; a <= r; a++) {
            for (int b = -r; b <= r; b++) {
                if (a * a + b * b > (r + 0.5) * (r + 0.5)) {
                    continue;
                }
                int wx = fx != 0 ? x : x + a;
                int wy = fy != 0 ? y : fx != 0 ? y + a : y + b;
                int wz = fz != 0 ? z : z + b;
                BlockData source = stroke.real.get(wx, wy, wz);
                if (source.getMaterial().isAir() || !stroke.allowed(wx, wy, wz)) {
                    continue;
                }
                for (int i = 1; i <= s.height(); i++) {
                    int ex = wx + fx * i;
                    int ey = wy + fy * i;
                    int ez = wz + fz * i;
                    if (!stroke.real.air(ex, ey, ez)) {
                        break;
                    }
                    stroke.changes.set(ex, ey, ez, source);
                }
            }
        }
    }

    /** An Arceon spike: a cone that thins while waving, from the aimed face. */
    private static void spike(Stroke stroke, int x, int y, int z, BlockFace face) {
        BrushSettings s = stroke.s;
        int ax = face.getModX();
        int ay = face.getModY();
        int az = face.getModZ();
        if (ax == 0 && ay == 0 && az == 0) {
            ay = 1;
        }
        int length = s.height();
        double radius = Math.max(0.5, s.size());
        Pattern within = stroke.pattern.within(Math.min(y, y + ay * length), Math.max(y, y + ay * length));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double wobbleA = 0;
        double wobbleB = 0;
        for (int i = 0; i < length; i++) {
            double t = (double) i / length;
            double width = radius * Math.pow(1 - t, 1.3) * (0.85 + 0.3 * random.nextDouble());
            wobbleA += (random.nextDouble() - 0.5) * 0.35;
            wobbleB += (random.nextDouble() - 0.5) * 0.35;
            double cx = x + 0.5 + ax * (i + 1) + (ax == 0 ? wobbleA : 0);
            double cy = y + 0.5 + ay * (i + 1) + (ay == 0 ? (ax == 0 ? wobbleB : wobbleA) : 0);
            double cz = z + 0.5 + az * (i + 1) + (az == 0 ? wobbleB : 0);
            Shapes.disk(cx, cy, cz, width, ax, ay, az, (a, b, c) -> {
                if (stroke.allowed(a, b, c)) {
                    stroke.changes.set(a, b, c, within.at(a, b, c));
                }
            });
        }
    }
}
