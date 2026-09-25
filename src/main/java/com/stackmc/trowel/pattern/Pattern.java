package com.stackmc.trowel.pattern;

import com.stackmc.trowel.api.Box;
import org.bukkit.block.data.BlockData;

/**
 * What to place at a position. Called off the main thread.
 *
 * <p>{@code null} means: place nothing here (a negative expression, a gap in the pattern).</p>
 */
@FunctionalInterface
public interface Pattern {

    BlockData at(int x, int y, int z);

    /** The block, knowing where it sits in the shape that places it. */
    default BlockData at(int x, int y, int z, Local local) {
        return at(x, y, z);
    }

    /** The same pattern, for an operation spanning {@code minY} to {@code maxY}. */
    default Pattern within(int minY, int maxY) {
        return this;
    }

    /** The same pattern, for an operation covering this box: gradients, normalized coordinates. */
    default Pattern within(Box box) {
        return within(box.minY(), box.maxY());
    }
}
