package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Keys;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipboardRotationTest {

    private static Long2ObjectOpenHashMap<String> turn(Long2ObjectOpenHashMap<String> cells, double degrees) {
        return Clipboard.turnFreely(cells, degrees, 0, 0, "air"::equals, s -> !s.equals("air") && !s.equals("torch"),
                LongSets.EMPTY_SET);
    }

    /** The solid blocks of a layer form one piece, joined by faces. */
    private static boolean joined(Long2ObjectOpenHashMap<String> cells, int y) {
        LongOpenHashSet solid = new LongOpenHashSet();
        cells.forEach((key, value) -> {
            if (Keys.y(key) == y && value.equals("stone")) {
                solid.add((long) key);
            }
        });
        if (solid.isEmpty()) {
            return false;
        }
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList pending = new LongArrayList();
        long first = solid.iterator().nextLong();
        seen.add(first);
        pending.add(first);
        while (!pending.isEmpty()) {
            long key = pending.removeLong(pending.size() - 1);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                long next = Keys.offset(key, d[0], 0, d[1]);
                if (solid.contains(next) && seen.add(next)) {
                    pending.add(next);
                }
            }
        }
        return seen.size() == solid.size();
    }

    private static Long2ObjectOpenHashMap<String> wall(int length) {
        Long2ObjectOpenHashMap<String> cells = new Long2ObjectOpenHashMap<>();
        for (int x = 0; x < length; x++) {
            cells.put(Keys.pack(x, 0, 0), "stone");
            cells.put(Keys.pack(x, 1, 0), "stone");
        }
        return cells;
    }

    @Test
    @DisplayName("A wall turned by 45 degrees becomes a diagonal without holes")
    void diagonal() {
        Long2ObjectOpenHashMap<String> turned = turn(wall(10), 45);
        assertTrue(turned.containsKey(Keys.pack(0, 0, 0)));
        assertTrue(turned.containsKey(Keys.pack(6, 1, 6)));
        for (int i = 0; i <= 6; i++) {
            assertTrue(turned.containsKey(Keys.pack(i, 0, i)), "diagonal block " + i);
        }
        assertTrue(turned.size() >= 2 * 7);
    }

    @Test
    @DisplayName("A small angle keeps the wall's length and tilts its end clockwise")
    void smallAngle() {
        Long2ObjectOpenHashMap<String> turned = turn(wall(20), 20);
        long end = Clipboard.turnPoint(Keys.pack(19, 0, 0), 20);
        assertEquals(18, Keys.x(end));
        assertEquals(6, Keys.z(end));
        assertTrue(turned.keySet().longStream().anyMatch(k -> Keys.y(k) == 0
                && Math.abs(Keys.x(k) - 18) <= 1 && Math.abs(Keys.z(k) - 6) <= 1));
        assertTrue(turned.values().stream().allMatch("stone"::equals));
    }

    @Test
    @DisplayName("Zero degrees changes nothing")
    void zero() {
        Long2ObjectOpenHashMap<String> cells = wall(5);
        assertEquals(cells, turn(cells, 0));
    }

    @Test
    @DisplayName("Walls turned by any angle stay joined by faces, never by corners only")
    void wallsStayJoined() {
        for (int angle = 5; angle <= 45; angle += 5) {
            Long2ObjectOpenHashMap<String> turned = turn(wall(16), angle);
            assertTrue(joined(turned, 0), "layer 0 at " + angle);
            assertTrue(joined(turned, 1), "layer 1 at " + angle);
        }
    }

    @Test
    @DisplayName("A floor turned by 45 degrees keeps its area, without holes")
    void floorWithoutHoles() {
        Long2ObjectOpenHashMap<String> cells = new Long2ObjectOpenHashMap<>();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                cells.put(Keys.pack(x, 0, z), "stone");
            }
        }
        Long2ObjectOpenHashMap<String> turned = turn(cells, 45);
        assertTrue(joined(turned, 0));
        assertTrue(Math.abs(turned.size() - 169) <= 12, "area " + turned.size());
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) + Math.abs(z) <= 8) {
                    assertTrue(turned.containsKey(Keys.pack(x, 0, z)), "hole at " + x + " " + z);
                }
            }
        }
    }

    @Test
    @DisplayName("A lone post is never lost, and a torch is neither duplicated nor left floating")
    void detailsStayHeld() {
        for (int step = 1; step <= 9; step++) {
            int angle = step * 5;
            Long2ObjectOpenHashMap<String> cells = wall(12);
            cells.put(Keys.pack(6, 1, 1), "torch");
            cells.put(Keys.pack(3, 0, 8), "stone");
            cells.put(Keys.pack(3, 1, 8), "stone");
            Long2ObjectOpenHashMap<String> turned = turn(cells, angle);
            long torches = turned.values().stream().filter("torch"::equals).count();
            assertTrue(torches <= 1, "torches at " + angle);
            turned.forEach((key, value) -> {
                if (value.equals("torch")) {
                    boolean held = false;
                    for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                        held |= "stone".equals(turned.get(Keys.offset(key, d[0], d[1], d[2])));
                    }
                    assertTrue(held, "floating torch at " + angle);
                }
            });
            long post = Clipboard.turnPoint(Keys.pack(3, 0, 8), angle);
            assertEquals("stone", turned.get(post), "post at " + angle);
            assertEquals("stone", turned.get(Keys.offset(post, 0, 1, 0)), "post top at " + angle);
        }
    }

    @Test
    @DisplayName("Air stays air: a room turned keeps its inside empty")
    void airStays() {
        Long2ObjectOpenHashMap<String> cells = new Long2ObjectOpenHashMap<>();
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                boolean edge = Math.abs(x) == 5 || Math.abs(z) == 5;
                cells.put(Keys.pack(x, 0, z), edge ? "stone" : "air");
            }
        }
        Long2ObjectOpenHashMap<String> turned = turn(cells, 30);
        assertEquals("air", turned.get(Keys.pack(0, 0, 0)));
        assertFalse(turned.values().stream().anyMatch(v -> !v.equals("air") && !v.equals("stone")));
        assertTrue(joined(turned, 0));
    }
}
