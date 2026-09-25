package com.stackmc.trowel.geom;

import com.stackmc.trowel.api.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeysAndNoiseTest {

    @Test
    @DisplayName("A negative relative position is stored and read back")
    void keysRoundTrip() {
        long key = Keys.pack(-12, -300, 45_000);
        assertEquals(-12, Keys.x(key));
        assertEquals(-300, Keys.y(key));
        assertEquals(45_000, Keys.z(key));
        assertEquals(Keys.pack(-11, -298, 44_999), Keys.offset(key, 1, 2, -1));
    }

    @Test
    @DisplayName("The noise always returns the same value at the same point, within [0, 1[")
    void noise() {
        for (int i = 0; i < 200; i++) {
            double x = i * 0.37;
            double v = Noise.fractal(x, -x, x * 2);
            assertEquals(v, Noise.fractal(x, -x, x * 2));
            assertTrue(v >= 0 && v < 1);
            double cell = Noise.voronoi(x, 1, -x);
            assertTrue(cell >= 0 && cell < 1);
        }
    }
}
