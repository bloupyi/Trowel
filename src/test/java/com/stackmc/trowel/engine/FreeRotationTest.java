package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Keys;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeRotationTest {

    private static final FreeRotation.Kind<String> STRINGS = new FreeRotation.Kind<>() {
        @Override
        public int tier(String value) {
            return switch (value) {
                case "air" -> FreeRotation.EMPTY;
                case "torch" -> FreeRotation.DETAIL;
                default -> FreeRotation.FULL;
            };
        }

        @Override
        public String turn(String value, int quarters) {
            return value;
        }

        @Override
        public int support(String value) {
            return -1;
        }
    };

    private static Long2ObjectOpenHashMap<String> turn(Long2ObjectOpenHashMap<String> cells, double degrees) {
        return FreeRotation.turn(cells, degrees, 0, 0, STRINGS, LongSets.EMPTY_SET).cells();
    }

    /** A facade block: what it is and where it faces (0 to 3: north, east, south, west), -1 if nowhere. */
    private record Part(String name, int facing) {
    }

    /** The face of {@link FreeRotation#FACES} toward north, east, south, west. */
    private static final int[] TOWARD = {5, 0, 4, 1};

    private static final FreeRotation.Kind<Part> PARTS = new FreeRotation.Kind<>() {
        @Override
        public int tier(Part value) {
            return switch (value.name) {
                case "air" -> FreeRotation.EMPTY;
                case "stone", "log" -> FreeRotation.FULL;
                case "torch" -> FreeRotation.DETAIL;
                default -> FreeRotation.PARTIAL;
            };
        }

        @Override
        public Part turn(Part value, int quarters) {
            return value.facing < 0 ? value : new Part(value.name, Math.floorMod(value.facing + quarters, 4));
        }

        @Override
        public int support(Part value) {
            return switch (value.name) {
                case "torch" -> TOWARD[(value.facing + 2) % 4];
                case "stair" -> TOWARD[value.facing];
                default -> -1;
            };
        }
    };

    /**
     * A wall with log pillars every 5 blocks, a window between them with a stair sill and
     * trapdoor shutters, a torch on each pillar and a slab cornice, in front of a floor.
     */
    private static Long2ObjectOpenHashMap<Part> facade() {
        Long2ObjectOpenHashMap<Part> cells = new Long2ObjectOpenHashMap<>();
        Part air = new Part("air", -1);
        for (int x = -2; x <= 22; x++) {
            for (int y = 0; y <= 7; y++) {
                for (int z = -3; z <= 3; z++) {
                    cells.put(Keys.pack(x, y, z), air);
                }
            }
        }
        for (int x = 0; x <= 20; x++) {
            int bay = x % 5;
            for (int y = 0; y <= 6; y++) {
                boolean window = (bay == 2 || bay == 3) && (y == 2 || y == 3);
                cells.put(Keys.pack(x, y, 0), new Part(bay == 0 ? "log" : window ? "air" : "stone", -1));
                if (bay == 0) {
                    cells.put(Keys.pack(x, y, -1), new Part("log", -1));
                }
                if ((bay == 2 || bay == 3) && y == 1) {
                    cells.put(Keys.pack(x, y, -1), new Part("stair", 2));
                }
                if ((bay == 1 || bay == 4) && (y == 2 || y == 3)) {
                    cells.put(Keys.pack(x, y, -1), new Part("trapdoor", 0));
                }
            }
            if (bay == 0) {
                cells.put(Keys.pack(x, 4, -2), new Part("torch", 0));
            }
            for (int z = 1; z <= 3; z++) {
                cells.put(Keys.pack(x, 0, z), new Part("stone", -1));
            }
        }
        for (int x = -1; x <= 21; x++) {
            cells.put(Keys.pack(x, 7, -1), new Part("slab", -1));
        }
        return cells;
    }

    private static boolean full(Part part) {
        return part != null && PARTS.tier(part) == FreeRotation.FULL;
    }

    private static Part toward(Long2ObjectOpenHashMap<Part> cells, long key, int face) {
        int[] d = FreeRotation.FACES[face];
        return cells.get(Keys.offset(key, d[0], d[1], d[2]));
    }

    @Test
    @DisplayName("A detailed facade turned by any angle keeps its sills, shutters and torches against the wall")
    void facadeStaysHeld() {
        Long2ObjectOpenHashMap<Part> source = facade();
        for (int angle = 5; angle < 360; angle += 5) {
            if (angle % 90 == 0) {
                continue;
            }
            Long2ObjectOpenHashMap<Part> turned = FreeRotation.turn(source, angle, 0, 0, PARTS, LongSets.EMPTY_SET)
                    .cells();
            int torches = 0;
            for (Long2ObjectMap.Entry<Part> entry : turned.long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                Part part = entry.getValue();
                switch (part.name) {
                    case "stair" -> assertTrue(full(toward(turned, key, TOWARD[part.facing])),
                            "stair without its back against the wall at " + angle);
                    case "trapdoor" -> {
                        boolean held = false;
                        for (int face = 0; face < 6; face++) {
                            held |= full(toward(turned, key, face));
                        }
                        assertTrue(held, "floating shutter at " + angle);
                    }
                    case "torch" -> {
                        torches++;
                        assertTrue(full(toward(turned, key, TOWARD[(part.facing + 2) % 4])),
                                "torch off its pillar at " + angle);
                    }
                    default -> {
                    }
                }
            }
            assertEquals(5, torches, "torches at " + angle);
        }
    }

    @Test
    @DisplayName("Every window of a facade turned by 45 degrees stays open, between its stones")
    void facadeKeepsWindows() {
        Long2ObjectOpenHashMap<Part> turned = FreeRotation.turn(facade(), 45, 0, 0, PARTS, LongSets.EMPTY_SET).cells();
        double cos = Math.cos(Math.toRadians(45));
        for (int bay = 0; bay < 4; bay++) {
            double x = bay * 5 + 2.5;
            long center = Keys.pack((int) Math.round(x * cos), 2, (int) Math.round(x * cos));
            boolean open = false;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Part part = turned.get(Keys.offset(center, dx, 0, dz));
                    open |= part != null && part.name.equals("air");
                }
            }
            assertTrue(open, "window " + bay + " closed");
        }
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
            // The post stands once, both blocks on one column, next to where its center lands.
            long[] column = turned.keySet().longStream().filter(k -> Math.abs(Keys.x(k) - Keys.x(post)) <= 1
                    && Math.abs(Keys.z(k) - Keys.z(post)) <= 1).toArray();
            assertEquals(2, column.length, "post at " + angle);
            assertEquals(Keys.x(column[0]), Keys.x(column[1]), "post at " + angle);
            assertEquals(Keys.z(column[0]), Keys.z(column[1]), "post at " + angle);
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
