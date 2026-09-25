package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.geom.Noise;
import com.stackmc.trowel.geom.NoiseSpec;
import com.stackmc.trowel.geom.Relief;
import com.stackmc.trowel.pattern.Mask;
import com.stackmc.trowel.pattern.Pattern;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generators: noise volumes, relief, vegetation, snow.
 *
 * <p>These are the most used tools of Arceon (Terragen, noise patterns), ezEdits (Noisegen,
 * Placement) and WorldEdit (flora, snow, thaw, green, fill) to sculpt scenery.</p>
 */
public final class Generators {

    private static final int[][] SPREAD = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};

    private Generators() {
    }

    /** What a generator places, given the noise value (above the threshold, brought into ]0, 1]). */
    @FunctionalInterface
    public interface Fill {
        BlockData at(int x, int y, int z, double value);
    }

    /**
     * Fills where the noise exceeds the threshold: rocks, floating islands, veins, clouds.
     *
     * @param threshold within [0, 1]: the higher, the rarer and smaller the shapes
     * @param onlyAir   only fills air, without touching what is already built
     */
    public static Engine.Compute noiseFill(Box box, Fill fill, NoiseSpec noise, double threshold, boolean onlyAir) {
        return context -> {
            ChangeSet changes = context.changes();
            context.progress().phase("computing", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        double v = noise.sample(x, y, z);
                        if (v < threshold) {
                            continue;
                        }
                        if (!onlyAir || context.view().air(x, y, z)) {
                            changes.set(x, y, z, fill.at(x, y, z, threshold >= 1 ? 1 : Math.max(1e-6,
                                    (v - threshold) / (1 - threshold))));
                        }
                    }
                }
            }
            return changes;
        };
    }

    /** Carves where the noise exceeds the threshold: caves, holes, sponge-like erosion. */
    public static Engine.Compute noiseCarve(Box box, NoiseSpec noise, double threshold) {
        return context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.terrain();
            context.progress().phase("computing", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        if (!view.air(x, y, z)
                                && noise.sample(x, y, z) >= threshold) {
                            changes.set(x, y, z, context.air());
                        }
                    }
                }
            }
            return changes;
        };
    }

    /**
     * Generates relief in the selection, like Arceon's Terragen.
     *
     * <p>The selection becomes terrain: below the surface, {@code under} for three layers then
     * {@code deep}; above, air. The height goes from the bottom to the top of the selection.</p>
     *
     * @param strength share of the selection height the relief occupies, in percent
     */
    public static Engine.Compute terrain(Box box, Relief relief, double scale, int strength,
                                         Pattern top, Pattern under, Pattern deep) {
        return context -> {
            ChangeSet changes = context.changes();
            double halfW = Math.max(1, box.width() / 2.0);
            double halfD = Math.max(1, box.depth() / 2.0);
            double centreX = (box.minX() + box.maxX()) / 2.0;
            double centreZ = (box.minZ() + box.maxZ()) / 2.0;
            context.progress().phase("computing", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    double h = relief.height(x, z, scale, (x - centreX) / halfW, (z - centreZ) / halfD)
                            * strength / 100.0;
                    int surface = box.minY() + (int) Math.round(h * (box.height() - 1));
                    boolean hole = relief == Relief.ISLANDS && h < 0.02;
                    for (int y = box.minY(); y <= box.maxY(); y++) {
                        BlockData data;
                        if (hole || y > surface) {
                            data = context.air();
                        } else if (y == surface) {
                            data = top.at(x, y, z);
                        } else if (y >= surface - 3) {
                            data = under.at(x, y, z);
                        } else {
                            data = deep.at(x, y, z);
                        }
                        changes.set(x, y, z, data);
                    }
                }
            }
            return changes;
        };
    }

    /** Adds noise to the height of the existing relief: it loses its smooth built look. */
    public static Engine.Compute roughen(Box box, NoiseSpec noise, int amplitude) {
        return context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.terrain();
            context.progress().phase("computing", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    int h = Terrain.surface(view, x, z, box.maxY(), box.minY());
                    if (h == Terrain.NONE) {
                        continue;
                    }
                    double n = noise.sample(x, 0, z) - 0.5;
                    int target = Math.max(box.minY(), Math.min(box.maxY(), h + (int) Math.round(n * 2 * amplitude)));
                    Terrain.reshape(changes, view, x, z, h, target, null, context.air());
                }
            }
            return changes;
        };
    }

    /**
     * Scatters on the surface: grass, flowers, pebbles (ezEdits Placement, WorldEdit flora).
     *
     * @param density chance to place, in percent, on each free column
     * @param on      what to scatter on, or {@code null} for anything
     */
    public static Engine.Compute scatter(Box box, Pattern pattern, int density, Mask on) {
        return context -> {
            ChangeSet changes = context.changes();
            Pattern within = pattern.within(box.minY(), box.maxY() + 1);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            context.progress().phase("computing", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    int top = Terrain.surface(context.terrain(), x, z, box.maxY(), box.minY());
                    if (top == Terrain.NONE || !context.view().air(x, top + 1, z) || random.nextInt(100) >= density) {
                        continue;
                    }
                    if (on == null || on.test(x, top, z, context.view())) {
                        changes.set(x, top + 1, z, within.at(x, top + 1, z));
                    }
                }
            }
            return changes;
        };
    }

    /** A layer of snow on every surface open to the sky. */
    public static Engine.Compute snow(Box box) {
        return context -> {
            ChangeSet changes = context.changes();
            BlockData snow = Material.SNOW.createBlockData();
            BlockView view = context.terrain();
            context.progress().phase("computing", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    int top = Terrain.surface(view, x, z, box.maxY(), box.minY());
                    if (top == Terrain.NONE || !context.view().air(x, top + 1, z)) {
                        continue;
                    }
                    Material ground = view.type(x, top, z);
                    if (ground == Material.ICE || ground == Material.PACKED_ICE || ground == Material.BARRIER) {
                        continue;
                    }
                    changes.set(x, top + 1, z, snow);
                }
            }
            return changes;
        };
    }

    /** Melts: snow disappears, ice turns back into water. */
    public static Engine.Compute thaw(Box box) {
        return context -> {
            ChangeSet changes = context.changes();
            BlockData water = Material.WATER.createBlockData();
            context.progress().phase("computing", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        Material type = context.view().type(x, y, z);
                        if (type == Material.SNOW) {
                            changes.set(x, y, z, context.air());
                        } else if (type == Material.ICE) {
                            changes.set(x, y, z, water);
                        }
                    }
                }
            }
            return changes;
        };
    }

    /** Dirt open to the sky turns into grass. */
    public static Engine.Compute green(Box box) {
        return context -> {
            ChangeSet changes = context.changes();
            BlockData grass = Material.GRASS_BLOCK.createBlockData();
            context.progress().phase("computing", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        if (context.view().type(x, y, z) == Material.DIRT && !context.view().solid(x, y + 1, z)) {
                            changes.set(x, y, z, grass);
                        }
                    }
                }
            }
            return changes;
        };
    }

    /**
     * Fills the hole you stand in, like WorldEdit's {@code //fill}: the void fills by spreading
     * horizontally and downwards, never upwards.
     *
     * <p>The void is whatever is not solid: grass or water at the bottom of the hole no longer
     * stops it. Markers do.</p>
     */
    public static Engine.Compute fillHole(int x, int y, int z, int radius, int depth, Pattern pattern) {
        return context -> {
            ChangeSet changes = context.changes();
            Pattern within = pattern.within(y - depth + 1, y);
            LongOpenHashSet seen = new LongOpenHashSet();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            long start = Keys.pack(x, y, z);
            if (!hollow(context, x, y, z)) {
                return changes;
            }
            seen.add(start);
            queue.enqueue(start);
            double limit = (radius + 0.5) * (radius + 0.5);
            while (!queue.isEmpty()) {
                long key = queue.dequeueLong();
                int cx = Keys.x(key);
                int cy = Keys.y(key);
                int cz = Keys.z(key);
                changes.set(cx, cy, cz, within.at(cx, cy, cz));
                for (int[] d : SPREAD) {
                    int nx = cx + d[0];
                    int ny = cy + d[1];
                    int nz = cz + d[2];
                    if (ny <= y - depth || (nx - x) * (nx - x) + (nz - z) * (nz - z) > limit) {
                        continue;
                    }
                    long next = Keys.pack(nx, ny, nz);
                    if (seen.add(next) && hollow(context, nx, ny, nz)) {
                        queue.enqueue(next);
                    }
                }
            }
            return changes;
        };
    }

    private static boolean hollow(EditContext context, int x, int y, int z) {
        return !context.view().type(x, y, z).isSolid() && !context.view().marker(x, y, z);
    }
}
