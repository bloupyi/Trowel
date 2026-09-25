package com.stackmc.trowel.engine;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The block of a marker asked for by name ({@code checkpoint}).
 *
 * <p>It has the material of an ordinary block, but it is a separate instance: once placed it
 * becomes a marker, while the same glass written by its material stays a plain block.</p>
 */
public final class NamedMarkers {

    private static final Map<Material, BlockData> BLOCKS = new ConcurrentHashMap<>();

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
}
