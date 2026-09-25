package com.stackmc.trowel.brush;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrushSettingsTest {

    @Test
    @DisplayName("A brush is stored in the item and read back as is, block states included")
    void roundTrip() {
        BrushSettings settings = new BrushSettings(BrushType.PAINT, 5, 2, 3, 60, 40, 2, "melt",
                "70%oak_slab[type=top],30%stone", "#surface & !#marker", true, false, "plateau", "ridged");
        assertEquals(settings, BrushSettings.decode(settings.encode()));
    }

    @Test
    @DisplayName("Unreadable content gives no brush; crazy values are clamped")
    void robustness() {
        assertNull(BrushSettings.decode("type=unknown"));
        assertNull(BrushSettings.decode(""));
        BrushSettings wild = new BrushSettings(BrushType.SPHERE, 500, 0, 0, 0, 300, 99, "x", "stone", "", false, false, "?", "?")
                .clamp(16);
        assertEquals(16, wild.size());
        assertEquals(1, wild.chance());
        assertEquals(100, wild.falloff());
        assertEquals("melt", wild.preset());
        assertEquals("dome", wild.profile());
        assertEquals("fractal", wild.noise());
    }

    @Test
    @DisplayName("A setting the type does not use stays neutral: flatten at 100% leaves no hole")
    void effectiveSettings() {
        BrushSettings flatten = new BrushSettings(BrushType.FLATTEN, 4, 3, 2, 100, 40, 2, "melt", "stone",
                "", true, true, "both", "fractal").effective();
        assertEquals(100, flatten.chance());
        assertEquals(0, flatten.falloff());
        assertEquals("", flatten.pattern());
        assertFalse(flatten.surface());
        assertFalse(flatten.random());
        BrushSettings paint = new BrushSettings(BrushType.PAINT, 4, 3, 2, 70, 40, 2, "melt", "stone",
                "#surface", true, false, "dome", "fractal").effective();
        assertEquals(70, paint.chance());
        assertEquals(40, paint.falloff());
        assertEquals("#surface", paint.mask());
    }

    @Test
    @DisplayName("Flatten has its modes, the others their shapes; a foreign shape falls back to the default")
    void profiles() {
        assertEquals(List.of("both", "fill", "cut"), BrushSettings.profilesFor(BrushType.FLATTEN));
        assertEquals("both", BrushSettings.defaults(BrushType.FLATTEN, null).profile());
        assertEquals("dome", BrushSettings.defaults(BrushType.RAISE, null).profile());
        assertEquals("both", BrushSettings.defaults(BrushType.RAISE, null).withProfile("dome")
                .withType(BrushType.FLATTEN).profile());
        assertEquals("both", new BrushSettings(BrushType.FLATTEN, 3, 3, 2, 100, 0, 1, "melt", "", "", false,
                false, "plateau", "fractal").clamp(16).profile());
        assertEquals("", BrushSettings.defaults(BrushType.RAISE, null).pattern());
        assertEquals("stone", BrushSettings.defaults(BrushType.SPHERE, null).pattern());
    }

    @Test
    @DisplayName("Changing type keeps what is shared, and resets what would change meaning")
    void switchType() {
        BrushSettings paint = BrushSettings.defaults(BrushType.PAINT, "oak_planks").withSize(6)
                .withMask("#surface");
        BrushSettings sphere = paint.withType(BrushType.SPHERE);
        assertEquals(6, sphere.size());
        assertEquals("oak_planks", sphere.pattern());
        assertEquals("#surface", sphere.mask());
        assertEquals(0, sphere.falloff());
        assertEquals(100, sphere.chance());

        BrushSettings splatter = BrushSettings.defaults(BrushType.SPLATTER, "stone");
        assertEquals(100, splatter.withType(BrushType.CUBE).chance());

        BrushSettings spike = BrushSettings.defaults(BrushType.SPIKE, "stone");
        assertEquals(1, spike.withType(BrushType.OVERLAY).height());

        assertEquals("", BrushSettings.defaults(BrushType.LOWER, "stone").withType(BrushType.RAISE).pattern());
        assertEquals("stone", BrushSettings.defaults(BrushType.RAISE, null).withType(BrushType.SPHERE).pattern());

        BrushSettings custom = new BrushSettings(BrushType.SPHERE, 5, 3, 2, 60, 30, 1, "melt", "stone", "",
                false, false, "dome", "fractal");
        BrushSettings cylinder = custom.withType(BrushType.CYLINDER);
        assertEquals(60, cylinder.chance());
        assertEquals(30, cylinder.falloff());
        assertEquals(custom, custom.withType(BrushType.SPHERE));
    }

    @Test
    @DisplayName("WorldEdit and VoxelSniper names lead to the right brush")
    void aliases() {
        assertEquals(BrushType.SPHERE, BrushType.parse("ball"));
        assertEquals(BrushType.CYLINDER, BrushType.parse("cyl"));
        assertEquals(BrushType.STAMP, BrushType.parse("clipboard"));
        assertEquals(BrushType.PAINT, BrushType.parse("Paint"));
        assertTrue(BrushType.STAMP.uses(BrushType.Setting.RANDOM));
    }
}
