package com.stackmc.trowel.spline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DNA example of the guide: two strands, rungs, base pairs in four colors. */
class DnaExpressionTest {

    static final String DNA = "k=z*1.05;c=cos(k);v=sin(k);u=x*c+y*v;n=floor(z/0.6);"
            + "p=floor(fract(sin(n*78.2)*4e4)*4);b=u>0?p+2:p+2+(p%2?-1:1);"
            + "r*r+0.5625-1.5*abs(u)<0.06?(u>0?0.08:0.25):"
            + "(fract(z/0.6)<0.3&&abs(x*v-y*c)<0.15&&abs(u)<0.75?(b+0.5)/6:0)";

    private static final Sections.Section SECTION = Sections.expression(DNA, false, 1.5, 100);

    /** The block number in the palette of six (0 to 5), or -1 for nothing. */
    private static int block(double x, double y, double z) {
        double v = SECTION.eval(x, y, z, 0);
        return v > 0 ? (int) Math.ceil(Math.min(1, v) * 6) - 1 : -1;
    }

    @Test
    void fitsInTheChat() {
        String command = "//spline expr ##dna 6 " + DNA;
        assertTrue(command.length() <= 256, command.length() + " characters");
    }

    @Test
    void strandsTurnAroundTheAxis() {
        assertEquals(0, block(0.75, 0, 0));
        assertEquals(1, block(-0.75, 0, 0));
        double z = Math.PI / 2 / 1.05;
        assertEquals(0, block(0, 0.75, z));
        assertEquals(1, block(0, -0.75, z));
        assertEquals(-1, block(0.75, 0, z));
    }

    @Test
    void rungsJoinTheStrandsWithPairedBases() {
        int left = block(0.3, 0, 0.1);
        int right = block(-0.3, 0, 0.1);
        assertTrue(left >= 2 && left <= 5, "base " + left);
        assertTrue(right >= 2 && right <= 5, "base " + right);
        assertEquals(left % 2 == 0 ? left + 1 : left - 1, right);
        assertEquals(-1, block(0, 0.6, 0.4));
        assertEquals(-1, block(0.3, 0, 0.4));
    }

    @Test
    void baseColorsChangeFromRungToRung() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int rung = 0; rung < 40; rung++) {
            double z = rung * 0.6 + 0.05;
            double k = z * 1.05;
            seen.add(block(0.3 * Math.cos(k), 0.3 * Math.sin(k), z));
        }
        assertTrue(seen.size() >= 3, "colors " + seen);
    }
}
