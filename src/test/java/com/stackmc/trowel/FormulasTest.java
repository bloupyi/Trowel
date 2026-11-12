package com.stackmc.trowel;

import com.stackmc.trowel.expr.Expression;
import com.stackmc.trowel.geom.NoiseSpec;
import com.stackmc.trowel.geom.Relief;
import com.stackmc.trowel.spline.Sections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulasTest {

    @Test
    @DisplayName("Every ready-made formula works in the command it is made for")
    void presetsParse() {
        Set<String> terrain = Set.of("alps", "rolling", "terraces", "crackle");
        Set<String> poly = Set.of("petals", "blob", "egg");
        Set<String> spline = Set.of("ribbed", "twisted");
        assertTrue(PaletteStore.FORMULAS.keySet().stream()
                .allMatch(k -> terrain.contains(k) || poly.contains(k) || spline.contains(k)));
        terrain.forEach(name -> {
            String[] parts = PaletteStore.FORMULAS.get(name).split(" ");
            assertTrue(Relief.parse(parts[0]) == null);
            assertDoesNotThrow(() -> NoiseSpec.parse(parts[0], 1 / Double.parseDouble(parts[1])), name);
        });
        poly.forEach(name -> {
            Expression e = Expression.compile(PaletteStore.FORMULAS.get(name), "a", "t", "i");
            for (double a = 0; a < 2 * Math.PI; a += 0.1) {
                double r = e.evaluate(a, 0, 0);
                assertTrue(r > 0.3 && r < 2, name);
            }
        });
        spline.forEach(name -> {
            Sections.Section section = Sections.expression(PaletteStore.FORMULAS.get(name), false, 1.5, 20);
            assertTrue(section.eval(0, 0, 0, 0.5) > 0, name);
            assertTrue(section.eval(1.4, 0, 0, 0.5) == 0, name);
        });
    }
}
