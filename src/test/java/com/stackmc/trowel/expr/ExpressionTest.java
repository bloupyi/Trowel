package com.stackmc.trowel.expr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionTest {

    private static double eval(String source, double... xyz) {
        return Expression.compile(source, "x", "y", "z").evaluate(xyz);
    }

    @Test
    @DisplayName("Precedence: power before unary minus, right associative; product before sum")
    void precedence() {
        assertEquals(7, eval("1 + 2 * 3"));
        assertEquals(-4, eval("-2^2"));
        assertEquals(512, eval("2^3^2"));
        assertEquals(0.5, eval("2^-1"));
        assertEquals(8, eval("2**3"));
        assertEquals(1, eval("7 % 3"));
        assertEquals(20, eval("(1 + 3) * 5"));
        assertEquals(12, eval("3 << 2"));
    }

    @Test
    @DisplayName("True means positive; comparisons, logic and ternary")
    void logic() {
        assertEquals(1, eval("x*x + y*y < 1", 0.5, 0.5, 0));
        assertEquals(0, eval("x*x + y*y < 1", 1, 1, 0));
        assertEquals(1, eval("x > 0 && y > 0 || z > 0", -1, -1, 3));
        assertEquals(0, eval("!(1)"));
        assertEquals(5, eval("x > 0 ? 5 : 6", 1, 0, 0));
        assertEquals(6, eval("x > 0 ? 5 : 6", 0, 0, 0));
        assertEquals(1, eval("0.1 + 0.2 ~= 0.3"));
    }

    @Test
    @DisplayName("Local variables, compound assignments, and modifiable inputs")
    void variables() {
        assertEquals(9, eval("r = x + 1; r *= 3; r", 2, 0, 0));
        assertEquals(4, eval("x += 2; x", 2, 0, 0));
        assertEquals(3, eval("a = b = 3; a"));
        assertEquals(2, eval("i = 1; i++; i"));
        Expression e = Expression.compile("t = t + 1; t", "x");
        assertEquals(1, e.evaluate(0));
        assertEquals(1, e.evaluate(0), "locals start again from zero");
        assertEquals(Math.PI, eval("pi"));
    }

    @Test
    @DisplayName("Statements: if/else, while, both fors, break, continue, return")
    void statements() {
        assertEquals(2, eval("if (x > 0) { 1 } else { 2 }", -1, 0, 0));
        assertEquals(1, eval("if (x > 0) 1; else 2", 3, 0, 0));
        assertEquals(10, eval("s = 0; i = 0; while (i < 4) { i++; s += i; } s"));
        assertEquals(15, eval("s = 0; for (i = 1, 5) { s += i } s"));
        assertEquals(6, eval("s = 0; for (i = 0; i < 4; i++) { s += i } s"));
        assertEquals(3, eval("s = 0; for (i = 0; i < 10; i++) { if (i == 3) { break } s++ } s"));
        assertEquals(5, eval("s = 0; for (i = 0; i < 10; i++) { if (i % 2 == 0) { continue } s++ } s"));
        assertEquals(42, eval("return 42; 7"));
        assertEquals(8, eval("i = 0; do { i += 2 } while (i < 7); i"));
    }

    @Test
    @DisplayName("Functions: math, distance shapes, bounded noises")
    void functions() {
        assertEquals(5, eval("len(3, 4)"));
        assertEquals(2, eval("clamp(5, 0, 2)"));
        assertEquals(0.5, eval("smoothstep(0, 1, 0.5)"));
        assertEquals(2, eval("mod(-1, 3)"));
        assertEquals(-1, eval("sdsphere(0, 0, 0, 1)"));
        assertEquals(0, eval("sdbox(1, 0, 0, 1, 1, 1)"), 1e-9);
        assertEquals(1, eval("max(1, -2, 0.5)"));
        for (int i = 0; i < 50; i++) {
            double p = eval("perlin(3, x, y, z, 0.2, 4, 0.5)", i * 1.3, i * 0.7, i * 0.4);
            assertTrue(p >= -1 && p <= 1);
            double c = eval("cracks(x, y, z)", i * 1.3, i * 0.7, i * 0.4);
            assertTrue(c >= 0 && c < 1);
            double v = eval("voronoi(1, x, y, z, 0.3)", i * 1.3, i * 0.7, i * 0.4);
            assertTrue(v >= -1 && v <= 1);
        }
        assertEquals(eval("fractal(1.5, 2.5, 3.5)"), eval("noise(1.5, 2.5, 3.5)"));
    }

    @Test
    @DisplayName("World queries read around the evaluated block")
    void worldQueries() {
        Expression e = Expression.compile("solid(0, -1, 0) && air(0, 1, 0)");
        Expression.Frame frame = e.frame();
        frame.blockX = 5;
        frame.blockY = 10;
        frame.blockZ = 5;
        frame.world = new Expression.WorldQuery() {
            @Override
            public boolean solid(int x, int y, int z) {
                return y < 10;
            }

            @Override
            public boolean air(int x, int y, int z) {
                return y > 10;
            }
        };
        assertEquals(1, e.run(frame.inputs()));
    }

    @Test
    @DisplayName("A malformed expression says where; an endless loop stops")
    void errors() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> eval("1 + (2"));
        assertTrue(missing.getMessage().contains(")"));
        assertThrows(IllegalArgumentException.class, () -> eval("foo(1)"));
        assertThrows(IllegalArgumentException.class, () -> eval("sin(1, 2)"));
        assertThrows(IllegalArgumentException.class, () -> eval("1 2"));
        assertThrows(IllegalArgumentException.class, () -> eval("pi = 3"));
        assertThrows(IllegalArgumentException.class, () -> eval("while (1) { }"));
        assertThrows(IllegalArgumentException.class, () -> eval(""));
    }
}
