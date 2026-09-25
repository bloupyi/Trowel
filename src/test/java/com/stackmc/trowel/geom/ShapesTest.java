package com.stackmc.trowel.geom;

import com.stackmc.trowel.api.Keys;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapesTest {

    private static LongOpenHashSet collect(java.util.function.Consumer<Shapes.Cell> shape) {
        LongOpenHashSet cells = new LongOpenHashSet();
        shape.accept((x, y, z) -> cells.add(Keys.pack(x, y, z)));
        return cells;
    }

    @Test
    @DisplayName("A sphere of radius 2 is five blocks wide, as in WorldEdit")
    void sphereWidth() {
        LongOpenHashSet sphere = collect(out -> Shapes.ellipsoid(0, 0, 0, 2, 2, 2, false, out));
        assertTrue(sphere.contains(Keys.pack(2, 0, 0)));
        assertTrue(sphere.contains(Keys.pack(-2, 0, 0)));
        assertFalse(sphere.contains(Keys.pack(3, 0, 0)));
        assertEquals(1, collect(out -> Shapes.ellipsoid(4, 4, 4, 0, 0, 0, false, out)).size());
    }

    @Test
    @DisplayName("A hollow sphere only has its skin")
    void hollowSphere() {
        LongOpenHashSet full = collect(out -> Shapes.ellipsoid(0, 0, 0, 4, 4, 4, false, out));
        LongOpenHashSet hollow = collect(out -> Shapes.ellipsoid(0, 0, 0, 4, 4, 4, true, out));
        assertTrue(full.containsAll(hollow));
        assertFalse(hollow.contains(Keys.pack(0, 0, 0)));
        assertTrue(hollow.contains(Keys.pack(4, 0, 0)));
    }

    @Test
    @DisplayName("A cylinder stacks its disc, downwards if the height is negative")
    void cylinder() {
        LongOpenHashSet disk = collect(out -> Shapes.cylinder(0, 0, 0, 3, 3, 1, false, out));
        LongOpenHashSet tall = collect(out -> Shapes.cylinder(0, 0, 0, 3, 3, 4, false, out));
        assertEquals(disk.size() * 4, tall.size());
        LongOpenHashSet down = collect(out -> Shapes.cylinder(0, 0, 0, 1, 1, -3, false, out));
        assertTrue(down.contains(Keys.pack(0, -2, 0)));
        assertFalse(down.contains(Keys.pack(0, 1, 0)));
    }

    @Test
    @DisplayName("A pyramid of three counts 25 + 9 + 1 blocks, hollow 16 + 8 + 1")
    void pyramid() {
        assertEquals(35, collect(out -> Shapes.pyramid(0, 0, 0, 3, false, out)).size());
        assertEquals(25, collect(out -> Shapes.pyramid(0, 0, 0, 3, true, out)).size());
    }

    @Test
    @DisplayName("A rope hangs in the middle, an arch rises, a line stays straight")
    void curves() {
        List<double[]> rope = Shapes.curve(0, 10, 0, 20, 10, 0, -4);
        List<double[]> arch = Shapes.curve(0, 10, 0, 20, 10, 0, 4);
        double[] ropeMiddle = rope.get(rope.size() / 2);
        double[] archMiddle = arch.get(arch.size() / 2);
        assertEquals(10.5 - 4, ropeMiddle[1], 0.2);
        assertEquals(10.5 + 4, archMiddle[1], 0.2);
        assertEquals(10.5, rope.get(0)[1], 1e-9);
        assertEquals(10.5, rope.get(rope.size() - 1)[1], 1e-9);
    }

    @Test
    @DisplayName("Thickening never places the same block twice")
    void thickenDeduplicates() {
        List<double[]> points = Shapes.curve(0, 0, 0, 0, 0, 0, 0);
        int[] calls = {0};
        Shapes.thicken(points, 1, (x, y, z) -> calls[0]++);
        assertEquals(1, calls[0]);
    }
}
