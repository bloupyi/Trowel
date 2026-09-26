package com.stackmc.trowel.geom;

import com.stackmc.trowel.api.ParamKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MarkerTransformTest {

    private static final Map<String, ParamKind> KINDS = Map.of(
            "direction", ParamKind.DIRECTION,
            "facing", ParamKind.ANGLE,
            "path", ParamKind.PATH,
            "w", ParamKind.SIZE_X,
            "h", ParamKind.SIZE_Y,
            "d", ParamKind.SIZE_Z);

    @Test
    @DisplayName("A wind blowing east turned a quarter blows south")
    void direction() {
        Map<String, String> out = MarkerTransform.params(Map.of("direction", "east", "power", "0.2"),
                KINDS, Transform.ROTATE_90);
        assertEquals("south", out.get("direction"));
        assertEquals("0.2", out.get("power"));
    }

    @Test
    @DisplayName("The facing rotates, in degrees as in cardinal points; keep stays keep")
    void facing() {
        assertEquals("135", MarkerTransform.params(Map.of("facing", "45"), KINDS, Transform.ROTATE_90).get("facing"));
        assertEquals("east", MarkerTransform.params(Map.of("facing", "north"), KINDS, Transform.ROTATE_90).get("facing"));
        assertEquals("keep", MarkerTransform.params(Map.of("facing", "keep"), KINDS, Transform.ROTATE_90).get("facing"));
    }

    @Test
    @DisplayName("The path of a platform rotates point by point")
    void path() {
        assertEquals("0,6,0;0,0,4",
                MarkerTransform.params(Map.of("path", "0,6,0;4,0,0"), KINDS, Transform.ROTATE_90).get("path"));
        assertEquals("abc", MarkerTransform.path("abc", Transform.ROTATE_90));
    }

    @Test
    @DisplayName("Width and depth swap on a quarter turn, not on a mirror")
    void sizes() {
        Map<String, String> turned = MarkerTransform.params(Map.of("w", "5", "d", "3"), KINDS, Transform.ROTATE_90);
        assertEquals("3", turned.get("w"));
        assertEquals("5", turned.get("d"));
        Map<String, String> flipped = MarkerTransform.params(Map.of("w", "5", "d", "3"), KINDS, Transform.FLIP_X);
        assertEquals("5", flipped.get("w"));
        Map<String, String> onlyWidth = MarkerTransform.params(Map.of("w", "4"), KINDS, Transform.ROTATE_90);
        assertFalse(onlyWidth.containsKey("w"));
        assertEquals("4", onlyWidth.get("d"));
    }

    @Test
    @DisplayName("A rotated zone keeps its block and covers the same blocks with signed sizes")
    void corner() {
        assertArrayEquals(new int[]{-2, 1, 3}, MarkerTransform.spans(0, 0, 0, 3, 1, 2, Transform.ROTATE_90));
        assertArrayEquals(new int[]{-3, 1, -2}, MarkerTransform.spans(0, 5, 0, 3, 1, 2, Transform.ROTATE_180));
        assertArrayEquals(new int[]{3, 1, 2}, MarkerTransform.spans(0, 5, 0, -3, 1, -2, Transform.ROTATE_180));
        assertArrayEquals(new int[]{1, 1, 1}, MarkerTransform.spans(4, 0, 0, 1, 1, 1, Transform.FLIP_Y));
    }
}
