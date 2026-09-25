package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Keys;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Ways to read blocks. */
public final class Views {

    private Views() {
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Chunks frozen when the operation starts.
     *
     * <p>That is what allows computing off the main thread: a {@link ChunkSnapshot} can be read
     * from any thread, the world cannot.</p>
     */
    public static BlockView snapshot(Long2ObjectMap<ChunkSnapshot> chunks, int minY, int maxY, BlockData air,
                                     LongSet markers) {
        return new BlockView() {
            @Override
            public boolean marker(int x, int y, int z) {
                return markers.contains(Keys.pack(x, y, z));
            }

            @Override
            public BlockData get(int x, int y, int z) {
                if (y < minY || y > maxY) {
                    return air;
                }
                ChunkSnapshot chunk = chunks.get(chunkKey(x >> 4, z >> 4));
                return chunk == null ? air : chunk.getBlockData(x & 15, y, z & 15);
            }

            @Override
            public Material type(int x, int y, int z) {
                if (y < minY || y > maxY) {
                    return Material.AIR;
                }
                ChunkSnapshot chunk = chunks.get(chunkKey(x >> 4, z >> 4));
                return chunk == null ? Material.AIR : chunk.getBlockType(x & 15, y, z & 15);
            }
        };
    }

    /**
     * The same view, where markers are air.
     *
     * <p>For relief algorithms: a marker (a stained glass placed above the ground) is neither a
     * ground nor a material to copy. Without this, flattening or smoothing took a marker for
     * the surface, and raising or eroding copied its glass elsewhere, creating new markers
     * without settings.</p>
     */
    public static BlockView hideMarkers(BlockView base, BlockData air) {
        return new BlockView() {
            @Override
            public BlockData get(int x, int y, int z) {
                return base.marker(x, y, z) ? air : base.get(x, y, z);
            }

            @Override
            public Material type(int x, int y, int z) {
                return base.marker(x, y, z) ? Material.AIR : base.type(x, y, z);
            }
        };
    }

    /** A view, with what a previous pass already changed on top. */
    public static BlockView overlay(BlockView base, Long2ObjectMap<BlockData> changes) {
        return new BlockView() {
            @Override
            public BlockData get(int x, int y, int z) {
                BlockData changed = changes.get(Keys.pack(x, y, z));
                return changed != null ? changed : base.get(x, y, z);
            }

            @Override
            public boolean marker(int x, int y, int z) {
                return !changes.containsKey(Keys.pack(x, y, z)) && base.marker(x, y, z);
            }
        };
    }

    /** The world itself. Main thread only: used for previews. */
    public static BlockView live(World world) {
        return live(world, LongSets.EMPTY_SET);
    }

    /** The world itself, with the positions of its real markers. Main thread only. */
    public static BlockView live(World world, LongSet markers) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        return new BlockView() {
            @Override
            public boolean marker(int x, int y, int z) {
                return markers.contains(Keys.pack(x, y, z));
            }

            @Override
            public BlockData get(int x, int y, int z) {
                return world.getBlockAt(x, Math.max(minY, Math.min(maxY, y)), z).getBlockData();
            }

            @Override
            public Material type(int x, int y, int z) {
                if (y < minY || y > maxY) {
                    return Material.AIR;
                }
                return world.getBlockAt(x, y, z).getType();
            }
        };
    }
}
