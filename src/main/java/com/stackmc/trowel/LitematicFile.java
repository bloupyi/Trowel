package com.stackmc.trowel;

import com.stackmc.trowel.api.Box;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a Litematica schematic ({@code .litematic}): one region holding the blocks. Markers and
 * block contents (sign text, chests...) stay in Trowel's library.
 */
public final class LitematicFile {

    private static final int INT = 3;
    private static final int LONG = 4;
    private static final int STRING = 8;
    private static final int LIST = 9;
    private static final int COMPOUND = 10;
    private static final int LONG_ARRAY = 12;
    private static final String AIR = "minecraft:air";

    private LitematicFile() {
    }

    /**
     * @param cells x, y, z, palette index, four ints per block, relative to the origin; a
     *              position without a block is air
     */
    public static byte[] write(String name, String author, Box b, List<String> palette, int[] cells, int dataVersion,
                               long created) throws IOException {
        int sx = b.width();
        int sy = b.height();
        int sz = b.depth();
        int volume = sx * sy * sz;
        // Litematica expects air first in the palette.
        List<String> states = new ArrayList<>();
        states.add(AIR);
        int[] remap = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            String state = palette.get(i);
            if (state.equals(AIR)) {
                remap[i] = 0;
            } else {
                remap[i] = states.size();
                states.add(state);
            }
        }
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(states.size() - 1));
        long[] packed = new long[(int) (((long) volume * bits + 63) / 64)];
        long mask = (1L << bits) - 1;
        int blocks = 0;
        for (int i = 0; i < cells.length; i += 4) {
            long value = remap[cells[i + 3]];
            if (value == 0) {
                continue;
            }
            blocks++;
            long index = (long) (cells[i + 1] - b.minY()) * sx * sz + (long) (cells[i + 2] - b.minZ()) * sx
                    + (cells[i] - b.minX());
            long start = index * bits;
            int first = (int) (start >> 6);
            int last = (int) ((start + bits - 1) >> 6);
            int offset = (int) (start & 63);
            packed[first] |= (value & mask) << offset;
            if (first != last) {
                packed[last] |= (value & mask) >>> (64 - offset);
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            tag(out, COMPOUND, "");
            integer(out, "Version", 6);
            integer(out, "SubVersion", 1);
            integer(out, "MinecraftDataVersion", dataVersion);

            tag(out, COMPOUND, "Metadata");
            string(out, "Name", name);
            string(out, "Author", author);
            string(out, "Description", "");
            integer(out, "RegionCount", 1);
            integer(out, "TotalVolume", volume);
            integer(out, "TotalBlocks", blocks);
            tag(out, LONG, "TimeCreated");
            out.writeLong(created);
            tag(out, LONG, "TimeModified");
            out.writeLong(created);
            vector(out, "EnclosingSize", sx, sy, sz);
            out.writeByte(0);

            tag(out, COMPOUND, "Regions");
            tag(out, COMPOUND, name);
            vector(out, "Position", 0, 0, 0);
            vector(out, "Size", sx, sy, sz);
            tag(out, LIST, "BlockStatePalette");
            out.writeByte(COMPOUND);
            out.writeInt(states.size());
            for (String state : states) {
                int open = state.indexOf('[');
                string(out, "Name", open < 0 ? state : state.substring(0, open));
                if (open >= 0 && state.endsWith("]") && state.length() > open + 2) {
                    tag(out, COMPOUND, "Properties");
                    for (String property : state.substring(open + 1, state.length() - 1).split(",")) {
                        int eq = property.indexOf('=');
                        string(out, property.substring(0, eq), property.substring(eq + 1));
                    }
                    out.writeByte(0);
                }
                out.writeByte(0);
            }
            tag(out, LONG_ARRAY, "BlockStates");
            out.writeInt(packed.length);
            for (long value : packed) {
                out.writeLong(value);
            }
            for (String list : List.of("TileEntities", "Entities", "PendingBlockTicks", "PendingFluidTicks")) {
                tag(out, LIST, list);
                out.writeByte(COMPOUND);
                out.writeInt(0);
            }
            out.writeByte(0);
            out.writeByte(0);
            out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static void tag(DataOutputStream out, int type, String name) throws IOException {
        out.writeByte(type);
        out.writeUTF(name);
    }

    private static void integer(DataOutputStream out, String name, int value) throws IOException {
        tag(out, INT, name);
        out.writeInt(value);
    }

    private static void string(DataOutputStream out, String name, String value) throws IOException {
        tag(out, STRING, name);
        out.writeUTF(value);
    }

    private static void vector(DataOutputStream out, String name, int x, int y, int z) throws IOException {
        tag(out, COMPOUND, name);
        integer(out, "x", x);
        integer(out, "y", y);
        integer(out, "z", z);
        out.writeByte(0);
    }
}
