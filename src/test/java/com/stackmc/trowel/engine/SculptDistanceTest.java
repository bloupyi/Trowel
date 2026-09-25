package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SculptDistanceTest {

    private static Sculpt.Grid grid(Box box, double fill, long seed) {
        int n = box.width() * box.height() * box.depth();
        boolean[] solid = new boolean[n];
        Random random = new Random(seed);
        for (int i = 0; i < n; i++) {
            solid[i] = random.nextDouble() < fill;
        }
        return new Sculpt.Grid(box, solid, new BlockData[n]);
    }

    private static double brute(Sculpt.Grid g, int i, boolean toSolid) {
        double best = Double.MAX_VALUE;
        for (int j = 0; j < g.solid.length; j++) {
            if (g.solid[j] == toSolid) {
                best = Math.min(best, g.distance(i, j));
            }
        }
        return best;
    }

    @Test
    void nearestIsExactEuclidean() {
        Box box = new Box(-3, 10, 5, 8, 17, 14);
        for (double fill : new double[]{0.02, 0.3, 0.9}) {
            Sculpt.Grid g = grid(box, fill, (long) (fill * 1000));
            for (boolean toSolid : new boolean[]{true, false}) {
                int[] seeds = g.nearest(toSolid, 1000, new Progress());
                for (int i = 0; i < seeds.length; i++) {
                    double expected = brute(g, i, toSolid);
                    if (expected == Double.MAX_VALUE) {
                        assertEquals(-1, seeds[i]);
                        continue;
                    }
                    assertTrue(seeds[i] >= 0, "cell " + i + " without a source");
                    assertEquals(toSolid, g.solid[seeds[i]]);
                    assertEquals(expected, g.distance(i, seeds[i]), 1e-9, "cell " + i);
                }
            }
        }
    }

    @Test
    void nearestRespectsTheLimit() {
        Box box = new Box(0, 0, 0, 20, 0, 0);
        boolean[] solid = new boolean[21];
        solid[0] = true;
        Sculpt.Grid g = new Sculpt.Grid(box, solid, new BlockData[21]);
        int[] seeds = g.nearest(true, 5, new Progress());
        for (int x = 0; x <= 20; x++) {
            assertEquals(x <= 5 ? 0 : -1, seeds[x], "x=" + x);
        }
    }

    @Test
    void emptyGridHasNoSource() {
        Box box = new Box(0, 0, 0, 4, 4, 4);
        Sculpt.Grid g = new Sculpt.Grid(box, new boolean[125], new BlockData[125]);
        for (int seed : g.nearest(true, 100, new Progress())) {
            assertEquals(-1, seed);
        }
    }
}
