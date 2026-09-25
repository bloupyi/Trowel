package com.stackmc.trowel.brush;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetailBrushesTest {

    @Test
    void detailBrushesHaveSensibleDefaults() {
        assertEquals("vine", BrushSettings.defaults(BrushType.IVY, null).pattern());
        assertEquals("pointed_dripstone", BrushSettings.defaults(BrushType.STALACTITE, null).pattern());
        assertEquals("air", BrushSettings.defaults(BrushType.CRACKS, null).pattern());
        assertTrue(BrushSettings.defaults(BrushType.SCREE, null).pattern().contains("gravel"));
        assertEquals(8, BrushSettings.defaults(BrushType.IVY, null).height());
        assertEquals(3, BrushSettings.defaults(BrushType.CRACKS, null).iterations());
    }

    @Test
    void detailBrushesAreFoundByTheirNames() {
        assertEquals(BrushType.IVY, BrushType.parse("vines"));
        assertEquals(BrushType.STALACTITE, BrushType.parse("dripstone"));
        assertEquals(BrushType.CRACKS, BrushType.parse("crack"));
        assertEquals(BrushType.SCREE, BrushType.parse("rubble"));
    }

    @Test
    void labelsFollowTheBrush() {
        assertEquals("Amount", BrushType.SCREE.label(BrushType.Setting.INTENSITY));
        assertEquals("Number of cracks", BrushType.CRACKS.label(BrushType.Setting.ITERATIONS));
    }
}
