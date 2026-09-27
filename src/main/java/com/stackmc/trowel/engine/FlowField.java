package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.geom.NoiseSpec;
import com.stackmc.trowel.pattern.Mask;
import com.stackmc.trowel.pattern.Palette;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Random;

/**
 * Flow fields, like ezEdits' {@code //ezflowfield}: a noise gives a direction at each point, lines
 * start at random and follow it, painting the palette where they pass.
 *
 * <p>In 2D (the default) the lines crawl on the relief of the selection and repaint its surface;
 * in 3D they fly through the volume. Where several lines cross, the palette goes further: the
 * first block for one pass, the second for two, and so on ({@code scalar} speeds that up).</p>
 */
public final class FlowField {

    private static final double STEP = 0.5;

    /**
     * @param lines      number of lines, or when {@code percent}, share of the start points in percent
     * @param iterations steps per line
     * @param velocity   blocks per step
     * @param scalar     palette steps per pass
     * @param inertia    0: follows the field, towards 1: keeps its previous direction
     * @param gravity    added to the direction at each step
     * @param start      where lines may start, or {@code null}
     * @param curl       follows the curl of the noise (swirls, no sinks) rather than its angle
     * @param fill       paints the untouched surface with the first block (2D)
     * @param threeD     lines through the volume rather than on the surface
     */
    public record Options(double lines, boolean percent, int iterations, double velocity, double scalar,
                          double inertia, double[] gravity, Mask start, boolean curl, boolean fill, boolean threeD) {
    }

    private FlowField() {
    }

