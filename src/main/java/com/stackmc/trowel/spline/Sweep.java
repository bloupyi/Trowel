package com.stackmc.trowel.spline;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.engine.ChangeSet;
import com.stackmc.trowel.engine.EditContext;
import com.stackmc.trowel.engine.Progress;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.pattern.Local;
import com.stackmc.trowel.pattern.Mask;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.Locale;

/**
 * Sweeps a section along a path and turns it into blocks.
 *
 * <p>Rather than placing discs along the curve (which leaves holes in bends and bulges on the
 * inside), each block near the path looks for its closest point on the curve: first among a few
 * spaced samples, then finely around it, then exactly on the segment. From there it knows its
 * place in the section (u, v), along the path (w, t) and the radius there: the shape says
 * whether it is solid, the pattern what it carries. No hole, no duplicate, whatever the
 * curvature.</p>
 */
public final class Sweep {

    public enum End {
        FLAT, SOFT, SPIKE, ROUND, CUBE;

        public static End parse(String raw) {
            try {
                return valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Caps: flat, soft, spike, round or cube.");
            }
        }
    }

    /** Fine sampling step, in blocks: the smaller, the more accurate the shape. */
    public enum Quality {
        FAST(0.5), BALANCED(0.25), HIGH(0.12), EXACT(0.05);

        final double step;

        Quality(double step) {
            this.step = step;
        }

        public double step() {
            return step;
        }

        public static Quality parse(String raw) {
            try {
                return valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Quality: fast, balanced, high or exact.");
            }
        }
    }

    /** What a block of the shape carries. */
    @FunctionalInterface
    public interface Fill {
        BlockData at(int x, int y, int z, Local local);
    }

    /**
     * @param roll     starting angle, in degrees
     * @param rollEnd  ending angle, or NaN to turn by {@code twist} per diameter
     * @param twist    degrees of twist per diameter of path travelled
     * @param stretch  stretches (greater than 1) or squashes the shape along the path
     */
    public record Options(Radii radii, double roll, double rollEnd, double twist, double stretch, End end,
                          Quality quality, boolean hollow, Path.Normal normal, double tension, double bias,
                          double continuity, boolean closed) {
    }

    private Sweep() {
    }

