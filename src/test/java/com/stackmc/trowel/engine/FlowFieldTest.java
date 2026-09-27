package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.geom.NoiseSpec;
import com.stackmc.trowel.pattern.Palette;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowFieldTest {

    private static final BlockData A = block();
    private static final Box BOX = new Box(0, -4, 0, 31, 8, 31);

    private static BlockData block() {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class},
                (proxy, method, args) -> method.getName().equals("equals") ? proxy == args[0] : null);
    }

    private static EditContext context() {
        return new EditContext((x, y, z) -> A, new Long2ObjectOpenHashMap<>(), null, null, 5_000_000, null, null, null,
                new Progress());
    }

    private static FlowField.Options options(boolean fill, boolean threeD) {
        return new FlowField.Options(20, false, 32, 1, 1, 0, new double[]{0, 0, 0}, null, false, fill, threeD);
    }

    @Test
    @DisplayName("In 3D the lines stay inside the selection, and the same seed draws the same lines")
    void volume() {
        Palette palette = new Palette(List.of(A));
        ChangeSet changes = FlowField.build(BOX, palette, NoiseSpec.parse("perlin", 0.05), options(false, true), 5)
                .run(context());
        assertFalse(changes.isEmpty());
        changes.blocks().keySet().forEach(key -> assertTrue(BOX.contains(Keys.x(key), Keys.y(key), Keys.z(key))));
        changes.blocks().values().forEach(data -> assertSame(A, data));
        ChangeSet again = FlowField.build(BOX, palette, NoiseSpec.parse("perlin", 0.05), options(false, true), 5)
                .run(context());
        assertEquals(changes.blocks().keySet(), again.blocks().keySet());
    }

    @Test
    @DisplayName("Curl and gravity still give unit directions")
    void directions() {
        NoiseSpec noise = NoiseSpec.parse("perlin", 0.05);
        for (boolean threeD : new boolean[]{false, true}) {
            FlowField.Options curl = new FlowField.Options(1, false, 1, 1, 1, 0, new double[]{0, 0, 0}, null, true,
                    false, threeD);
            for (int i = 0; i < 50; i++) {
                double[] d = FlowField.direction(noise, new double[]{i * 1.7, i * 0.3, i * 2.9}, curl);
                assertEquals(1, Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-9);
            }
        }
        assertEquals(1024 / 10, FlowField.lineCount(new Box(0, 0, 0, 31, 9, 31),
                new FlowField.Options(10, true, 1, 1, 1, 0, new double[]{0, 0, 0}, null, false, false, false)));
    }
}
