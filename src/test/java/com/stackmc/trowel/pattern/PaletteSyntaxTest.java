package com.stackmc.trowel.pattern;

import com.stackmc.trowel.geom.NoiseSpec;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteSyntaxTest {

    @Test
    @DisplayName("ezEdits palettes: sequence, reversal, excerpt, repetition, group, block states")
    void modifiers() {
        assertEquals(List.of("stone", "dirt"), Palette.parseTokens("stone,dirt"));
        assertEquals(List.of("dirt", "stone"), Palette.parseTokens("-[stone,dirt]"));
        assertEquals(List.of("gold_block", "gold_block", "gold_block", "diamond_block"),
                Palette.parseTokens("gold_block*3,diamond_block"));
        List<String> grayscale = Palette.parseTokens("##grayscale");
        assertEquals(grayscale.subList(2, 5), Palette.parseTokens("##grayscale(3:5)"));
        List<String> reversed = Palette.parseTokens("-##grayscale");
        assertEquals(grayscale.get(0), reversed.get(reversed.size() - 1));
        assertEquals(List.of("oak_stairs[facing=north,half=top]", "stone"),
                Palette.parseTokens("oak_stairs[facing=north,half=top],stone"));
        assertEquals("gold_block", Palette.parseTokens("-[##magma,gold_block]").get(0));
        assertEquals(6, Palette.parseTokens("60%stone").size());
        assertThrows(IllegalArgumentException.class, () -> Palette.parseTokens("##unknown"));
        assertThrows(IllegalArgumentException.class, () -> Palette.parseTokens("[stone"));
    }

    @Test
    @DisplayName("Every block of the ready-made palettes exists")
    void presetsExist() {
        for (String name : Palette.presets()) {
            for (String block : Palette.parseTokens("##" + name)) {
                Material material = Material.matchMaterial(block);
                assertNotNull(material, "##" + name + ": " + block);
            }
        }
    }

    @Test
    @DisplayName("Bracket arguments, nested; splitting that respects brackets and parentheses")
    void specials() {
        Special s = Special.read("#noise[-[##magma,gold_block]][cellular(cr:edge,f:0.2)][2]");
        assertNotNull(s);
        assertEquals("noise", s.name());
        assertEquals(List.of("-[##magma,gold_block]", "cellular(cr:edge,f:0.2)", "2"), s.args());
        assertNull(Special.read("#noise:8:stone"));
        assertNull(Special.read("##magma"));
        assertThrows(IllegalArgumentException.class, () -> Special.read("#noise[a]b"));
        assertEquals(List.of("#near[stone,dirt][3]", "!air"), Special.split("#near[stone,dirt][3] !air", " &"));
    }

    @Test
    @DisplayName("Tuned noises: bounds, settings, clear errors")
    void noiseSpecs() {
        for (String text : List.of("perlin(f:0.1,ft:ridged,fo:5)", "cellular(cr:edge,cj:0.7,cd:man)",
                "gabor(gf:3,go:45)", "value(i:true,st:4)", "marble(f:0.2,wa:6)", "valuecubic(l:-0.5,u:0.5)",
                "perlin(ft:pingpong,fp:3)", "white", "cells(f:0.3)")) {
            NoiseSpec spec = NoiseSpec.parse(text, 0.1);
            for (int i = 0; i < 200; i++) {
                double v = spec.sample(i * 1.7, i * 0.3, -i * 0.9);
                assertTrue(v >= 0 && v < 1, text + " -> " + v);
            }
        }
        NoiseSpec a = NoiseSpec.parse("perlin(s:4)", 0.1);
        NoiseSpec b = NoiseSpec.parse("perlin(s:5)", 0.1);
        boolean differs = false;
        for (int i = 0; i < 20; i++) {
            differs |= a.sample(i, i, i) != b.sample(i, i, i);
        }
        assertTrue(differs, "two seeds give two noises");
        assertEquals(a.sample(3, 4, 5), NoiseSpec.parse("perlin(s:4)", 0.1).sample(3, 4, 5));
        assertThrows(IllegalArgumentException.class, () -> NoiseSpec.parse("perlin(zz:1)", 0.1));
        assertThrows(IllegalArgumentException.class, () -> NoiseSpec.parse("unknown", 0.1));
        assertThrows(IllegalArgumentException.class, () -> NoiseSpec.parse("perlin(f:abc)", 0.1));
    }
}
