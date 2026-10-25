package com.stackmc.trowel.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandArgsTest {

    @Test
    @DisplayName("Flags are letters after a dash; a negative number is an argument")
    void flagsAndNumbers() {
        String[] args = {"5", "-as", "-3", "north", "-o"};
        assertEquals(Set.of("a", "s", "o"), Commands.flags(args));
        assertArrayEquals(new String[]{"5", "-3", "north"}, Commands.plain(args));
    }

    @Test
    @DisplayName("Integers are checked against their range, with the label in the message")
    void integers() {
        assertEquals(12, Commands.integer(" 12 ", 1, 64, "Radius"));
        IllegalArgumentException range = assertThrows(IllegalArgumentException.class,
                () -> Commands.integer("65", 1, 64, "Radius"));
        assertEquals("Radius: between 1 and 64.", range.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Commands.integer("abc", 1, 64, "Radius"));
    }

    @Test
    @DisplayName("Speed levels go from vanilla at 1 to Bukkit's maximum at 10")
    void speeds() {
        assertEquals(0.1f, Commands.speed("1", 0.1f), 1e-6);
        assertEquals(0.2f, Commands.speed("1", 0.2f), 1e-6);
        assertEquals(1f, Commands.speed("10", 0.1f));
        assertEquals(1f, Commands.speed("10", 0.2f));
        assertEquals(0.55f, Commands.speed("5.5", 0.1f), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> Commands.speed("0", 0.1f));
        assertThrows(IllegalArgumentException.class, () -> Commands.speed("11", 0.1f));
        assertThrows(IllegalArgumentException.class, () -> Commands.speed("NaN", 0.1f));
        assertThrows(IllegalArgumentException.class, () -> Commands.speed("fast", 0.1f));
    }

    @Test
    @DisplayName("A missing argument gives the usage")
    void need() {
        assertEquals("stone", Commands.need(new String[]{"stone"}, 0, "//set <pattern>"));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> Commands.need(new String[0], 0, "//set <pattern>"));
        assertEquals("Usage: //set <pattern>", missing.getMessage());
        assertEquals("a b c", Commands.join(new String[]{"x", "a", "b", "c"}, 1));
    }
}
