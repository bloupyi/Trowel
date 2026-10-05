package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Keys;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipboardRotationTest {

    private static Long2ObjectOpenHashMap<String> wall(int length) {
        Long2ObjectOpenHashMap<String> cells = new Long2ObjectOpenHashMap<>();
        for (int x = 0; x < length; x++) {
            cells.put(Keys.pack(x, 0, 0), "stone");
            cells.put(Keys.pack(x, 1, 0), "stone");
        }
        return cells;
    }

    @Test
    @DisplayName("A wall turned by 45 degrees becomes a diagonal without holes")
    void diagonal() {
        Long2ObjectOpenHashMap<String> turned = Clipboard.turnFreely(wall(10), 45);
        assertTrue(turned.containsKey(Keys.pack(0, 0, 0)));
        assertTrue(turned.containsKey(Keys.pack(6, 1, 6)));
        for (int i = 0; i <= 6; i++) {
            assertTrue(turned.containsKey(Keys.pack(i, 0, i)), "diagonal block " + i);
        }
        assertTrue(turned.size() >= 2 * 7);
    }

    @Test
    @DisplayName("A small angle keeps the wall's length and tilts its end clockwise")
    void smallAngle() {
        Long2ObjectOpenHashMap<String> turned = Clipboard.turnFreely(wall(20), 20);
        long end = Clipboard.turnPoint(Keys.pack(19, 0, 0), 20);
        assertEquals(18, Keys.x(end));
        assertEquals(6, Keys.z(end));
        assertTrue(turned.keySet().longStream().anyMatch(k -> Keys.y(k) == 0
                && Math.abs(Keys.x(k) - 18) <= 1 && Math.abs(Keys.z(k) - 6) <= 1));
        assertTrue(turned.values().stream().allMatch("stone"::equals));
    }

    @Test
    @DisplayName("Zero degrees changes nothing")
    void zero() {
        Long2ObjectOpenHashMap<String> cells = wall(5);
        assertEquals(cells, Clipboard.turnFreely(cells, 0));
    }
}
