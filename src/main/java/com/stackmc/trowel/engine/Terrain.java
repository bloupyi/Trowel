package com.stackmc.trowel.engine;

import com.stackmc.trowel.pattern.Pattern;
import org.bukkit.block.data.BlockData;

/**
 * Reads and shapes the relief column by column.
 *
 * <p>A column has a height: its highest solid block with room above it. Changing that height
 * keeps the top block on top (grass stays grass) and fills with the one below (dirt), as
 * goBrush and WorldEdit smoothing do.</p>
 */
public final class Terrain {

    public static final int NONE = Integer.MIN_VALUE;

    private Terrain() {
    }

    public static int surface(BlockView view, int x, int z, int top, int bottom) {
        for (int y = top; y >= bottom; y--) {
            if (view.solid(x, y, z) && !view.solid(x, y + 1, z)) {
                return y;
            }
        }
        return NONE;
    }

    /** Slope of the relief around this block, in degrees: 0 when flat, 45 for one step per block. */
    public static double slope(BlockView view, int x, int y, int z) {
        double gx = (near(view, x + 1, z, y) - near(view, x - 1, z, y)) / 2.0;
        double gz = (near(view, x, z + 1, y) - near(view, x, z - 1, y)) / 2.0;
        return Math.toDegrees(Math.atan(Math.sqrt(gx * gx + gz * gz)));
    }

    private static int near(BlockView view, int x, int z, int y) {
        int h = surface(view, x, z, y + 4, y - 4);
        return h == NONE ? y : h;
    }

    /**
     * Brings the column from {@code height} to {@code target}.
     *
     * @param fill what fills when rising, or {@code null} to reuse the terrain
     */
    public static void reshape(ChangeSet changes, BlockView view, int x, int z, int height, int target,
                               Pattern fill, BlockData air) {
        if (target == height) {
            return;
        }
        BlockData top = view.get(x, height, z);
        BlockData under = view.solid(x, height - 1, z) ? view.get(x, height - 1, z) : top;
        BlockData plant = view.get(x, height + 1, z);
        boolean hasPlant = !plant.getMaterial().isAir() && !plant.getMaterial().isSolid();

        if (target > height) {
            for (int y = height; y < target; y++) {
                if (y > height && view.solid(x, y, z)) {
                    continue;
                }
                changes.set(x, y, z, fill != null ? fill.at(x, y, z) : under);
            }
            changes.set(x, target, z, fill != null ? fill.at(x, target, z) : top);
        } else {
            for (int y = target + 1; y <= height; y++) {
                changes.set(x, y, z, air);
            }
            changes.set(x, target, z, top);
            if (hasPlant) {
                changes.set(x, height + 1, z, air);
            }
        }
        if (hasPlant && (target + 1 <= height || view.air(x, target + 1, z))) {
            changes.set(x, target + 1, z, plant);
        }
    }
}
