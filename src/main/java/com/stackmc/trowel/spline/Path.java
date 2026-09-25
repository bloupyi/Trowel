package com.stackmc.trowel.spline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The path of a spline: a Kochanek-Bartels curve through every point, sampled at a regular
 * step along its length, with a frame at each sample.
 *
 * <p>The frame (U, V, T) orients the section: T follows the curve, U and V are the section axes.
 * Three ways to turn it, like ezEdits:</p>
 * <ul>
 *   <li>{@code consistent}: rotation minimizing frame (double reflection): the section never
 *       twists by itself, it only rolls as much as needed in bends;</li>
 *   <li>{@code horizontal}: V stays as close to vertical as possible, the section does not roll;</li>
 *   <li>{@code upright}: V is the world vertical, the section is no longer perpendicular to the path.</li>
 * </ul>
 */
public final class Path {

    public enum Normal {
        CONSISTENT, HORIZONTAL, UPRIGHT;

        public static Normal parse(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "consistent", "c", "rmf" -> CONSISTENT;
                case "horizontal", "h" -> HORIZONTAL;
                case "upright", "u", "vertical" -> UPRIGHT;
                default -> throw new IllegalArgumentException("Orientation: consistent, horizontal or upright.");
            };
        }
    }

    private final double step;
    private final double length;
    private final boolean closed;
    final double[] px;
    final double[] py;
    final double[] pz;
    final double[] tx;
    final double[] ty;
    final double[] tz;
    final double[] ux;
    final double[] uy;
    final double[] uz;
    final double[] vx;
    final double[] vy;
    final double[] vz;

    private Path(int n, double step, double length, boolean closed) {
        this.step = step;
        this.length = length;
        this.closed = closed;
        px = new double[n];
        py = new double[n];
        pz = new double[n];
        tx = new double[n];
        ty = new double[n];
        tz = new double[n];
        ux = new double[n];
        uy = new double[n];
        uz = new double[n];
        vx = new double[n];
        vy = new double[n];
        vz = new double[n];
    }

    public int size() {
        return px.length;
    }

    public double step() {
        return step;
    }

    public double length() {
        return length;
    }

    public boolean closed() {
        return closed;
    }

    /** Point number {@code i}, {@code i * step} blocks from the start. */
    public double[] point(int i) {
        return new double[]{px[i], py[i], pz[i]};
    }

    /**
     * Builds the path.
     *
     * @param points     the waypoints, block centers
     * @param tension    -1 to 1: 1 tightens the curve (straight segments), -1 loosens it
     * @param bias       -1 to 1: leans the curve towards the point before or after
     * @param continuity -1 to 1: rounds or breaks the corners at the points
     * @param closed     loops from the last point to the first
     * @param step       sampling step, in blocks
     */
    public static Path build(List<double[]> points, double tension, double bias, double continuity, boolean closed,
                             Normal normal, double step) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("A spline needs at least two points.");
        }
        List<double[]> dense = dense(points, tension, bias, continuity, closed);
        double[] cumulative = new double[dense.size()];
        for (int i = 1; i < dense.size(); i++) {
            cumulative[i] = cumulative[i - 1] + distance(dense.get(i - 1), dense.get(i));
        }
        double total = cumulative[dense.size() - 1];
        if (total < 1e-6) {
            throw new IllegalArgumentException("The spline points are all at the same place.");
        }
        int n = Math.max(2, (int) Math.ceil(total / step) + 1);
        double real = total / (n - 1);
        Path path = new Path(n, real, total, closed);
        int j = 0;
        for (int i = 0; i < n; i++) {
            double s = Math.min(total, i * real);
            while (j < dense.size() - 2 && cumulative[j + 1] < s) {
                j++;
            }
            double span = cumulative[j + 1] - cumulative[j];
            double f = span <= 0 ? 0 : (s - cumulative[j]) / span;
            double[] a = dense.get(j);
            double[] b = dense.get(j + 1);
            path.px[i] = a[0] + (b[0] - a[0]) * f;
            path.py[i] = a[1] + (b[1] - a[1]) * f;
            path.pz[i] = a[2] + (b[2] - a[2]) * f;
        }
        path.tangents();
        path.frames(normal);
        return path;
    }

    /** The curve finely cut, one point about every eighth of a block. */
    private static List<double[]> dense(List<double[]> points, double t, double b, double c, boolean closed) {
        List<double[]> pts = new ArrayList<>(points);
        int count = pts.size();
        int segments = closed ? count : count - 1;
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < segments; i++) {
            double[] p0 = get(pts, i - 1, closed);
            double[] p1 = get(pts, i, closed);
            double[] p2 = get(pts, i + 1, closed);
            double[] p3 = get(pts, i + 2, closed);
            double[] m1 = new double[3];
            double[] m2 = new double[3];
            for (int k = 0; k < 3; k++) {
                // Start tangent at p1 and end tangent at p2 (Kochanek-Bartels).
                m1[k] = (1 - t) * (1 + b) * (1 + c) / 2 * (p1[k] - p0[k]) + (1 - t) * (1 - b) * (1 - c) / 2 * (p2[k] - p1[k]);
                m2[k] = (1 - t) * (1 + b) * (1 - c) / 2 * (p2[k] - p1[k]) + (1 - t) * (1 - b) * (1 + c) / 2 * (p3[k] - p2[k]);
            }
            double chord = distance(p1, p2);
            int steps = Math.max(2, (int) Math.ceil(chord * 8));
            for (int s = 0; s < steps; s++) {
                double u = (double) s / steps;
                double h00 = 2 * u * u * u - 3 * u * u + 1;
                double h10 = u * u * u - 2 * u * u + u;
                double h01 = -2 * u * u * u + 3 * u * u;
                double h11 = u * u * u - u * u;
                out.add(new double[]{
                        h00 * p1[0] + h10 * m1[0] + h01 * p2[0] + h11 * m2[0],
                        h00 * p1[1] + h10 * m1[1] + h01 * p2[1] + h11 * m2[1],
                        h00 * p1[2] + h10 * m1[2] + h01 * p2[2] + h11 * m2[2]});
            }
        }
        out.add(closed ? pts.get(0).clone() : pts.get(count - 1).clone());
        return out;
    }

    /** A point, extending the ends (open) or looping (closed). */
    private static double[] get(List<double[]> pts, int i, boolean closed) {
        int n = pts.size();
        if (closed) {
            return pts.get(Math.floorMod(i, n));
        }
        if (i < 0) {
            double[] a = pts.get(0);
            double[] b = pts.get(1);
            return new double[]{2 * a[0] - b[0], 2 * a[1] - b[1], 2 * a[2] - b[2]};
        }
        if (i >= n) {
            double[] a = pts.get(n - 1);
            double[] b = pts.get(n - 2);
            return new double[]{2 * a[0] - b[0], 2 * a[1] - b[1], 2 * a[2] - b[2]};
        }
        return pts.get(i);
    }

    private void tangents() {
        int n = size();
        for (int i = 0; i < n; i++) {
            int a = i == 0 ? (closed ? n - 2 : 0) : i - 1;
            int b = i == n - 1 ? (closed ? 1 : n - 1) : i + 1;
            double dx = px[b] - px[a];
            double dy = py[b] - py[a];
            double dz = pz[b] - pz[a];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-9) {
                if (i > 0) {
                    tx[i] = tx[i - 1];
                    ty[i] = ty[i - 1];
                    tz[i] = tz[i - 1];
                } else {
                    tx[i] = 1;
                }
                continue;
            }
            tx[i] = dx / len;
            ty[i] = dy / len;
            tz[i] = dz / len;
        }
    }

    private void frames(Normal normal) {
        int n = size();
        // First frame: V as close to vertical as possible.
        double[] v0 = perpendicularUp(tx[0], ty[0], tz[0], null);
        set(0, v0);
        for (int i = 1; i < n; i++) {
            double[] v = switch (normal) {
                case CONSISTENT -> reflect(i);
                case HORIZONTAL -> perpendicularUp(tx[i], ty[i], tz[i], new double[]{vx[i - 1], vy[i - 1], vz[i - 1]});
                case UPRIGHT -> null;
            };
            if (normal == Normal.UPRIGHT) {
                uprightFrame(i);
            } else {
                set(i, v);
            }
        }
        if (normal == Normal.UPRIGHT) {
            uprightFrame(0);
        }
        if (closed && normal == Normal.CONSISTENT && n > 2) {
            closeTwist();
        }
    }

    /** Double reflection (Wang et al.): the next frame, without rotation around T. */
    private double[] reflect(int i) {
        double v1x = px[i] - px[i - 1];
        double v1y = py[i] - py[i - 1];
        double v1z = pz[i] - pz[i - 1];
        double c1 = v1x * v1x + v1y * v1y + v1z * v1z;
        double rx = vx[i - 1];
        double ry = vy[i - 1];
        double rz = vz[i - 1];
        double ttx = tx[i - 1];
        double tty = ty[i - 1];
        double ttz = tz[i - 1];
        if (c1 > 1e-12) {
            double k = 2 / c1 * (v1x * rx + v1y * ry + v1z * rz);
            rx -= k * v1x;
            ry -= k * v1y;
            rz -= k * v1z;
            double kt = 2 / c1 * (v1x * ttx + v1y * tty + v1z * ttz);
            ttx -= kt * v1x;
            tty -= kt * v1y;
            ttz -= kt * v1z;
        }
        double v2x = tx[i] - ttx;
        double v2y = ty[i] - tty;
        double v2z = tz[i] - ttz;
        double c2 = v2x * v2x + v2y * v2y + v2z * v2z;
        if (c2 > 1e-12) {
            double k = 2 / c2 * (v2x * rx + v2y * ry + v2z * rz);
            rx -= k * v2x;
            ry -= k * v2y;
            rz -= k * v2z;
        }
        return orthonormal(tx[i], ty[i], tz[i], rx, ry, rz);
    }

    /** The vertical projected perpendicular to T; failing that (T vertical), the previous one or east. */
    private static double[] perpendicularUp(double tx, double ty, double tz, double[] fallback) {
        double dot = ty;
        double x = -dot * tx;
        double y = 1 - dot * ty;
        double z = -dot * tz;
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-4) {
            if (fallback != null) {
                return orthonormal(tx, ty, tz, fallback[0], fallback[1], fallback[2]);
            }
            return orthonormal(tx, ty, tz, 1, 0, 0);
        }
        return new double[]{x / len, y / len, z / len};
    }

    private static double[] orthonormal(double tx, double ty, double tz, double x, double y, double z) {
        double dot = x * tx + y * ty + z * tz;
        x -= dot * tx;
        y -= dot * ty;
        z -= dot * tz;
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-9) {
            return Math.abs(tx) < 0.9 ? orthonormal(tx, ty, tz, 1, 0, 0) : orthonormal(tx, ty, tz, 0, 0, 1);
        }
        return new double[]{x / len, y / len, z / len};
    }

    /** V given; U = T x V: the right hand of someone looking along the path, head up. */
    private void set(int i, double[] v) {
        vx[i] = v[0];
        vy[i] = v[1];
        vz[i] = v[2];
        ux[i] = ty[i] * v[2] - tz[i] * v[1];
        uy[i] = tz[i] * v[0] - tx[i] * v[2];
        uz[i] = tx[i] * v[1] - ty[i] * v[0];
    }

    private void uprightFrame(int i) {
        double x = tz[i];
        double z = -tx[i];
        double len = Math.sqrt(x * x + z * z);
        if (len < 1e-4) {
            if (i > 0) {
                ux[i] = ux[i - 1];
                uy[i] = uy[i - 1];
                uz[i] = uz[i - 1];
            } else {
                ux[i] = 1;
            }
        } else {
            ux[i] = -x / len;
            uy[i] = 0;
            uz[i] = -z / len;
        }
        vx[i] = 0;
        vy[i] = 1;
        vz[i] = 0;
    }

    /** A closed loop: the small rotation gap at the end is spread over the whole path. */
    private void closeTwist() {
        int n = size() - 1;
        double[] v0 = {vx[0], vy[0], vz[0]};
        double cos = vx[n] * v0[0] + vy[n] * v0[1] + vz[n] * v0[2];
        double sin = ux[n] * v0[0] + uy[n] * v0[1] + uz[n] * v0[2];
        double angle = Math.atan2(sin, cos);
        for (int i = 0; i <= n; i++) {
            double a = angle * i / n;
            double c = Math.cos(a);
            double s = Math.sin(a);
            double nvx = c * vx[i] + s * ux[i];
            double nvy = c * vy[i] + s * uy[i];
            double nvz = c * vz[i] + s * uz[i];
            set(i, new double[]{nvx, nvy, nvz});
        }
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
