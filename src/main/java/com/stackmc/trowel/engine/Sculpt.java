package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.geom.NoiseSpec;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.EnumMap;
import java.util.Map;

/**
 * Sculpt whole volumes, like the ezEdits surface and deformation tools.
 *
 * <p>Everything starts from a grid of the selection (plus a margin): solid or empty, and the
 * material of each block, read from the relief view (markers are air there). A block that
 * appears takes the material of the closest solid block: an inflated rock stays stone, a
 * smoothed grass stays grass.</p>
 */
public final class Sculpt {

    private Sculpt() {
    }

    private static final int FAR = Integer.MAX_VALUE;
    static final long MAX_CELLS = 8_000_000L;

    /** One pass of the distance transform along a line of the grid. */
    private static final class Line {
        final int[] dist;
        final int[] seed;
        final int[] hull;
        final double[] from;

        Line(int length) {
            dist = new int[length];
            seed = new int[length];
            hull = new int[length];
            from = new double[length + 1];
        }

        /** The lower envelope of the parabolas of the cells already reached, then the closest to each. */
        void pass(int[] field, int[] seeds, int start, int stride, int length) {
            int k = -1;
            for (int q = 0; q < length; q++) {
                int i = start + q * stride;
                dist[q] = field[i];
                seed[q] = seeds[i];
                if (dist[q] == FAR) {
                    continue;
                }
                if (k < 0) {
                    k = 0;
                    hull[0] = q;
                    from[0] = Double.NEGATIVE_INFINITY;
                    from[1] = Double.POSITIVE_INFINITY;
                    continue;
                }
                double s;
                while (true) {
                    int p = hull[k];
                    s = ((dist[q] + (double) q * q) - (dist[p] + (double) p * p)) / (2.0 * (q - p));
                    if (s > from[k]) {
                        break;
                    }
                    k--;
                }
                k++;
                hull[k] = q;
                from[k] = s;
                from[k + 1] = Double.POSITIVE_INFINITY;
            }
            if (k < 0) {
                return;
            }
            int j = 0;
            for (int q = 0; q < length; q++) {
                while (from[j + 1] < q) {
                    j++;
                }
                int p = hull[j];
                long value = (long) (q - p) * (q - p) + dist[p];
                int i = start + q * stride;
                field[i] = (int) Math.min(value, FAR - 1L);
                seeds[i] = seed[p];
            }
        }
    }

    /** The selection frozen as solid and empty, with its materials. */
    static final class Grid {
        final Box box;
        final int w;
        final int h;
        final int d;
        final boolean[] solid;
        final BlockData[] data;

        Grid(EditContext context, Box box) {
            this(box, new boolean[cells(box)], new BlockData[cells(box)]);
            BlockView view = context.terrain();
            context.progress().phase("reading", w);
            for (int x = 0; x < w; x++) {
                context.progress().tick(1);
                for (int y = 0; y < h; y++) {
                    for (int z = 0; z < d; z++) {
                        int i = x + w * (y + h * z);
                        BlockData block = view.get(box.minX() + x, box.minY() + y, box.minZ() + z);
                        data[i] = block;
                        solid[i] = block != null && block.getMaterial().isSolid();
                    }
                }
            }
        }

        /** An already filled grid. */
        Grid(Box box, boolean[] solid, BlockData[] data) {
            this.box = box;
            w = box.width();
            h = box.height();
            d = box.depth();
            this.solid = solid;
            this.data = data;
        }

        private static int cells(Box box) {
            long volume = (long) box.width() * box.height() * box.depth();
            if (volume > MAX_CELLS) {
                throw new IllegalArgumentException("Area too large to sculpt: " + volume
                        + " blocks with the margin, the maximum is " + MAX_CELLS + ". Shrink the selection or the radius.");
            }
            return (int) volume;
        }

        int index(int x, int y, int z) {
            return (x - box.minX()) + w * ((y - box.minY()) + h * (z - box.minZ()));
        }

        boolean contains(int x, int y, int z) {
            return box.contains(x, y, z);
        }

