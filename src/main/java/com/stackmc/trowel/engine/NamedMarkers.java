package com.stackmc.trowel.engine;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocks that become markers once placed.
 *
 * <p>A marker asked for by name ({@code checkpoint}) has the material of an ordinary block, but
 * it is a separate instance: once placed it becomes a marker, while the same glass written by its
 * material stays a plain block. A marker taken from the clipboard ({@code #clipboard}) is such an
 * instance too, and carries its settings.</p>
 */
public final class NamedMarkers {

    private static final Map<Material, BlockData> BLOCKS = new ConcurrentHashMap<>();
    /** One instance per state and settings: a pattern may hand out the same marker millions of times. */
    private static final Map<String, BlockData> CARRIERS = new ConcurrentHashMap<>();
    private static final Map<BlockData, Map<String, String>> SETTINGS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private NamedMarkers() {
    }

    public static BlockData of(Material material) {
        return BLOCKS.computeIfAbsent(material, Material::createBlockData);
    }

    public static boolean is(BlockData data) {
        if (data == null || BLOCKS.isEmpty()) {
            return false;
        }
        Material material = data.getMaterial();
        return material != null && BLOCKS.get(material) == data;
    }

    /** A block that becomes a marker with these settings once placed. */
    public static BlockData carrying(BlockData data, Map<String, String> settings) {
        Map<String, String> copy = Map.copyOf(settings);
        return CARRIERS.computeIfAbsent(data.getAsString() + '\u0000' + new java.util.TreeMap<>(copy), key -> {
            BlockData carrier = data.clone();
            SETTINGS.put(carrier, copy);
            return carrier;
        });
    }

    /** The settings carried by this block, or {@code null} if it is not a carrier. */
    public static Map<String, String> carried(BlockData data) {
        if (data == null || SETTINGS.isEmpty()) {
            return null;
        }
        Map<String, String> settings = SETTINGS.get(data);
        return settings == null ? null : new LinkedHashMap<>(settings);
    }
}
