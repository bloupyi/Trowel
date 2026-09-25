package com.stackmc.trowel.geom;

import com.stackmc.trowel.api.Keys;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Shapes in integer coordinates. Nothing is read from the world: they are tested without a server.
 *
 * <p>Radii are WorldEdit's: a sphere of radius 2 is five blocks wide, because the center
 * half-block counts.</p>
 */
public final class Shapes {

    @FunctionalInterface
    public interface Cell {
        void accept(int x, int y, int z);
    }

    private Shapes() {
    }

    public static void ellipsoid(int cx, int cy, int cz, double rx, double ry, double rz, boolean hollow, Cell out) {
        double ax = rx + 0.5;
        double ay = ry + 0.5;
        double az = rz + 0.5;
        int ix = (int) Math.ceil(ax);
        int iy = (int) Math.ceil(ay);
        int iz = (int) Math.ceil(az);
        for (int dx = -ix; dx <= ix; dx++) {
            for (int dy = -iy; dy <= iy; dy++) {
                for (int dz = -iz; dz <= iz; dz++) {
                    if (!inside(dx, dy, dz, ax, ay, az)) {
                        continue;
                    }
                    if (hollow && inside(dx + 1, dy, dz, ax, ay, az) && inside(dx - 1, dy, dz, ax, ay, az)
                            && inside(dx, dy + 1, dz, ax, ay, az) && inside(dx, dy - 1, dz, ax, ay, az)
                            && inside(dx, dy, dz + 1, ax, ay, az) && inside(dx, dy, dz - 1, ax, ay, az)) {
                        continue;
                    }
                    out.accept(cx + dx, cy + dy, cz + dz);
                }
            }
        }
    }

    private static boolean inside(double dx, double dy, double dz, double ax, double ay, double az) {
        return dx * dx / (ax * ax) + dy * dy / (ay * ay) + dz * dz / (az * az) <= 1.0;
    }

    /** An upright cylinder. A negative height goes down; hollow, it only has its wall. */
    public static void cylinder(int cx, int cy, int cz, double rx, double rz, int height, boolean hollow, Cell out) {
        double ax = rx + 0.5;
        double az = rz + 0.5;
        int ix = (int) Math.ceil(ax);
        int iz = (int) Math.ceil(az);
        int size = Math.max(1, Math.abs(height));
        int step = height < 0 ? -1 : 1;
        for (int dx = -ix; dx <= ix; dx++) {
            for (int dz = -iz; dz <= iz; dz++) {
                if (!inside(dx, 0, dz, ax, 1, az)) {
                    continue;
                }
                boolean edge = !inside(dx + 1, 0, dz, ax, 1, az) || !inside(dx - 1, 0, dz, ax, 1, az)
                        || !inside(dx, 0, dz + 1, ax, 1, az) || !inside(dx, 0, dz - 1, ax, 1, az);
                if (hollow && !edge) {
                    continue;
                }
                for (int i = 0; i < size; i++) {
                    out.accept(cx + dx, cy + step * i, cz + dz);
                }
            }
        }
    }

