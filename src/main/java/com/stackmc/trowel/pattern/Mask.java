package com.stackmc.trowel.pattern;

import com.stackmc.trowel.engine.BlockView;

/** What an operation may touch, read from the block already in place. */
@FunctionalInterface
public interface Mask {

    boolean test(int x, int y, int z, BlockView view);

    default Mask and(Mask other) {
        return (x, y, z, view) -> test(x, y, z, view) && other.test(x, y, z, view);
    }

    default Mask negate() {
        return (x, y, z, view) -> !test(x, y, z, view);
    }
}