        int x(int i) {
            return box.minX() + i % w;
        }

        int y(int i) {
            return box.minY() + (i / w) % h;
        }

        int z(int i) {
            return box.minZ() + i / (w * h);
        }

        /**
         * For each cell, the closest source cell (solid if {@code toSolid}, empty otherwise),
         * within {@code max} blocks; -1 beyond. Exact euclidean distance transform, one axis
         * after the other (Felzenszwalb): three linear passes, and fixed memory.
         */
        int[] nearest(boolean toSolid, double max, Progress progress) {
            int n = solid.length;
            int[] seed = new int[n];
            int[] dist = new int[n];
            progress.phase("distances", 3);
            for (int z = 0; z < d; z++) {
                progress.check();
                for (int y = 0; y < h; y++) {
                    int base = w * (y + h * z);
                    int last = -1;
                    for (int x = 0; x < w; x++) {
                        int i = base + x;
                        if (solid[i] == toSolid) {
                            last = x;
                        }
                        dist[i] = last < 0 ? FAR : (x - last) * (x - last);
                        seed[i] = last < 0 ? -1 : base + last;
                    }
                    last = -1;
                    for (int x = w - 1; x >= 0; x--) {
                        int i = base + x;
                        if (solid[i] == toSolid) {
                            last = x;
                        }
                        if (last >= 0 && (last - x) * (last - x) < dist[i]) {
                            dist[i] = (last - x) * (last - x);
                            seed[i] = base + last;
                        }
                    }
                }
            }
            progress.tick(1);
            Line line = new Line(Math.max(w, Math.max(h, d)));
            for (int z = 0; z < d; z++) {
                progress.check();
                for (int x = 0; x < w; x++) {
                    line.pass(dist, seed, x + w * h * z, w, h);
                }
            }
            progress.tick(1);
            for (int y = 0; y < h; y++) {
                progress.check();
                for (int x = 0; x < w; x++) {
                    line.pass(dist, seed, x + w * y, w * h, d);
                }
            }
            progress.tick(1);
            double limit = max * max;
            for (int i = 0; i < n; i++) {
                if (seed[i] >= 0 && dist[i] > limit) {
                    seed[i] = -1;
                }
            }
            return seed;
        }

