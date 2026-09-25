package com.stackmc.trowel.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BoxTest {

    private final Box box = new Box(10, 5, 10, 0, 0, 0);

    @Test
    @DisplayName("Corners are put back in order")
    void normalised() {
        assertEquals(new Box(0, 0, 0, 10, 5, 10), box);
        assertEquals(11 * 6 * 11, box.volume());
    }

    @Test
    @DisplayName("Expand pushes the face of the given direction, contract pulls in the opposite face")
    void expandContract() {
        assertEquals(new Box(0, 0, 0, 10, 8, 10), box.expand(0, 3, 0));
        assertEquals(new Box(0, 0, -2, 10, 5, 10), box.expand(0, 0, -2));
        assertEquals(new Box(0, 2, 0, 10, 5, 10), box.contract(0, 2, 0));
        assertNull(box.contract(0, 10, 0));
    }

    @Test
    @DisplayName("Intersection and union")
    void intersectUnion() {
        Box other = new Box(8, 4, 8, 20, 20, 20);
        assertEquals(new Box(8, 4, 8, 10, 5, 10), box.intersect(other));
        assertEquals(new Box(0, 0, 0, 20, 20, 20), box.union(other));
        assertNull(box.intersect(new Box(50, 50, 50, 60, 60, 60)));
    }
}