    /** The box the shape may touch: the points, plus the largest radius and a margin for curves. */
    public static Box bounds(List<double[]> points, Options o, Sections.Section section) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        double longest = 0;
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            minZ = Math.min(minZ, p[2]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
            maxZ = Math.max(maxZ, p[2]);
            if (i > 0) {
                double[] q = points.get(i - 1);
                longest = Math.max(longest, Math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1])
                        + (p[2] - q[2]) * (p[2] - q[2])));
            }
        }
        double reach = o.radii().max() * section.reach() * (o.end() == End.FLAT || o.end() == End.SOFT ? 1 : 1.5)
                + longest * 0.3 + 2;
        int r = (int) Math.ceil(reach);
        return new Box((int) Math.floor(minX) - r, (int) Math.floor(minY) - r, (int) Math.floor(minZ) - r,
                (int) Math.floor(maxX) + r, (int) Math.floor(maxY) + r, (int) Math.floor(maxZ) + r);
    }

    /**
     * The whole computation: path, neighbourhood, shape. Off the main thread.
     *
     * @param mask what the shape may replace, or {@code null}
     */
    public static ChangeSet build(EditContext context, List<double[]> points, Sections.Section section, Options o,
                                  Fill fill, Mask mask) {
        return build(context, points, section, o, fill, mask, null);
    }

    /**
     * The whole computation, with shaping blocks on the surface ({@code smooth}, or {@code null}).
     */
    public static ChangeSet build(EditContext context, List<double[]> points, Sections.Section section, Options o,
                                  Fill fill, Mask mask, Smoothblocks.Spec smooth) {
        Progress progress = context.progress();
        progress.phase("path", 0);
        double fine = o.quality().step();
        Path path = Path.build(points, o.tension(), o.bias(), o.continuity(), o.closed(), o.normal(), fine);
        int n = path.size();
        double length = path.length();
        double step = path.step();

        double[] radius = new double[n];
        double[] w = new double[n];
        double[] roll = new double[n];
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0 : (double) i / (n - 1);
            radius[i] = o.radii().at(t);
            if (i > 0) {
                w[i] = w[i - 1] + step / ((radius[i - 1] + radius[i]) / 2);
            }
        }
        for (int i = 0; i < n; i++) {
            double t = (double) i / (n - 1);
            double degrees = Double.isNaN(o.rollEnd()) ? o.roll() + o.twist() * w[i] / 2
                    : o.roll() + (o.rollEnd() - o.roll()) * t;
            roll[i] = Math.toRadians(degrees);
            w[i] /= Math.max(0.01, o.stretch());
        }

        // Coarse neighbourhood: each nearby block keeps the closest spaced sample.
        double minRadius = Double.MAX_VALUE;
        for (double r : radius) {
            minRadius = Math.min(minRadius, r);
        }
        int stride = Math.max(1, (int) Math.floor(Math.min(4, Math.max(0.5, minRadius * 0.5)) / step));
        boolean capped = !o.closed() && (o.end() == End.ROUND || o.end() == End.SPIKE || o.end() == End.CUBE);
        long budget = (long) context.limit() * 24L;
        Long2IntOpenHashMap coarse = new Long2IntOpenHashMap();
        Long2DoubleOpenHashMap coarseDistance = new Long2DoubleOpenHashMap();
        coarseDistance.defaultReturnValue(Double.MAX_VALUE);
        progress.phase("neighbourhood", n / stride + 1);
        for (int c = 0; ; c = Math.min(n - 1, c + stride)) {
            progress.tick(1);
            double reach = radius[c] * section.reach() + stride * step * 0.75 + 1.2;
            if (capped && (c == 0 || c == n - 1)) {
                reach += radius[c] * 1.2;
            }
            int ir = (int) Math.ceil(reach);
            double cx = path.px[c];
            double cy = path.py[c];
            double cz = path.pz[c];
            int bx = (int) Math.floor(cx);
            int by = (int) Math.floor(cy);
            int bz = (int) Math.floor(cz);
            for (int x = bx - ir; x <= bx + ir; x++) {
                double dx = x + 0.5 - cx;
                for (int y = by - ir; y <= by + ir; y++) {
                    double dy = y + 0.5 - cy;
                    for (int z = bz - ir; z <= bz + ir; z++) {
                        double dz = z + 0.5 - cz;
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d > reach * reach) {
                            continue;
                        }
                        long key = Keys.pack(x, y, z);
                        if (d < coarseDistance.get(key)) {
                            coarseDistance.put(key, d);
                            coarse.put(key, c);
                        }
                    }
                }
            }
            if (coarse.size() > budget) {
                throw new IllegalArgumentException("Spline too big: more than " + budget
                        + " blocks to examine. Shrink the radius or the length.");
            }
            if (c == n - 1) {
                break;
            }
        }
        coarseDistance.clear();

        // For each block: the exact closest point of the path, then the shape.
        ChangeSet changes = context.changes();
        Long2ObjectOpenHashMap<BlockData> inside = o.hollow() ? new Long2ObjectOpenHashMap<>() : null;
        Local local = new Local();
        Probe probe = new Probe(path, o, section, radius, w, roll, stride);
        progress.phase("shape", coarse.size());
        if (smooth != null) {
            Long2ObjectOpenHashMap<Smoothblocks.Cell> cells = new Long2ObjectOpenHashMap<>();
            for (Long2IntMap.Entry entry : coarse.long2IntEntrySet()) {
                progress.tick();
                long key = entry.getLongKey();
                int c = entry.getIntValue();
                int x = Keys.x(key);
                int y = Keys.y(key);
                int z = Keys.z(key);
                if (mask != null && !mask.test(x, y, z, context.view())) {
                    continue;
                }
                int bits = 0;
                BlockData full = null;
                if (probe.eval(x + 0.5, y + 0.5, z + 0.5, c, local) > 0) {
                    full = fill.at(x, y, z, local);
                }
                for (int i = 0; i < 8; i++) {
                    double sx = x + ((i & 1) != 0 ? 0.75 : 0.25);
                    double sy = y + ((i & 2) != 0 ? 0.75 : 0.25);
                    double sz = z + ((i & 4) != 0 ? 0.75 : 0.25);
                    if (probe.eval(sx, sy, sz, c, local) > 0) {
                        bits |= 1 << i;
                        if (full == null) {
                            full = fill.at(x, y, z, local);
                        }
                    }
                }
                if (bits != 0 && full != null) {
                    cells.put(key, new Smoothblocks.Cell(bits, full));
                }
            }
            Long2ObjectOpenHashMap<BlockData> shaped = Smoothblocks.shape(cells, smooth);
            if (inside != null) {
                inside.putAll(shaped);
            } else {
                shaped.long2ObjectEntrySet().forEach(e -> changes.set(e.getLongKey(), e.getValue()));
            }
        } else {
            for (Long2IntMap.Entry entry : coarse.long2IntEntrySet()) {
                progress.tick();
                long key = entry.getLongKey();
                int x = Keys.x(key);
                int y = Keys.y(key);
                int z = Keys.z(key);
                if (!(probe.eval(x + 0.5, y + 0.5, z + 0.5, entry.getIntValue(), local) > 0)) {
                    continue;
                }
                if (mask != null && !mask.test(x, y, z, context.view())) {
                    continue;
                }
                BlockData data = fill.at(x, y, z, local);
                if (data == null) {
                    continue;
                }
                if (inside != null) {
                    inside.put(key, data);
                } else {
                    changes.set(x, y, z, data);
                }
            }
        }

        if (inside != null) {
            progress.phase("hollow", inside.size());
            for (Long2ObjectOpenHashMap.Entry<BlockData> entry : inside.long2ObjectEntrySet()) {
                progress.tick();
                long key = entry.getLongKey();
                if (!inside.containsKey(Keys.offset(key, 1, 0, 0)) || !inside.containsKey(Keys.offset(key, -1, 0, 0))
                        || !inside.containsKey(Keys.offset(key, 0, 1, 0)) || !inside.containsKey(Keys.offset(key, 0, -1, 0))
                        || !inside.containsKey(Keys.offset(key, 0, 0, 1)) || !inside.containsKey(Keys.offset(key, 0, 0, -1))) {
                    changes.set(key, entry.getValue());
                }
            }
        }
        return changes;
    }

    /** Samples the shape at any point: the closest point of the path, then the section. */
    static final class Probe {
        private final Path path;
        private final Options o;
        private final Sections.Section section;
        private final double[] radius;
        private final double[] w;
        private final double[] roll;
        private final int n;
        private final int stride;
        private final double length;
        private final double step;

        Probe(Path path, Options o, Sections.Section section, double[] radius, double[] w, double[] roll, int stride) {
            this.path = path;
            this.o = o;
            this.section = section;
            this.radius = radius;
            this.w = w;
            this.roll = roll;
            this.n = path.size();
            this.stride = stride;
            this.length = path.length();
            this.step = path.step();
        }

        /** The section value at this point (0 outside), near the path sample {@code c}; fills {@code local}. */
        double eval(double qx, double qy, double qz, int c, Local local) {
            int from = Math.max(0, c - stride - 1);
            int to = Math.min(n - 1, c + stride + 1);
            int best = c;
            double bestD = Double.MAX_VALUE;
            for (int j = from; j <= to; j++) {
                double dx = qx - path.px[j];
                double dy = qy - path.py[j];
                double dz = qz - path.pz[j];
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = j;
                }
            }
            // On the segment on either side of the best sample.
            int segment = best;
            double f = 0;
            bestD = Double.MAX_VALUE;
            for (int j = Math.max(0, best - 1); j <= Math.min(n - 2, best); j++) {
                double ax = path.px[j];
                double ay = path.py[j];
                double az = path.pz[j];
                double ex = path.px[j + 1] - ax;
                double ey = path.py[j + 1] - ay;
                double ez = path.pz[j + 1] - az;
                double len2 = ex * ex + ey * ey + ez * ez;
                double g = len2 == 0 ? 0 : ((qx - ax) * ex + (qy - ay) * ey + (qz - az) * ez) / len2;
                g = Math.max(0, Math.min(1, g));
                double px = ax + ex * g - qx;
                double py = ay + ey * g - qy;
                double pz = az + ez * g - qz;
                double d = px * px + py * py + pz * pz;
                if (d < bestD) {
                    bestD = d;
                    segment = j;
                    f = g;
                }
            }
            int j1 = Math.min(n - 1, segment + 1);
            double ox = lerp(path.px[segment], path.px[j1], f);
            double oy = lerp(path.py[segment], path.py[j1], f);
            double oz = lerp(path.pz[segment], path.pz[j1], f);
            double dx = qx - ox;
            double dy = qy - oy;
            double dz = qz - oz;
            double tx = lerp(path.tx[segment], path.tx[j1], f);
            double ty = lerp(path.ty[segment], path.ty[j1], f);
            double tz = lerp(path.tz[segment], path.tz[j1], f);
            double ux = lerp(path.ux[segment], path.ux[j1], f);
            double uy = lerp(path.uy[segment], path.uy[j1], f);
            double uz = lerp(path.uz[segment], path.uz[j1], f);
            double vx = lerp(path.vx[segment], path.vx[j1], f);
            double vy = lerp(path.vy[segment], path.vy[j1], f);
            double vz = lerp(path.vz[segment], path.vz[j1], f);
            double r = lerp(radius[segment], radius[j1], f);
            double s = Math.min(length, (segment + f) * step);
            double t = length == 0 ? 0 : s / length;
            double wl = lerp(w[segment], w[j1], f);
            double angle = lerp(roll[segment], roll[j1], f);

            double axial = dx * tx + dy * ty + dz * tz;
            double ru = dx * ux + dy * uy + dz * uz;
            double rv = dx * vx + dy * vy + dz * vz;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double u = (cos * ru + sin * rv) / r;
            double v = (-sin * ru + cos * rv) / r;

            boolean atStart = !o.closed() && segment == 0 && f <= 0 && axial < -0.49;
            boolean atEnd = !o.closed() && j1 == n - 1 && f >= 1 && axial > 0.49;
            if (atStart || atEnd) {
                double e = Math.abs(axial) / r;
                double k;
                switch (o.end()) {
                    case ROUND -> k = e < 1 ? Math.sqrt(1 - e * e) : 0;
                    case SPIKE -> k = 1 - e;
                    case CUBE -> k = e <= 1 ? 1 : 0;
                    default -> k = 0;
                }
                if (k <= 0.02) {
                    return 0;
                }
                u /= k;
                v /= k;
                wl += (atStart ? -e : e) / Math.max(0.01, o.stretch());
            } else if (o.end() == End.SOFT && !o.closed()) {
                double de = Math.min(s, length - s) / r;
                if (de < 1) {
                    double k = Math.sqrt(Math.max(0, 1 - (1 - de) * (1 - de)));
                    if (k <= 0.02) {
                        return 0;
                    }
                    u /= k;
                    v /= k;
                }
            }
            double value = section.eval(u, v, wl, t);
            if (value > 0) {
                local.set(u, v, wl, t, s, r, value);
                return value;
            }
            return 0;
        }
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }
}
