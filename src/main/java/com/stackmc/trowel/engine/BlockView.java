package com.stackmc.trowel.engine;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** Reads blocks, frozen or not. Outside of what was read, everything is air. */
public interface BlockView {

    BlockData get(int x, int y, int z);

    default Material type(int x, int y, int z) {
        return get(x, y, z).getMaterial();
    }

    default boolean air(int x, int y, int z) {
        return type(x, y, z).isAir();
    }

    default boolean solid(int x, int y, int z) {
        return type(x, y, z).isSolid();
    }

    /** A real marker, placed as one: not a block that merely has its material. */
    default boolean marker(int x, int y, int z) {
        return false;
    }

    /** Touches air on at least one face. */
    default boolean exposed(int x, int y, int z) {
        return air(x + 1, y, z) || air(x - 1, y, z) || air(x, y + 1, z)
                || air(x, y - 1, z) || air(x, y, z + 1) || air(x, y, z - 1);
    }
}
