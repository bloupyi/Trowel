package com.stackmc.trowel.spline;

import com.stackmc.trowel.engine.ChangeSet;
import com.stackmc.trowel.engine.EditContext;
import com.stackmc.trowel.engine.Progress;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.geom.NoiseSpec;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplineTest {

    private static final BlockData STONE = (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(),
            new Class<?>[]{BlockData.class}, (proxy, method, args) -> null);

    private static EditContext context() {
        return new EditContext((x, y, z) -> null, new Long2ObjectOpenHashMap<>(), null, null, 5_000_000, null, null,
                null, new Progress());
    }

    private static Sweep.Options options(String radii, Sweep.End end) {
        return new Sweep.Options(Radii.parse(radii, 64), 0, Double.NaN, 0, 1, end, Sweep.Quality.BALANCED, false,
                Path.Normal.CONSISTENT, 0, 0, 0, false);
    }

    @Test
    @DisplayName("A straight tube of radius 3: exactly the WorldEdit disc at each slice, flat caps")
    void straightTube() {
        List<double[]> points = List.of(new double[]{0.5, 0.5, 0.5}, new double[]{30.5, 0.5, 0.5});
        ChangeSet changes = Sweep.build(context(), points, Sections.parse("circle"), options("3", Sweep.End.FLAT),
                (x, y, z, l) -> STONE, null);
        int disk = 0;
        for (int dy = -3; dy <= 3; dy++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dy * dy + dz * dz <= 9) {
                    disk++;
                }
            }
        }
        assertEquals(31 * disk, changes.size());
        assertTrue(changes.blocks().containsKey(Keys.pack(15, 3, 0)));
        assertTrue(!changes.blocks().containsKey(Keys.pack(-1, 0, 0)));
    }

    @Test
    @DisplayName("In a tight bend, no hole along the path or around it")
    void noHolesInCurves() {
        List<double[]> points = List.of(new double[]{0.5, 60.5, 0.5}, new double[]{10.5, 64.5, 3.5},
                new double[]{12.5, 70.5, 14.5}, new double[]{2.5, 66.5, 18.5});
        ChangeSet changes = Sweep.build(context(), points, Sections.parse("circle"), options("2", Sweep.End.ROUND),
                (x, y, z, l) -> STONE, null);
        Path path = Path.build(points, 0, 0, 0, false, Path.Normal.CONSISTENT, 0.1);
        for (int i = 0; i < path.size(); i++) {
            double[] p = path.point(i);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = (int) Math.floor(p[0]) + dx;
                        int y = (int) Math.floor(p[1]) + dy;
                        int z = (int) Math.floor(p[2]) + dz;
                        double d = Math.sqrt(Math.pow(x + 0.5 - p[0], 2) + Math.pow(y + 0.5 - p[1], 2)
                                + Math.pow(z + 0.5 - p[2], 2));
                        if (d <= 1.4) {
                            assertTrue(changes.blocks().containsKey(Keys.pack(x, y, z)),
                                    "hole at " + x + " " + y + " " + z);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("The frame stays orthonormal all along, in the three orientations")
    void frames() {
        List<double[]> points = List.of(new double[]{0, 0, 0}, new double[]{5, 8, 1}, new double[]{9, 2, 7},
                new double[]{3, -4, 12}, new double[]{0, 10, 10});
        for (Path.Normal normal : Path.Normal.values()) {
            Path path = Path.build(points, 0.2, -0.3, 0.1, false, normal, 0.25);
            for (int i = 0; i < path.size(); i++) {
                double tu = path.tx[i] * path.ux[i] + path.ty[i] * path.uy[i] + path.tz[i] * path.uz[i];
                double uv = path.ux[i] * path.vx[i] + path.uy[i] * path.vy[i] + path.uz[i] * path.vz[i];
                double vv = path.vx[i] * path.vx[i] + path.vy[i] * path.vy[i] + path.vz[i] * path.vz[i];
                assertEquals(0, uv, 1e-6, normal + " u.v");
                assertEquals(1, vv, 1e-6, normal + " |v|");
                if (normal != Path.Normal.UPRIGHT) {
                    assertEquals(0, tu, 1e-6, normal + " t.u");
                }
            }
        }
        Path loop = Path.build(List.of(new double[]{0, 0, 0}, new double[]{10, 0, 0}, new double[]{10, 5, 10},
                new double[]{0, 3, 10}), 0, 0, 0, true, Path.Normal.CONSISTENT, 0.25);
        int last = loop.size() - 1;
        assertEquals(1, loop.vx[0] * loop.vx[last] + loop.vy[0] * loop.vy[last] + loop.vz[0] * loop.vz[last], 1e-3,
                "a loop closes without twisting");
    }

    @Test
    @DisplayName("Each section reads with its default settings and fills something")
    void sections() {
        for (String name : Sections.all().keySet()) {
            Sections.Section section = Sections.parse(name);
            int filled = 0;
            for (int i = 0; i < 4000; i++) {
                double u = (i % 20) / 10.0 - 0.95;
                double v = ((i / 20) % 20) / 10.0 - 0.95;
                double w = (i / 400) * 0.37;
                double value = section.eval(u, v, w, 0.5);
                assertTrue(value >= 0 && value <= 1, name);
                if (value > 0) {
                    filled++;
                }
            }
            assertTrue(filled > 0, name + " fills nothing");
        }
        Sections.Section star = Sections.parse("star(S:6,D:0.4)");
        assertEquals(1, star.eval(0, 0.99, 0, 0));
        assertEquals(0, star.eval(0.5, 0.5, 0, 0) > 0 && Sections.parse("star(S:5,D:0.9)").eval(0.5, 0.5, 0, 0) > 0 ? 1 : 0);
        assertThrows(IllegalArgumentException.class, () -> Sections.parse("star(Z:3)"));
        assertThrows(IllegalArgumentException.class, () -> Sections.parse("unknown"));
        Sections.Section noise = Sections.noise(NoiseSpec.parse("cellular(cr:edge)", 2), 0.6, null);
        assertTrue(noise.valued());
        assertEquals(0, noise.eval(1.1, 0, 0, 0));
        Sections.Section expression = Sections.expression("x*x + y*y < 0.5 ? 0.3 : 0", false, 1, 10);
        assertEquals(0.3, expression.eval(0.1, 0.1, 0, 0), 1e-9);
        assertEquals(0, expression.eval(0.9, 0.9, 0, 0));
    }

    @Test
    @DisplayName("Keyframe radii: places spread out, edges held, clear errors")
    void radii() {
        Radii r = Radii.parse("1,12,1", 64);
        assertEquals(1, r.at(0), 1e-9);
        assertEquals(12, r.at(0.5), 1e-9);
        assertEquals(1, r.at(1), 1e-9);
        Radii shifted = Radii.parse("1,0.2:10,1", 64);
        assertEquals(10, shifted.at(0.2), 1e-9);
        assertEquals(12, r.max(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> Radii.parse("0.5:3,4", 64));
        assertThrows(IllegalArgumentException.class, () -> Radii.parse("1,0.6:3,0.4:3,1", 64));
        assertThrows(IllegalArgumentException.class, () -> Radii.parse("100", 64));
        assertThrows(IllegalArgumentException.class, () -> Radii.parse("-2", 64));
    }
}
