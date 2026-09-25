package com.stackmc.trowel.geom;

import com.stackmc.trowel.api.Keys;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorsGeometryTest {

    @Test
    @DisplayName("Each noise returns a value within [0, 1[, always the same at the same point")
    void noiseKinds() {
        for (Noise.Kind kind : Noise.Kind.values()) {
            for (int i = 0; i < 300; i++) {
                double x = i * 0.73;
                double v = Noise.sample(kind, x, -x * 0.5, x * 1.3);
                assertTrue(v >= 0 && v < 1, kind + ": " + v);
                assertEquals(v, Noise.sample(kind, x, -x * 0.5, x * 1.3), kind.name());
            }
        }
        assertEquals(Noise.Kind.CELLS, Noise.Kind.parse("voronoi"));
        assertEquals(Noise.Kind.RIDGED, Noise.Kind.parse("Ridges"));
    }

    @Test
    @DisplayName("Each noise spreads over its whole range: a pattern with several blocks uses them all")
    void noiseSpread() {
        for (Noise.Kind kind : Noise.Kind.values()) {
            double min = 1;
            double max = 0;
            for (int x = 0; x < 40; x++) {
                for (int z = 0; z < 40; z++) {
                    double v = Noise.sample(kind, x * 0.37 + 0.13, x * 0.11 + 0.5, z * 0.37 + 0.29);
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
            assertTrue(min < 0.3 && max > 0.7, kind + ": from " + min + " to " + max);
        }
    }

    @Test
    @DisplayName("Each relief gives a height within [0, 1]")
    void reliefs() {
        for (Relief relief : Relief.values()) {
            for (int x = -40; x <= 40; x += 7) {
                for (int z = -40; z <= 40; z += 7) {
                    double h = relief.height(x, z, 16, x / 40.0, z / 40.0);
                    assertTrue(h >= 0 && h <= 1, relief + ": " + h);
                }
            }
            assertEquals(relief, Relief.parse(relief.id()));
        }
    }

    @Test
    @DisplayName("A spline goes through each of its points")
    void splineThroughPoints() {
        List<double[]> points = List.of(new double[]{0, 0, 0}, new double[]{10, 5, 0}, new double[]{20, 0, 10});
        List<double[]> curve = Shapes.spline(points);
        assertArrayEquals(points.get(0), curve.get(0), 1e-9);
        assertArrayEquals(points.get(2), curve.get(curve.size() - 1), 1e-9);
        boolean passesMiddle = curve.stream().anyMatch(p -> Math.abs(p[0] - 10) < 1e-9 && Math.abs(p[1] - 5) < 1e-9);
        assertTrue(passesMiddle);
    }

    @Test
    @DisplayName("A cone thins towards the top; hollow, it has no core")
    void cone() {
        LongOpenHashSet full = new LongOpenHashSet();
        Shapes.cone(0, 0, 0, 5, 10, false, (x, y, z) -> full.add(Keys.pack(x, y, z)));
        assertTrue(full.contains(Keys.pack(5, 0, 0)));
        assertFalse(full.contains(Keys.pack(5, 8, 0)));
        assertTrue(full.contains(Keys.pack(0, 9, 0)));
        LongOpenHashSet hollow = new LongOpenHashSet();
        Shapes.cone(0, 0, 0, 5, 10, true, (x, y, z) -> hollow.add(Keys.pack(x, y, z)));
        assertTrue(full.containsAll(hollow));
        assertFalse(hollow.contains(Keys.pack(0, 1, 0)));
        assertNotNull(hollow);
    }
}
