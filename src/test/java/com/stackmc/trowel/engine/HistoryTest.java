package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Keys;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final BlockData AIR = block();
    private static final BlockData SIGN = block();
    private static final BlockData STONE = block();
    private static final BlockData DIRT = block();

    private static BlockData block() {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class},
                (proxy, method, args) -> method.getName().equals("equals") ? proxy == args[0] : null);
    }

    private static BlockState state() {
        return (BlockState) Proxy.newProxyInstance(BlockState.class.getClassLoader(), new Class<?>[]{BlockState.class},
                (proxy, method, args) -> method.getName().equals("equals") ? proxy == args[0] : null);
    }

    @Test
    @DisplayName("Strokes merged: the oldest state wins, with its own block data only")
    void absorbKeepsOldestTile() {
        long a = Keys.pack(1, 2, 3);
        long b = Keys.pack(4, 5, 6);
        BlockState sign = state();
        BlockState later = state();

        Batch first = new Batch(WORLD, "brush:0:sphere", 0, "Sphere");
        first.before.put(a, SIGN);
        first.tilesBefore.put(a, sign);
        first.after.put(a, STONE);
        first.before.put(b, AIR);
        first.after.put(b, STONE);

        Batch second = new Batch(WORLD, "brush:0:sphere", 5, "Sphere");
        second.before.put(a, STONE);
        second.after.put(a, DIRT);
        // b was air before the gesture: a block data met on the second stroke must not come back on undo.
        second.before.put(b, SIGN);
        second.tilesBefore.put(b, later);
        second.after.put(b, DIRT);

        first.absorb(second);

        assertSame(SIGN, first.before.get(a));
        assertSame(sign, first.tilesBefore.get(a));
        assertSame(AIR, first.before.get(b));
        assertNull(first.tilesBefore.get(b));
        assertSame(DIRT, first.after.get(a));
        assertEquals(5, first.lastTick);
    }

    @Test
    @DisplayName("Undoing part of a batch takes the block data along, and leaves the rest")
    void moveIntoCarriesTiles() {
        long inside = Keys.pack(0, 0, 0);
        long outside = Keys.pack(50, 0, 0);
        BlockState sign = state();
        Batch batch = new Batch(WORLD, null, 0, "Fill");
        batch.before.put(inside, SIGN);
        batch.tilesBefore.put(inside, sign);
        batch.after.put(inside, STONE);
        batch.before.put(outside, AIR);
        batch.after.put(outside, STONE);
        batch.paramsBefore.put(inside, Map.of("w", "3"));
        batch.paramsAfter.put(inside, null);

        Batch part = new Batch(WORLD, null, 0, "Undo");
        Engine.moveInto(batch, part, key -> Keys.x(key) < 10, true);

        assertSame(SIGN, part.before.get(inside));
        assertSame(sign, part.tilesBefore.get(inside));
        assertSame(STONE, part.after.get(inside));
        assertEquals(Map.of("w", "3"), part.paramsBefore.get(inside));
        assertFalse(batch.before.containsKey(inside));
        assertFalse(batch.tilesBefore.containsKey(inside));
        assertTrue(batch.before.containsKey(outside));
        assertFalse(part.before.containsKey(outside));
    }

    @Test
    @DisplayName("Going back through several batches, the oldest state is placed, the newest is kept for redo")
    void moveIntoOlderWins() {
        long key = Keys.pack(3, 3, 3);
        Batch newer = new Batch(WORLD, null, 0, "B");
        newer.before.put(key, STONE);
        newer.after.put(key, DIRT);
        Batch older = new Batch(WORLD, null, 0, "A");
        older.before.put(key, AIR);
        older.after.put(key, STONE);

        Batch part = new Batch(WORLD, null, 0, "Undo inside");
        Engine.moveInto(newer, part, k -> true, true);
        Engine.moveInto(older, part, k -> true, true);

        assertSame(AIR, part.before.get(key));
        assertSame(DIRT, part.after.get(key));
        assertTrue(newer.isEmpty());
        assertTrue(older.isEmpty());
    }

    @Test
    @DisplayName("A block placed again by the same operation forgets the data it was given")
    void changeSetDropsTileWhenReplaced() {
        ChangeSet changes = new ChangeSet();
        long key = Keys.pack(0, 0, 0);
        changes.set(key, SIGN);
        changes.tile(key, state());
        changes.set(key, STONE);
        assertNull(changes.tiles().get(key));
        changes.tile(Keys.pack(9, 9, 9), state());
        assertTrue(changes.tiles().isEmpty());
    }
}
