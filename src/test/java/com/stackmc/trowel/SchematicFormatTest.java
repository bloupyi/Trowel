package com.stackmc.trowel;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicFormatTest {

    @TempDir
    File folder;

    @Test
    @DisplayName("A schematic reads back as written: bounds, palette, cells, markers, block data")
    void roundTrip() throws IOException {
        File file = new File(folder, "house.tsch");
        Box bounds = new Box(-2, 0, -3, 4, 5, 1);
        int[] cells = {-2, 0, -3, 0, 4, 5, 1, 1};
        byte[] sign = {1, 2, 3, 4};
        SchematicLibrary.write(file, bounds, List.of("minecraft:air", "minecraft:oak_sign[rotation=4]"), cells,
                Map.of(Keys.pack(4, 5, 1), Map.of("dir", "east")), Map.of(Keys.pack(4, 5, 1), sign));

        SchematicLibrary.Raw raw = SchematicLibrary.read(file);
        assertEquals(bounds, raw.bounds());
        assertEquals(List.of("minecraft:air", "minecraft:oak_sign[rotation=4]"), raw.palette());
        assertArrayEquals(cells, raw.cells());
        assertEquals(1, raw.params().size());
        assertEquals(Map.of("dir", "east"), raw.params().values().iterator().next());
        assertEquals(1, raw.tiles().size());
        assertArrayEquals(new long[]{4, 5, 1}, raw.tiles().keySet().iterator().next());
        assertArrayEquals(sign, raw.tiles().values().iterator().next());
        assertFalse(new File(folder, "house.tsch.tmp").exists());
    }

    @Test
    @DisplayName("Files from the first version, without block data, still load")
    void readsVersionOne() throws IOException {
        File file = new File(folder, "old.tsch");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeInt(0x54534348);
            out.writeInt(1);
            for (int value : new int[]{0, 0, 0, 1, 1, 1}) {
                out.writeInt(value);
            }
            out.writeInt(1);
            out.writeUTF("minecraft:stone");
            out.writeInt(1);
            for (int value : new int[]{0, 0, 0, 0}) {
                out.writeInt(value);
            }
            out.writeInt(0);
        }
        SchematicLibrary.Raw raw = SchematicLibrary.read(file);
        assertEquals(List.of("minecraft:stone"), raw.palette());
        assertTrue(raw.tiles().isEmpty());
    }

    @Test
    @DisplayName("A file from a newer Trowel is refused instead of misread")
    void refusesNewerVersion() throws IOException {
        File file = new File(folder, "future.tsch");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(file)))) {
            out.writeInt(0x54534348);
            out.writeInt(99);
        }
        assertThrows(IOException.class, () -> SchematicLibrary.read(file));
    }

    @Test
    @DisplayName("Overwriting replaces the old file in one move")
    void overwrite() throws IOException {
        File file = new File(folder, "twice.tsch");
        SchematicLibrary.write(file, new Box(0, 0, 0, 0, 0, 0), List.of("minecraft:stone"), new int[]{0, 0, 0, 0},
                Map.of(), Map.of());
        SchematicLibrary.write(file, new Box(0, 0, 0, 1, 0, 0), List.of("minecraft:dirt"),
                new int[]{0, 0, 0, 0, 1, 0, 0, 0}, Map.of(), Map.of());
        assertEquals(List.of("minecraft:dirt"), SchematicLibrary.read(file).palette());
    }
}
