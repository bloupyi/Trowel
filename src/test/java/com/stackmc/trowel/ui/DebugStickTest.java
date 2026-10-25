package com.stackmc.trowel.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugStickTest {

    @Test
    @DisplayName("Properties and their values come out in the order the states list them")
    void properties() {
        Map<String, List<String>> properties = ToolListener.properties(List.of(
                "minecraft:oak_slab[type=top,waterlogged=true]",
                "minecraft:oak_slab[type=top,waterlogged=false]",
                "minecraft:oak_slab[type=bottom,waterlogged=true]",
                "minecraft:oak_slab[type=bottom,waterlogged=false]",
                "minecraft:oak_slab[type=double,waterlogged=true]",
                "minecraft:oak_slab[type=double,waterlogged=false]"));
        assertEquals(List.of("type", "waterlogged"), List.copyOf(properties.keySet()));
        assertEquals(List.of("top", "bottom", "double"), properties.get("type"));
        assertEquals(List.of("true", "false"), properties.get("waterlogged"));
        assertTrue(ToolListener.properties(List.of("minecraft:stone")).isEmpty());
    }

    @Test
    @DisplayName("A state reads and writes back the same")
    void state() {
        Map<String, String> values = ToolListener.values("minecraft:oak_stairs[facing=north,half=bottom,shape=straight]");
        assertEquals("north", values.get("facing"));
        assertEquals("[facing=north,half=bottom,shape=straight]", ToolListener.state(values));
    }

    @Test
    @DisplayName("Cycling wraps around both ways")
    void cycle() {
        List<String> options = List.of("north", "south", "west", "east");
        assertEquals("south", ToolListener.cycle(options, "north", false));
        assertEquals("east", ToolListener.cycle(options, "north", true));
        assertEquals("north", ToolListener.cycle(options, "east", false));
    }
}