        double distance(int i, int seed) {
            if (seed < 0) {
                return Double.MAX_VALUE;
            }
            double dx = x(i) - x(seed);
            double dy = y(i) - y(seed);
            double dz = z(i) - z(seed);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** Places the result: only what changes, and only inside the selection. */
    private static ChangeSet apply(EditContext context, Grid grid, Box region, boolean[] after, BlockData[] fill) {
        ChangeSet changes = context.changes();
        for (int i = 0; i < after.length; i++) {
            int x = grid.x(i);
            int y = grid.y(i);
            int z = grid.z(i);
            if (!region.contains(x, y, z) || after[i] == grid.solid[i] && (fill == null || fill[i] == null)) {
                continue;
            }
            if (!after[i]) {
                changes.set(x, y, z, context.air());
            } else if (!grid.solid[i] || fill != null && fill[i] != null) {
                BlockData data = fill == null ? null : fill[i];
                if (data != null) {
                    changes.set(x, y, z, data);
                }
            }
        }
        return changes;
    }

    /** The material of the cells that become solid: that of the closest solid block. */
    private static BlockData[] materials(Grid grid, boolean[] after, int[] nearestSolid) {
        BlockData[] out = new BlockData[after.length];
        for (int i = 0; i < after.length; i++) {
            if (after[i] && !grid.solid[i]) {
                int s = nearestSolid[i];
                out[i] = s < 0 ? null : grid.data[s];
            }
        }
        return out;
    }

    // ------------------------------------------------------------- smoothing

    /**
     * Smoothing in three dimensions (ezsmooth): a blur of material presence, then a threshold.
     *
     * @param bias -1 to 1: positive, the volume swells while smoothing; negative, it thins
     */
    public static Engine.Compute smooth(Box region, int radius, int iterations, double bias) {
        return context -> {
            Box box = region.grow(radius + 1);
            Grid grid = new Grid(context, box);
            int n = grid.solid.length;
            float[] field = new float[n];
            for (int i = 0; i < n; i++) {
                field[i] = grid.solid[i] ? 1 : 0;
            }
            for (int pass = 0; pass < iterations; pass++) {
                context.progress().phase("smoothing " + (pass + 1) + "/" + iterations, 3);
                for (int axis = 0; axis < 3; axis++) {
                    context.progress().tick(1);
                    field = blur(field, grid, axis, radius);
                }
            }
            double threshold = 0.5 - bias * 0.45;
            boolean[] after = new boolean[n];
            for (int i = 0; i < n; i++) {
                after[i] = field[i] > threshold;
            }
            int[] nearest = grid.nearest(true, radius + 2, context.progress());
            return apply(context, grid, region, after, materials(grid, after, nearest));
        };
    }

    private static float[] blur(float[] field, Grid g, int axis, int radius) {
        float[] out = new float[field.length];
        int[] size = {g.w, g.h, g.d};
        int len = size[axis];
        int stride = axis == 0 ? 1 : axis == 1 ? g.w : g.w * g.h;
        for (int i = 0; i < field.length; i++) {
            int coord = axis == 0 ? i % g.w : axis == 1 ? (i / g.w) % g.h : i / (g.w * g.h);
            float sum = 0;
            int count = 0;
            for (int k = -radius; k <= radius; k++) {
                int c = coord + k;
                if (c < 0 || c >= len) {
                    continue;
                }
                sum += field[i + k * stride];
                count++;
            }
            out[i] = sum / count;
        }
        return out;
    }

    /** Inflates ({@code radius > 0}) or deflates a volume by that many blocks. */
    public static Engine.Compute inflate(Box region, double radius) {
        return context -> {
            double r = Math.abs(radius);
            Box box = region.grow((int) Math.ceil(r) + 1);
            Grid grid = new Grid(context, box);
            int n = grid.solid.length;
            boolean[] after = grid.solid.clone();
            if (radius > 0) {
                int[] nearest = grid.nearest(true, r, context.progress());
                for (int i = 0; i < n; i++) {
                    if (!grid.solid[i] && nearest[i] >= 0 && grid.distance(i, nearest[i]) <= r) {
                        after[i] = true;
                    }
                }
                return apply(context, grid, region, after, materials(grid, after, nearest));
            }
            int[] nearestAir = grid.nearest(false, r, context.progress());
            for (int i = 0; i < n; i++) {
                if (grid.solid[i] && nearestAir[i] >= 0 && grid.distance(i, nearestAir[i]) <= r) {
                    after[i] = false;
                }
            }
            return apply(context, grid, region, after, null);
        };
    }

    // ------------------------------------------------------------- surface

    public enum Surface { FUZZIFY, ROCKIFY, VORONOIFY, NOISIFY }

    /**
     * Deforms the surface of a volume over a depth of {@code radius} blocks.
     *
     * @param noise the {@code noisify} noise, or {@code null}
     * @param size  the size of the rocks or cells
     * @param mode  0: dig and bulge; 1: dig only; 2: bulge only
     */
    public static Engine.Compute surface(Box region, Surface kind, double radius, double size, int octaves,
                                         NoiseSpec noise, int mode) {
        return context -> {
            int margin = (int) Math.ceil(radius) + 1;
            Box box = region.grow(margin);
            Grid grid = new Grid(context, box);
            int n = grid.solid.length;
            int[] nearestAir = grid.nearest(false, radius + 1, context.progress());
            int[] nearestSolid = grid.nearest(true, radius + 1, context.progress());
            NoiseSpec field = switch (kind) {
                case ROCKIFY -> NoiseSpec.parse("perlin(fo:" + Math.max(1, octaves) + ")", 1 / Math.max(1, size));
                case VORONOIFY -> NoiseSpec.parse("cellular(cr:2sub)", 1 / Math.max(1, size));
                case NOISIFY -> noise;
                case FUZZIFY -> null;
            };
            boolean[] after = grid.solid.clone();
            context.progress().phase("surface", n);
            java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
            for (int i = 0; i < n; i++) {
                context.progress().tick();
                double signed;
                if (grid.solid[i]) {
                    signed = nearestAir[i] < 0 ? radius + 1 : grid.distance(i, nearestAir[i]) - 0.5;
                } else {
                    signed = nearestSolid[i] < 0 ? -(radius + 1) : -(grid.distance(i, nearestSolid[i]) - 0.5);
                }
                if (Math.abs(signed) > radius + 0.5) {
                    continue;
                }
                double v = field == null ? random.nextDouble() : field.sample(grid.x(i), grid.y(i), grid.z(i));
                double offset = (v * 2 - 1) * radius;
                boolean solid = signed + offset > 0;
                if (mode == 1 && solid && !grid.solid[i]) {
                    continue;
                }
                if (mode == 2 && !solid && grid.solid[i]) {
                    continue;
                }
                after[i] = solid;
            }
            return apply(context, grid, region, after, materials(grid, after, nearestSolid));
        };
    }

    // ------------------------------------------------------------ deformations

    /** Cuts the volume into Voronoi cells separated by cracks {@code gap} blocks wide. */
    public static Engine.Compute voronoialize(Box region, double size, double gap, long seed) {
        return context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.terrain();
            double s = Math.max(1, size);
            NoiseSpec cells = NoiseSpec.parse("cellular(cr:sub,s:" + seed + ")", 1 / s);
            context.progress().phase("cells", region.width());
            for (int x = region.minX(); x <= region.maxX(); x++) {
                context.progress().tick(1);
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    for (int z = region.minZ(); z <= region.maxZ(); z++) {
                        if (view.air(x, y, z)) {
                            continue;
                        }
                        double edge = cells.sample(x, y, z) * s;
                        if (edge < gap) {
                            changes.set(x, y, z, context.air());
                        }
                    }
                }
            }
            return changes;
        };
    }

