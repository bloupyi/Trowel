package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TexturesAmbientTest {

    interface Shape {
        boolean solid(int x, int y, int z);
    }

    private static Sculpt.Grid grid(Shape shape) {
        Box box = new Box(-12, -6, -12, 12, 12, 12);
        boolean[] solid = new boolean[box.width() * box.height() * box.depth()];
        Sculpt.Grid g = new Sculpt.Grid(box, solid, new BlockData[solid.length]);
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    g.solid[g.index(x, y, z)] = shape.solid(x, y, z);
                }
            }
        }
        return g;
    }

    @Test
    @DisplayName("Ambient: flat ground in the middle of the palette, edges towards the end, nooks towards the start")
    void ambient() {
        Sculpt.Grid g = grid((x, y, z) -> y <= 0 || (x >= 0 && x <= 4 && z >= 0 && z <= 4 && y <= 4));
        double flat = Textures.ambient(g, -6, 0, -6, 3);
        double edge = Textures.ambient(g, 4, 4, 4, 3);
        double nook = Textures.ambient(g, -1, 0, 2, 3);
        assertEquals(0.5, flat, 1e-9);
        assertTrue(edge > 0.8, "edge " + edge);
        assertTrue(nook < 0.3, "nook " + nook);
    }

    @Test
    @DisplayName("Ambient: the face of a thick wall is as flat as the ground")
    void thickWall() {
        Sculpt.Grid g = grid((x, y, z) -> x <= 0);
        assertEquals(0.5, Textures.ambient(g, 0, 3, 0, 3), 1e-9);
    }

    @Test
    @DisplayName("Ambient: a thin wall is flat on both faces, its top is an edge, its foot a nook")
    void thinWall() {
        for (int thickness = 1; thickness <= 2; thickness++) {
            int t = thickness;
            Sculpt.Grid g = grid((x, y, z) -> y <= 0 || (x >= 0 && x < t && y <= 8));
            double face = Textures.ambient(g, 0, 4, 0, 3);
            double back = Textures.ambient(g, t - 1, 4, 0, 3);
            double top = Textures.ambient(g, 0, 8, 0, 3);
            double foot = Textures.ambient(g, -1, 0, 0, 3);
            assertEquals(0.5, face, 0.05, "face, thickness " + t);
            assertEquals(face, back, 1e-9, "back, thickness " + t);
            assertTrue(top > 0.8, "top " + top + ", thickness " + t);
            assertTrue(foot < 0.3, "foot " + foot + ", thickness " + t);
        }
    }

    @Test
    @DisplayName("Ambient: the same wall gets the same value whichever way it faces")
    void orientation() {
        Sculpt.Grid east = grid((x, y, z) -> y <= 0 || (x <= 0 && y <= 8));
        Sculpt.Grid west = grid((x, y, z) -> y <= 0 || (x >= 0 && y <= 8));
        Sculpt.Grid south = grid((x, y, z) -> y <= 0 || (z <= 0 && y <= 8));
        for (int y = 1; y <= 8; y++) {
            double e = Textures.ambient(east, 0, y, 0, 3);
            assertEquals(e, Textures.ambient(west, 0, y, 0, 3), 1e-9, "y " + y);
            assertEquals(e, Textures.ambient(south, 0, y, 0, 3), 1e-9, "y " + y);
        }
    }

    @Test
    @DisplayName("Ambient: two walls meeting give an edge outside the corner and a nook inside")
    void corner() {
        Sculpt.Grid g = grid((x, y, z) -> (x >= 0 && x <= 1 && z >= 0) || (z >= 0 && z <= 1 && x >= 0));
        double outside = Textures.ambient(g, 0, 3, 0, 3);
        double inside = Textures.ambient(g, 2, 3, 1, 3);
        double face = Textures.ambient(g, 0, 3, 8, 3);
        assertTrue(outside > 0.8, "outside " + outside);
        assertTrue(inside < 0.3, "inside " + inside);
        assertEquals(0.5, face, 0.05, "face");
    }

    @Test
    @DisplayName("Ambient: a ceiling is as flat as the ground")
    void ceiling() {
        Sculpt.Grid g = grid((x, y, z) -> y >= 5);
        assertEquals(0.5, Textures.ambient(g, 0, 5, 0, 3), 1e-9);
    }
}
