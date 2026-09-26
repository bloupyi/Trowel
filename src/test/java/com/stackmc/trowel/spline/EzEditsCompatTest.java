package com.stackmc.trowel.spline;

import com.stackmc.trowel.geom.NoiseSpec;
import com.stackmc.trowel.pattern.Palette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EzEditsCompatTest {

    @Test
    @DisplayName("ezEdits noise settings read in any case, Rounded return included")
    void noises() {
        for (String text : List.of("Ce(F:1.6,fO:1,cD:r,cR:r,M:OR,L:-1.1,U:-.2)", "Ce(f:1.4,z:.3,m:or,l:-1,u:-0.5)",
                "Ce(f:1.5,cr:sub,m:or,u:-0.85,l:-.5,y:0.3)", "Ce(cr:2log,cd:m4)")) {
            NoiseSpec spec = assertDoesNotThrow(() -> NoiseSpec.parse(text, 2), text);
            for (int i = 0; i < 50; i++) {
                double v = spec.sample(i * 0.37, i * 0.21, i * 0.13);
                assertTrue(v >= 0 && v < 1, text + ": " + v);
            }
        }
    }

    @Test
    @DisplayName("Mixed-case palettes, excerpts and numeric ids with repetitions")
    void palettes() {
        List<String> tokens = Palette.parseTokens("##GrayWarm(3:11),251:8*15");
        assertEquals(15, tokens.stream().filter("251:8"::equals).count());
        assertTrue(tokens.size() > 15);
        assertEquals("251:8", tokens.get(tokens.size() - 1));
    }

    @Test
    @DisplayName("The -i expressions compile, xx and yy included, and the default one shapes a tube")
    void expressions() {
        NoiseSpec spec = NoiseSpec.parse("Ce(f:1.4,z:.3,m:or,l:-1,u:-0.5)", 2);
        assertDoesNotThrow(() -> Sections.noise(spec, 3,
                "t=0.2;r=sqrt(xx+yy);m=1-abs(2*r-t-1)/abs(t-1);n<m&&r<1"));
        assertDoesNotThrow(() -> Sections.noise(spec, 0.7, "(xx+n+yy<1&&y<0)*(y+0.97)"));
        Sections.Section tube = Sections.noise(NoiseSpec.parse("perlin(f:2,z:0.5)", 2), 0.7, null);
        assertTrue(tube.eval(0, 0, 0, 0) > 0, "the core is solid");
        assertEquals(0, tube.eval(1.2, 0, 0, 0), "outside the radius is empty");
    }

    @Test
    @DisplayName("Smoothblocks profiles and overrides")
    void smoothblocks() {
        assertEquals(Smoothblocks.Profile.PANES, Smoothblocks.Spec.parse("Panes").profile());
        Smoothblocks.Spec slabs = Smoothblocks.Spec.parse("Slabs(Slab:acacia,Coverage:0.3)");
        assertEquals(Smoothblocks.Profile.SLABS, slabs.profile());
        assertEquals("acacia", slabs.overrides().get("slab"));
        assertEquals(0.3, slabs.coverage());
    }
}