    public static Engine.Compute build(Box box, Palette palette, NoiseSpec noise, Options options, long seed) {
        return context -> {
            BlockView view = context.terrain();
            Random random = new Random(seed);
            Long2IntOpenHashMap passes = new Long2IntOpenHashMap();
            Long2IntOpenHashMap surfaces = new Long2IntOpenHashMap();
            int lines = lineCount(box, options);
            context.progress().phase("computing", lines);
            for (int i = 0; i < lines; i++) {
                context.progress().tick(1);
                double[] p = startPoint(box, view, options, surfaces, random);
                if (p != null) {
                    trace(box, view, noise, options, surfaces, passes, p);
                }
            }
            ChangeSet changes = context.changes();
            if (options.fill() && !options.threeD()) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        int y = surface(box, view, surfaces, x, z);
                        if (y != Terrain.NONE) {
                            changes.set(x, y, z, palette.get(0));
                        }
                    }
                }
            }
            passes.long2IntEntrySet().fastForEach(entry -> {
                long key = entry.getLongKey();
                double v = Math.min(1, entry.getIntValue() * options.scalar() / palette.size());
                changes.set(Keys.x(key), Keys.y(key), Keys.z(key), palette.value(Math.max(1e-6, v)));
            });
            return changes;
        };
    }

    static int lineCount(Box box, Options options) {
        if (!options.percent()) {
            return (int) Math.max(1, Math.min(100_000, options.lines()));
        }
        long points = (long) box.width() * box.depth() * (options.threeD() ? box.height() : 1);
        return (int) Math.max(1, Math.min(100_000, Math.round(points * options.lines() / 100)));
    }

    private static double[] startPoint(Box box, BlockView view, Options options, Long2IntOpenHashMap surfaces,
                                       Random random) {
        for (int attempt = 0; attempt < 16; attempt++) {
            int x = box.minX() + random.nextInt(box.width());
            int z = box.minZ() + random.nextInt(box.depth());
            int y = options.threeD() ? box.minY() + random.nextInt(box.height()) : surface(box, view, surfaces, x, z);
            if (y == Terrain.NONE || (options.start() != null && !options.start().test(x, y, z, view))) {
                continue;
            }
            return new double[]{x + random.nextDouble(), y + 0.5, z + random.nextDouble()};
        }
        return null;
    }

    /** Follows the field from {@code p}, counting the blocks it passes over once per line. */
    private static void trace(Box box, BlockView view, NoiseSpec noise, Options options, Long2IntOpenHashMap surfaces,
                              Long2IntOpenHashMap passes, double[] p) {
        LongOpenHashSet seen = new LongOpenHashSet();
        double[] dir = null;
        int sub = (int) Math.max(1, Math.ceil(options.velocity() / STEP));
        double length = options.velocity() / sub;
        for (int i = 0; i <= options.iterations(); i++) {
            double[] field = direction(noise, p, options);
            if (dir != null && options.inertia() > 0) {
                for (int k = 0; k < 3; k++) {
                    field[k] = field[k] * (1 - options.inertia()) + dir[k] * options.inertia();
                }
            }
            double[] g = options.gravity();
            field[0] += g[0];
            field[1] += options.threeD() ? g[1] : 0;
            field[2] += g[2];
            dir = normalize(field);
            for (int s = 0; s < sub; s++) {
                int bx = (int) Math.floor(p[0]);
                int bz = (int) Math.floor(p[2]);
                int by = options.threeD() ? (int) Math.floor(p[1]) : surface(box, view, surfaces, bx, bz);
                if (!box.contains(bx, by, bz)) {
                    return;
                }
                long key = Keys.pack(bx, by, bz);
                if (seen.add(key)) {
                    passes.addTo(key, 1);
                }
                if (i == options.iterations()) {
                    return;
                }
                p[0] += dir[0] * length;
                p[1] = options.threeD() ? p[1] + dir[1] * length : by + 0.5;
                p[2] += dir[2] * length;
            }
        }
    }

    /** The unit direction of the field at this point. */
    static double[] direction(NoiseSpec noise, double[] p, Options options) {
        double x = p[0];
        double y = options.threeD() ? p[1] : 0;
        double z = p[2];
        if (!options.threeD()) {
            if (options.curl()) {
                double e = 0.5;
                double dx = noise.sample(x + e, 0, z) - noise.sample(x - e, 0, z);
                double dz = noise.sample(x, 0, z + e) - noise.sample(x, 0, z - e);
                return normalize(new double[]{dz, 0, -dx});
            }
            double angle = noise.sample(x, 0, z) * Math.PI * 4;
            return new double[]{Math.cos(angle), 0, Math.sin(angle)};
        }
        if (options.curl()) {
            double e = 0.5;
            double dn3dy = sample(noise, 2, x, y + e, z) - sample(noise, 2, x, y - e, z);
            double dn2dz = sample(noise, 1, x, y, z + e) - sample(noise, 1, x, y, z - e);
            double dn1dz = sample(noise, 0, x, y, z + e) - sample(noise, 0, x, y, z - e);
            double dn3dx = sample(noise, 2, x + e, y, z) - sample(noise, 2, x - e, y, z);
            double dn2dx = sample(noise, 1, x + e, y, z) - sample(noise, 1, x - e, y, z);
            double dn1dy = sample(noise, 0, x, y + e, z) - sample(noise, 0, x, y - e, z);
            return normalize(new double[]{dn3dy - dn2dz, dn1dz - dn3dx, dn2dx - dn1dy});
        }
        double yaw = sample(noise, 0, x, y, z) * Math.PI * 4;
        double pitch = (sample(noise, 1, x, y, z) - 0.5) * Math.PI;
        return new double[]{Math.cos(yaw) * Math.cos(pitch), Math.sin(pitch), Math.sin(yaw) * Math.cos(pitch)};
    }

    /** One of three independent readings of the noise, shifted far apart. */
    private static double sample(NoiseSpec noise, int channel, double x, double y, double z) {
        return noise.sample(x + channel * 1031.7, y + channel * 517.3, z - channel * 2749.1);
    }

    private static double[] normalize(double[] v) {
        double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (length < 1e-9) {
            return new double[]{1, 0, 0};
        }
        return new double[]{v[0] / length, v[1] / length, v[2] / length};
    }

    private static int surface(Box box, BlockView view, Long2IntOpenHashMap surfaces, int x, int z) {
        long key = Keys.pack(x, 0, z);
        if (surfaces.containsKey(key)) {
            return surfaces.get(key);
        }
        int y = Terrain.surface(view, x, z, box.maxY(), box.minY());
        surfaces.put(key, y);
        return y;
    }
}