    /**
     * Hexagonal columns (ezdeform hexagonalize): each column is brought to the average height
     * of the relief it covers, and a crack {@code gap} blocks wide separates it from its neighbours.
     */
    public static Engine.Compute hexagonalize(Box region, double size, double gap, double angle) {
        return context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.terrain();
            double s = Math.max(2, size);
            double cos = Math.cos(Math.toRadians(angle));
            double sin = Math.sin(Math.toRadians(angle));
            int w = region.width();
            int d = region.depth();
            int[] top = new int[w * d];
            long[] cell = new long[w * d];
            Map<Long, double[]> sums = new java.util.HashMap<>();
            context.progress().phase("columns", w);
            for (int x = 0; x < w; x++) {
                context.progress().tick(1);
                for (int z = 0; z < d; z++) {
                    int wx = region.minX() + x;
                    int wz = region.minZ() + z;
                    int h = Terrain.surface(view, wx, wz, region.maxY(), region.minY());
                    top[x + w * z] = h;
                    double rx = (wx * cos - wz * sin) / s;
                    double rz = (wx * sin + wz * cos) / s;
                    long hex = hexCell(rx, rz);
                    cell[x + w * z] = hex;
                    if (h != Terrain.NONE) {
                        sums.computeIfAbsent(hex, key -> new double[2]);
                        double[] sum = sums.get(hex);
                        sum[0] += h;
                        sum[1]++;
                    }
                }
            }
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    int h = top[x + w * z];
                    if (h == Terrain.NONE) {
                        continue;
                    }
                    int wx = region.minX() + x;
                    int wz = region.minZ() + z;
                    double[] sum = sums.get(cell[x + w * z]);
                    int target = (int) Math.round(sum[0] / sum[1]);
                    double rx = (wx * cos - wz * sin) / s;
                    double rz = (wx * sin + wz * cos) / s;
                    if (gap > 0 && hexEdge(rx, rz) * s < gap) {
                        target = Math.min(target, h) - 1;
                    }
                    target = Math.max(region.minY(), Math.min(region.maxY(), target));
                    Terrain.reshape(changes, view, wx, wz, h, target, null, context.air());
                }
            }
            return changes;
        };
    }

    /** Big blocks (ezdeform voxelize): each grid cell becomes solid or empty, of its majority material. */
    public static Engine.Compute voxelize(Box region, int sx, int sy, int sz, int gap, double distortion, long seed) {
        return context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.terrain();
            NoiseSpec jitter = NoiseSpec.parse("white(s:" + seed + ")", 1);
            int ox = Math.floorMod(region.minX(), sx);
            int oy = Math.floorMod(region.minY(), sy);
            int oz = Math.floorMod(region.minZ(), sz);
            context.progress().phase("cells", region.width() / sx + 1);
            for (int cx = region.minX() - ox; cx <= region.maxX(); cx += sx) {
                context.progress().tick(1);
                for (int cy = region.minY() - oy; cy <= region.maxY(); cy += sy) {
                    for (int cz = region.minZ() - oz; cz <= region.maxZ(); cz += sz) {
                        int dx = distortion > 0 ? (int) Math.round((jitter.sample(cx, cy, cz) - 0.5) * distortion * sx) : 0;
                        int dz = distortion > 0 ? (int) Math.round((jitter.sample(cz, cx, cy) - 0.5) * distortion * sz) : 0;
                        Map<Material, Integer> counts = new EnumMap<>(Material.class);
                        Map<Material, BlockData> samples = new EnumMap<>(Material.class);
                        int solid = 0;
                        int total = 0;
                        for (int x = cx; x < cx + sx; x++) {
                            for (int y = cy; y < cy + sy; y++) {
                                for (int z = cz; z < cz + sz; z++) {
                                    total++;
                                    BlockData data = view.get(x + dx, y, z + dz);
                                    if (data.getMaterial().isSolid()) {
                                        solid++;
                                        counts.merge(data.getMaterial(), 1, Integer::sum);
                                        samples.putIfAbsent(data.getMaterial(), data);
                                    }
                                }
                            }
                        }
                        BlockData fill = null;
                        if (solid * 2 >= total) {
                            Material best = counts.entrySet().stream().max(Map.Entry.comparingByValue())
                                    .map(Map.Entry::getKey).orElse(null);
                            fill = best == null ? null : samples.get(best);
                        }
                        for (int x = cx; x < cx + sx; x++) {
                            for (int y = cy; y < cy + sy; y++) {
                                for (int z = cz; z < cz + sz; z++) {
                                    if (!region.contains(x, y, z)) {
                                        continue;
                                    }
                                    boolean edge = gap > 0 && (x - cx >= sx - gap || y - cy >= sy - gap || z - cz >= sz - gap);
                                    BlockData want = fill == null || edge ? context.air() : fill;
                                    if (!want.equals(view.get(x, y, z))) {
                                        changes.set(x, y, z, want);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return changes;
        };
    }

    /**
     * Deforms with a noise field (ezdeform noise): each block takes the one of a point shifted
     * by the noise, up to {@code strength} blocks.
     *
     * @param axes 0: everywhere; 1: horizontally only; 2: vertically only
     */
    public static Engine.Compute noiseDeform(Box region, NoiseSpec noise, double strength, int axes) {
        return context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.view();
            context.progress().phase("deforming", region.width());
            for (int x = region.minX(); x <= region.maxX(); x++) {
                context.progress().tick(1);
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    for (int z = region.minZ(); z <= region.maxZ(); z++) {
                        double ox = axes == 2 ? 0 : (noise.sample(x, y, z) * 2 - 1) * strength;
                        double oy = axes == 1 ? 0 : (noise.sample(x + 311.7, y + 97.3, z + 53.9) * 2 - 1) * strength;
                        double oz = axes == 2 ? 0 : (noise.sample(x + 71.1, y + 213.3, z + 157.7) * 2 - 1) * strength;
                        int sx = (int) Math.round(x + ox);
                        int sy = (int) Math.round(y + oy);
                        int sz = (int) Math.round(z + oz);
                        if (sx != x || sy != y || sz != z) {
                            changes.set(x, y, z, view.get(sx, sy, sz));
                        }
                    }
                }
            }
            return changes;
        };
    }

    /**
     * Rotates, twists or tapers the selection around an axis through its center, fetching for
     * each block the one that comes to its place.
     *
     * @param angle  degrees of rotation (at the end for a twist)
     * @param twist  true: the rotation grows along the axis, from 0 to {@code angle}
     * @param taper  size factor at the end of the axis (1: none), tapering from the base
     */
    public static Engine.Compute rotate(Box region, char axis, double angle, boolean twist, double taper) {
        return context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.view();
            double cx = (region.minX() + region.maxX()) / 2.0;
            double cy = (region.minY() + region.maxY()) / 2.0;
            double cz = (region.minZ() + region.maxZ()) / 2.0;
            int[] size = {region.width(), region.height(), region.depth()};
            int along = axis == 'x' ? 0 : axis == 'z' ? 2 : 1;
            context.progress().phase("rotation", region.width());
            for (int x = region.minX(); x <= region.maxX(); x++) {
                context.progress().tick(1);
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    for (int z = region.minZ(); z <= region.maxZ(); z++) {
                        double[] p = {x - cx, y - cy, z - cz};
                        int base = along == 0 ? region.minX() : along == 1 ? region.minY() : region.minZ();
                        int coord = along == 0 ? x : along == 1 ? y : z;
                        double f = size[along] <= 1 ? 1 : (coord - base) / (double) (size[along] - 1);
                        double a = Math.toRadians(twist ? angle * f : angle);
                        double scale = 1 + (taper - 1) * f;
                        int i = along == 0 ? 1 : 0;
                        int j = along == 2 ? 1 : 2;
                        // The block that comes here: inverse rotation, then inverse scaling.
                        double u = p[i] * Math.cos(-a) - p[j] * Math.sin(-a);
                        double v = p[i] * Math.sin(-a) + p[j] * Math.cos(-a);
                        if (scale > 1e-3) {
                            u /= scale;
                            v /= scale;
                        }
                        double[] q = p.clone();
                        q[i] = u;
                        q[j] = v;
                        int sx = (int) Math.round(q[0] + cx);
                        int sy = (int) Math.round(q[1] + cy);
                        int sz = (int) Math.round(q[2] + cz);
                        BlockData source = region.contains(sx, sy, sz) ? view.get(sx, sy, sz) : context.air();
                        if (!source.equals(view.get(x, y, z))) {
                            changes.set(x, y, z, source);
                        }
                    }
                }
            }
            return changes;
        };
    }

    private static long hexCell(double x, double z) {
        double q = Math.sqrt(3) / 3 * x - z / 3;
        double r = 2.0 / 3 * z;
        double s = -q - r;
        long rq = Math.round(q);
        long rr = Math.round(r);
        long rs = Math.round(s);
        double dq = Math.abs(rq - q);
        double dr = Math.abs(rr - r);
        double ds = Math.abs(rs - s);
        if (dq > dr && dq > ds) {
            rq = -rr - rs;
        } else if (dr > ds) {
            rr = -rq - rs;
        }
        return (rq << 32) ^ (rr & 0xFFFFFFFFL);
    }

    /** Distance to the edge of the hexagon, in hexagon sizes. */
    private static double hexEdge(double x, double z) {
        long cell = hexCell(x, z);
        long rq = cell >> 32;
        long rr = (int) cell;
        double cx = Math.sqrt(3) * (rq + rr / 2.0);
        double cz = 1.5 * rr;
        double px = Math.abs(x - cx);
        double pz = Math.abs(z - cz);
        double hex = Math.max(px * Math.sqrt(3) / 2 + pz / 2, pz);
        return Math.max(0, (1 - hex)) * Math.sqrt(3) / 2;
    }
}
