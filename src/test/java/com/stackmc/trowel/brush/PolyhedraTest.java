package com.stackmc.trowel.brush;

import com.stackmc.trowel.pattern.Colors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolyhedraTest {

    @Test
    @DisplayName("A rock contains its center, not the outside of its sphere, and gains faces when subdivided")
    void boulder() {
        for (int level = 0; level <= 3; level++) {
            List<double[]> faces = Polyhedra.boulder(level, 0.5);
            assertEquals(20 * (int) Math.pow(4, level), faces.size());
            assertTrue(Polyhedra.inside(faces, 0, 0, 0));
            assertTrue(Polyhedra.inside(faces, 0.2, -0.1, 0.1));
            assertFalse(Polyhedra.inside(faces, 1.5, 0, 0));
            assertFalse(Polyhedra.inside(faces, 0, -1.4, 0.2));
        }
        List<double[]> round = Polyhedra.boulder(3, 0);
        assertTrue(Polyhedra.inside(round, 0.9, 0, 0));
        assertFalse(Polyhedra.inside(round, 1.02, 0, 0));
    }

    @Test
    @DisplayName("Colors: hexadecimal, components, dye names; zero distance to itself")
    void colors() {
        assertEquals(0xFF8800, Colors.parse("#ff8800"));
        assertEquals(0xFF8800, Colors.parse("255,136,0"));
        assertEquals(0x00FF00, Colors.parse("00ff00"));
        assertTrue(Colors.parse("red") > 0);
        assertEquals(0, Colors.distance(0x123456, 0x123456), 1e-9);
        assertTrue(Colors.distance(0xFF0000, 0x00FF00) > Colors.distance(0xFF0000, 0xEE1100));
        assertThrows(IllegalArgumentException.class, () -> Colors.parse("not a color"));
    }
}
