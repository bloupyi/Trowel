package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeSetTest {

    private static final BlockData STONE = (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(),
            new Class<?>[]{BlockData.class}, (proxy, method, args) -> null);

    @Test
    @DisplayName("What leaves the buildable area does not get in, but is counted")
    void outsideIsCounted() {
        ChangeSet changes = new ChangeSet(new Box(0, 0, 0, 9, 9, 9), 1_000);
        changes.set(5, 5, 5, STONE);
        changes.set(10, 5, 5, STONE);
        changes.set(-1, 5, 5, STONE);
        changes.params(com.stackmc.trowel.api.Keys.pack(20, 5, 5), Map.of("w", "3"));
        assertEquals(1, changes.size());
        assertEquals(2, changes.outside());
        assertTrue(changes.params().isEmpty());
    }

    @Test
    @DisplayName("Exceeding the limit stops computing at once; setting the same cell again does not count")
    void limitStopsEarly() {
        ChangeSet changes = new ChangeSet(null, 3);
        changes.set(0, 0, 0, STONE);
        changes.set(1, 0, 0, STONE);
        changes.set(2, 0, 0, STONE);
        changes.set(2, 0, 0, STONE);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> changes.set(3, 0, 0, STONE));
        assertTrue(refused.getMessage().contains("3"));
    }

    @Test
    @DisplayName("A pattern that places nothing counts nowhere")
    void nullIsIgnored() {
        ChangeSet changes = new ChangeSet(new Box(0, 0, 0, 1, 1, 1), 1);
        changes.set(5, 5, 5, null);
        changes.set(0, 0, 0, null);
        assertEquals(0, changes.size());
        assertEquals(0, changes.outside());
    }

    @Test
    @DisplayName("What the filter rejects is counted apart and does not use up the limit")
    void gateRefusesWithoutSpendingLimit() {
        ChangeSet changes = new ChangeSet(new Box(0, 0, 0, 9, 9, 9), 2, (x, y, z, data) -> x % 2 == 0);
        for (int x = 0; x < 4; x++) {
            changes.set(x, 0, 0, STONE);
        }
        changes.set(20, 0, 0, STONE);
        assertEquals(2, changes.size());
        assertEquals(2, changes.refused());
        assertEquals(1, changes.outside());
    }
}
