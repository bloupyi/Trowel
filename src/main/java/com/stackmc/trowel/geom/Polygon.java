package com.stackmc.trowel.geom;

import java.util.BitSet;

/**
 * A polygon seen from above, its vertices on blocks, rasterized once into columns.
 *
 * <p>A column is inside when its center is inside the polygon, or when an edge passes through
 * it: the clicked blocks and the edges between them always belong to the selection, without a
 * gap even on a diagonal. The polygon closes by itself, from the last point to the first.</p>
 */
public final class Polygon {

    public static final int MAX_POINTS = 100;

    private final int minX;
    private final int minZ;
    private final int width;
    private final int depth;
    private final BitSet inside;

    public Polygon(int[] xs, int[] zs) {
        if (xs.length == 0 || xs.length != zs.length) {
            throw new IllegalArgumentException("A polygon needs points.");
        }
        int loX = Integer.MAX_VALUE, hiX = Integer.MIN_VALUE, loZ = Integer.MAX_VALUE, hiZ = Integer.MIN_VALUE;
        for (int i = 0; i < xs.length; i++) {
            loX = Math.min(loX, xs[i]);
            hiX = Math.max(hiX, xs[i]);
            loZ = Math.min(loZ, zs[i]);
            hiZ = Math.max(hiZ, zs[i]);
        }
        minX = loX;
        minZ = loZ;
        width = hiX - loX + 1;
        depth = hiZ - loZ + 1;
        inside = new BitSet(width * depth);
        int n = xs.length;
        if (n >= 3) {
            double[] crossings = new double[n];
            for (int z = loZ; z <= hiZ; z++) {
                int count = 0;
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    // Half open: a vertex on the row counts for one of its two edges only.
                    if ((zs[i] <= z && z < zs[j]) || (zs[j] <= z && z < zs[i])) {
                        crossings[count++] = xs[i] + (double) (z - zs[i]) * (xs[j] - xs[i]) / (zs[j] - zs[i]);
                    }
                }
                java.util.Arrays.sort(crossings, 0, count);
                for (int k = 0; k + 1 < count; k += 2) {
                    for (int x = (int) Math.ceil(crossings[k]); x <= (int) Math.floor(crossings[k + 1]); x++) {
                        set(x, z);
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            edge(xs[i], zs[i], xs[j], zs[j]);
        }
    }

    /** The columns of a segment, four-connected: a diagonal edge leaves no hole a block could pass. */
    private void edge(int x1, int z1, int x2, int z2) {
        int dx = Math.abs(x2 - x1);
        int dz = Math.abs(z2 - z1);
        int sx = Integer.signum(x2 - x1);
        int sz = Integer.signum(z2 - z1);
        int x = x1;
        int z = z1;
        set(x, z);
        for (int ix = 0, iz = 0; ix < dx || iz < dz; ) {
            // Step along the axis whose next block border the segment crosses first.
            if ((long) (1 + 2 * ix) * dz < (long) (1 + 2 * iz) * dx) {
                x += sx;
                ix++;
            } else {
                z += sz;
                iz++;
            }
            set(x, z);
        }
    }

    private void set(int x, int z) {
        inside.set((x - minX) * depth + (z - minZ));
    }

    public boolean contains(int x, int z) {
        int i = x - minX;
        int k = z - minZ;
        return i >= 0 && k >= 0 && i < width && k < depth && inside.get(i * depth + k);
    }

    /** Columns inside. */
    public int area() {
        return inside.cardinality();
    }
}
