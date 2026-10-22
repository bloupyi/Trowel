package com.stackmc.trowel.geom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolygonTest {

    @Test
    @DisplayName("A square holds its edges and inside, nothing around")
    void square() {
        Polygon square = new Polygon(new int[]{0, 9, 9, 0}, new int[]{0, 0, 9, 9});
        assertEquals(100, square.area());
        assertTrue(square.contains(0, 0));
        assertTrue(square.contains(9, 9));
        assertTrue(square.contains(4, 5));
        assertFalse(square.contains(10, 5));
        assertFalse(square.contains(-1, 0));
    }

    @Test
    @DisplayName("An L shape leaves its notch out")
    void concave() {
        Polygon l = new Polygon(new int[]{0, 10, 10, 4, 4, 0}, new int[]{0, 0, 4, 4, 10, 10});
        assertTrue(l.contains(2, 8));
        assertTrue(l.contains(8, 2));
        assertTrue(l.contains(4, 7));
        assertFalse(l.contains(7, 7));
        assertFalse(l.contains(5, 5));
    }

    @Test
    @DisplayName("A diagonal edge leaves no gap a block could pass through")
    void diagonalEdge() {
        Polygon triangle = new Polygon(new int[]{0, 20, 0}, new int[]{0, 7, 15});
        for (int x = 0; x <= 20; x++) {
            for (int z = 0; z <= 15; z++) {
                if (triangle.contains(x, z) && triangle.contains(x + 1, z + 1)) {
                    assertTrue(triangle.contains(x + 1, z) || triangle.contains(x, z + 1), x + " " + z);
                }
                if (triangle.contains(x, z + 1) && triangle.contains(x + 1, z)) {
                    assertTrue(triangle.contains(x, z) || triangle.contains(x + 1, z + 1), x + " " + z);
                }
            }
        }
        assertTrue(triangle.contains(20, 7));
        assertTrue(triangle.contains(5, 7));
        assertFalse(triangle.contains(19, 0));
    }

    @Test
    @DisplayName("One or two points make a column or a line")
    void fewPoints() {
        assertEquals(1, new Polygon(new int[]{3}, new int[]{4}).area());
        Polygon line = new Polygon(new int[]{0, 5}, new int[]{0, 0});
        assertEquals(6, line.area());
    }
}
