package com.stackmc.trowel.brush;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Faceted polyhedra: the Arceon rock is an icosahedron with jostled vertices. */
final class Polyhedra {

    private static final double PHI = (1 + Math.sqrt(5)) / 2;
    private static final int[][] ICOSAHEDRON = {
            {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6},
            {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11}, {6, 2, 10},
            {8, 6, 7}, {9, 8, 1}};

    private Polyhedra() {
    }

    /**
     * The faces of a rock of radius 1: each is {@code {ax, ay, az, nx, ny, nz}}, a vertex and
     * the outward normal.
     *
     * @param subdivisions 0 to 3: the more, the rounder the rock
     * @param irregular    0 to 1: how far the vertices stray from the sphere
     */
    static List<double[]> boulder(int subdivisions, double irregular) {
        List<double[]> vertices = new ArrayList<>();
        double[][] base = {{-1, PHI, 0}, {1, PHI, 0}, {-1, -PHI, 0}, {1, -PHI, 0}, {0, -1, PHI}, {0, 1, PHI},
                {0, -1, -PHI}, {0, 1, -PHI}, {PHI, 0, -1}, {PHI, 0, 1}, {-PHI, 0, -1}, {-PHI, 0, 1}};
        for (double[] v : base) {
            vertices.add(normalize(v));
        }
        List<int[]> faces = new ArrayList<>(List.of(ICOSAHEDRON));
        for (int level = 0; level < subdivisions; level++) {
            Map<Long, Integer> middles = new HashMap<>();
            List<int[]> next = new ArrayList<>();
            for (int[] f : faces) {
                int a = middle(vertices, middles, f[0], f[1]);
                int b = middle(vertices, middles, f[1], f[2]);
                int c = middle(vertices, middles, f[2], f[0]);
                next.add(new int[]{f[0], a, c});
                next.add(new int[]{f[1], b, a});
                next.add(new int[]{f[2], c, b});
                next.add(new int[]{a, b, c});
            }
            faces = next;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (double[] v : vertices) {
            double factor = 1 + irregular * (random.nextDouble() - 0.6) * 0.8;
            v[0] *= factor;
            v[1] *= factor;
            v[2] *= factor;
        }
        List<double[]> out = new ArrayList<>();
        for (int[] f : faces) {
            double[] a = vertices.get(f[0]);
            double[] b = vertices.get(f[1]);
            double[] c = vertices.get(f[2]);
            double ux = b[0] - a[0];
            double uy = b[1] - a[1];
            double uz = b[2] - a[2];
            double vx = c[0] - a[0];
            double vy = c[1] - a[1];
            double vz = c[2] - a[2];
            double nx = uy * vz - uz * vy;
            double ny = uz * vx - ux * vz;
            double nz = ux * vy - uy * vx;
            if (nx * a[0] + ny * a[1] + nz * a[2] < 0) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            out.add(new double[]{a[0], a[1], a[2], nx, ny, nz});
        }
        return out;
    }

    /** Inside if the point is below every face. */
    static boolean inside(List<double[]> faces, double x, double y, double z) {
        for (double[] f : faces) {
            if ((x - f[0]) * f[3] + (y - f[1]) * f[4] + (z - f[2]) * f[5] > 0) {
                return false;
            }
        }
        return true;
    }

    private static int middle(List<double[]> vertices, Map<Long, Integer> middles, int i, int j) {
        long key = ((long) Math.min(i, j) << 32) | Math.max(i, j);
        Integer known = middles.get(key);
        if (known != null) {
            return known;
        }
        double[] a = vertices.get(i);
        double[] b = vertices.get(j);
        vertices.add(normalize(new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2}));
        middles.put(key, vertices.size() - 1);
        return vertices.size() - 1;
    }

    private static double[] normalize(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / len, v[1] / len, v[2] / len};
    }
}
