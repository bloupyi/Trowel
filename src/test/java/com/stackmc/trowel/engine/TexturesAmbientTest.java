package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TexturesAmbientTest {

    @Test
    @DisplayName("Ambient: flat ground in the middle of the palette, edges towards the end, nooks towards the start")
    void ambient() {
        Box box = new Box(-10, -5, -10, 10, 10, 10);
        boolean[] solid = new boolean[box.width() * box.height() * box.depth()];
        Sculpt.Grid g = new Sculpt.Grid(box, solid, new BlockData[solid.length]);
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    g.solid[g.index(x, y, z)] = y <= 0 || (x >= 0 && x <= 4 && z >= 0 && z <= 4 && y <= 4);
                }
            }
        }
        double flat = Textures.ambient(g, -6, 0, -6, 3);
        double edge = Textures.ambient(g, 4, 4, 4, 3);
        double nook = Textures.ambient(g, -1, 0, 2, 3);
        assertEquals(0.5, flat, 1e-9);
        assertTrue(edge > 0.8, "edge " + edge);
        assertTrue(nook < 0.3, "nook " + nook);
    }
}
