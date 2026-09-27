package com.stackmc.trowel.geom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TransformTest {

    @Test
    @DisplayName("Four quarter turns come back to the start")
    void fullTurn() {
        int[] p = {3, 7, -2};
        for (int i = 0; i < 4; i++) {
            p = Transform.ROTATE_90.apply(p[0], p[1], p[2]);
        }
        assertArrayEquals(new int[]{3, 7, -2}, p);
    }

    @Test
    @DisplayName("A quarter turn sends north to east, clockwise seen from above")
    void clockwise() {
        assertEquals("east", Transform.ROTATE_90.direction("north"));
        assertEquals("south", Transform.ROTATE_90.direction("east"));
        assertEquals("up", Transform.ROTATE_90.direction("up"));
        assertEquals("west", Transform.ROTATE_270.direction("north"));
        assertEquals("unknown", Transform.ROTATE_90.direction("unknown"));
    }

    @Test
    @DisplayName("The facing rotates with the blocks: a quarter turn adds 90 degrees")
    void yaw() {
        assertEquals(90, Transform.ROTATE_90.yaw(0), 1e-9);
        assertEquals(-90, Transform.ROTATE_90.yaw(180), 1e-9);
        assertEquals(-90, Transform.FLIP_X.yaw(90), 1e-9);
        assertEquals(-180, Transform.FLIP_Z.yaw(0), 1e-9);
    }

    @Test
    @DisplayName("Only quarter turns are rotations")
    void rotations() {
        assertEquals(Transform.ROTATE_90, Transform.rotation(90));
        assertEquals(Transform.ROTATE_270, Transform.rotation(-90));
        assertNull(Transform.rotation(45));
        assertNull(Transform.rotation(0));
    }

    @Test
    @DisplayName("Diagonals and comma-joined directions add up, one step per axis")
    void combined() {
        assertArrayEquals(new int[]{1, 0, -1}, Transform.combined("northeast"));
        assertArrayEquals(new int[]{-1, 0, 1}, Transform.combined("SW"));
        assertArrayEquals(new int[]{0, 1, -1}, Transform.combined("north,up"));
        assertArrayEquals(new int[]{1, -1, 1}, Transform.combined("se, down"));
        assertArrayEquals(new int[]{0, -1, 0}, Transform.combined("down"));
        assertNull(Transform.combined("north,south"));
        assertNull(Transform.combined("ne,east"));
        assertNull(Transform.combined("north,"));
        assertNull(Transform.combined("sideways"));
    }
}