    /** A stepped pyramid standing on (cx, cy, cz). Hollow, only its sides remain. */
    public static void pyramid(int cx, int cy, int cz, int size, boolean hollow, Cell out) {
        for (int level = 0; level < size; level++) {
            int half = size - 1 - level;
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (hollow && Math.abs(dx) != half && Math.abs(dz) != half) {
                        continue;
                    }
                    out.accept(cx + dx, cy + level, cz + dz);
                }
            }
        }
    }

    /** A cone standing on (cx, cy, cz), tip up. Hollow, only its side remains. */
    public static void cone(int cx, int cy, int cz, double radius, int height, boolean hollow, Cell out) {
        for (int level = 0; level < height; level++) {
            double a = radius * (1 - (double) level / height) + 0.5;
            double next = level + 1 < height ? radius * (1 - (double) (level + 1) / height) + 0.5 : -1;
            int ir = (int) Math.ceil(a);
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    double d2 = dx * dx + dz * dz;
                    if (d2 > a * a) {
                        continue;
                    }
                    boolean edge = d2 > (a - 1) * (a - 1) || d2 > next * next;
                    if (hollow && !edge) {
                        continue;
                    }
                    out.accept(cx + dx, cy + level, cz + dz);
                }
            }
        }
    }

    /**
     * A smooth curve through every point, in order (Catmull-Rom).
     *
     * <p>It is the ezEdits spline and Arceon's multi-anchor rope: place points, the curve joins
     * them without corners.</p>
     */
    public static List<double[]> spline(List<double[]> points) {
        List<double[]> out = new ArrayList<>();
        if (points.isEmpty()) {
            return out;
        }
        if (points.size() == 1) {
            out.add(points.get(0));
            return out;
        }
        for (int i = 0; i < points.size() - 1; i++) {
            double[] p0 = points.get(Math.max(0, i - 1));
            double[] p1 = points.get(i);
            double[] p2 = points.get(i + 1);
            double[] p3 = points.get(Math.min(points.size() - 1, i + 2));
            double length = Math.sqrt(Math.pow(p2[0] - p1[0], 2) + Math.pow(p2[1] - p1[1], 2) + Math.pow(p2[2] - p1[2], 2));
            int steps = Math.max(2, (int) Math.ceil(length * 4));
            for (int step = 0; step < steps; step++) {
                double t = (double) step / steps;
                double[] q = new double[3];
                for (int k = 0; k < 3; k++) {
                    q[k] = 0.5 * (2 * p1[k] + (-p0[k] + p2[k]) * t
                            + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t * t
                            + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t * t * t);
                }
                out.add(q);
            }
        }
        out.add(points.get(points.size() - 1));
        return out;
    }

    /** A disc perpendicular to an axis, for spikes coming out of a wall. */
    public static void disk(double cx, double cy, double cz, double radius, int ax, int ay, int az, Cell out) {
        double a = radius + 0.5;
        int ir = (int) Math.ceil(a);
        for (int u = -ir; u <= ir; u++) {
            for (int v = -ir; v <= ir; v++) {
                if (u * u + v * v > a * a) {
                    continue;
                }
                if (ay != 0) {
                    out.accept((int) Math.floor(cx + u), (int) Math.floor(cy), (int) Math.floor(cz + v));
                } else if (ax != 0) {
                    out.accept((int) Math.floor(cx), (int) Math.floor(cy + u), (int) Math.floor(cz + v));
                } else {
                    out.accept((int) Math.floor(cx + u), (int) Math.floor(cy + v), (int) Math.floor(cz));
                }
            }
        }
    }

    /**
     * Points of a curve between two block centers, bulging by {@code bulge} in its middle.
     *
     * <p>Positive, it is an arch; negative, a hanging rope. Zero, a straight line.</p>
     */
    public static List<double[]> curve(double x1, double y1, double z1, double x2, double y2, double z2, double bulge) {
        double length = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        int steps = Math.max(2, (int) Math.ceil((length + Math.abs(bulge) * 2) * 4));
        List<double[]> points = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            points.add(new double[]{
                    x1 + (x2 - x1) * t + 0.5,
                    y1 + (y2 - y1) * t + 0.5 + bulge * 4 * t * (1 - t),
                    z1 + (z2 - z1) * t + 0.5});
        }
        return points;
    }

    /** Thickens a curve: each point becomes a ball of this diameter. */
    public static void thicken(List<double[]> points, double thickness, Cell out) {
        LongOpenHashSet seen = new LongOpenHashSet();
        Cell once = (x, y, z) -> {
            if (seen.add(Keys.pack(x, y, z))) {
                out.accept(x, y, z);
            }
        };
        double radius = Math.max(0, (thickness - 1) / 2.0);
        for (double[] point : points) {
            int bx = (int) Math.floor(point[0]);
            int by = (int) Math.floor(point[1]);
            int bz = (int) Math.floor(point[2]);
            if (radius <= 0) {
                once.accept(bx, by, bz);
            } else {
                ellipsoid(bx, by, bz, radius, radius, radius, false, once);
            }
        }
    }

    /**
     * A deck along a curve: {@code width} wide horizontally, {@code thickness} thick
     * downwards. It is the shape of an arch bridge.
     */
    public static void band(List<double[]> points, double sideX, double sideZ, int width, int thickness, Cell out) {
        LongOpenHashSet seen = new LongOpenHashSet();
        double half = (width - 1) / 2.0;
        for (double[] point : points) {
            for (double offset = -half; offset <= half + 1e-9; offset += 0.5) {
                for (double down = 0; down < thickness - 1e-9; down += 0.5) {
                    int x = (int) Math.floor(point[0] + sideX * offset);
                    int y = (int) Math.floor(point[1] - down);
                    int z = (int) Math.floor(point[2] + sideZ * offset);
                    if (seen.add(Keys.pack(x, y, z))) {
                        out.accept(x, y, z);
                    }
                }
            }
        }
    }
}
