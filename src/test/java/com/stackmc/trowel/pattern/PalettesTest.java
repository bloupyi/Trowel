package com.stackmc.trowel.pattern;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PalettesTest {

    @Test
    @DisplayName("Every block of every palette exists")
    void everyBlockExists() {
        Palettes.ALL.forEach((name, list) -> {
            for (String token : Tokens.split(list, ',')) {
                String id = token.replaceFirst("^\\d+(\\.\\d+)?%", "");
                Material material = Material.matchMaterial(id);
                assertNotNull(material, name + ": " + id);
            }
        });
    }

    @Test
    @DisplayName("A palette fits in a pattern, even in the middle of a noise")
    void expands() {
        assertEquals("#noise:cracks:6:ice,packed_ice,blue_ice", Palettes.expand("#noise:cracks:6:#palette:ice"));
        assertEquals("stone", Palettes.expand("stone"));
        assertThrows(IllegalArgumentException.class, () -> Palettes.expand("#palette:unknown"));
    }
}
