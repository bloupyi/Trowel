package com.stackmc.trowel.spline;

import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.pattern.Colors;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Snow;
import org.bukkit.block.data.type.Stairs;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shaping blocks on the surface of a shape, like ezEdits smoothblocks ({@code -w Slabs}).
 *
 * <p>Each block is sampled at eight points (2 x 2 x 2). Fully covered blocks stay full, blocks
 * barely touched stay empty, and the ones in between become the shaping block that matches the
 * covered part: a bottom or top slab, a stair facing its full side, a pane, snow layers. The
 * shaping block is the variant of the placed block ({@code stone} gives {@code stone_slab}), or
 * the variant closest in color when there is none.</p>
 */
public final class Smoothblocks {

    public enum Profile {
        SLABS, SLABS_AND_STAIRS, SLABS_AND_STAIRS_2D, PANES, LAYERS;

        static Profile parse(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT).replace("_", "")) {
                case "slabs", "slab" -> SLABS;
                case "slabsandstairs", "stairs" -> SLABS_AND_STAIRS;
                case "slabsandstairs2d", "stairs2d" -> SLABS_AND_STAIRS_2D;
                case "panes", "pane" -> PANES;
                case "layers", "layer", "snow" -> LAYERS;
                default -> throw new IllegalArgumentException("-w: Slabs, SlabsAndStairs, SlabsAndStairs2D, Panes or Layers.");
            };
        }
    }

    enum Kind { SLAB, STAIRS, PANE }

    /**
     * @param coverage  0: only full blocks, 1: every surface block becomes a shaping block
     * @param overrides material per shaping block ({@code slab} gives {@code acacia}), may be empty
     */
    public record Spec(Profile profile, double coverage, Map<String, String> overrides) {

        /** {@code Slabs}, {@code Slabs(Slab:acacia,Coverage:0.3)}. */
        public static Spec parse(String raw) {
            String text = raw.trim();
            int open = text.indexOf('(');
            Profile profile = Profile.parse(open < 0 ? text : text.substring(0, open));
            double coverage = 0.5;
            Map<String, String> overrides = new LinkedHashMap<>();
            if (open >= 0) {
                if (!text.endsWith(")")) {
                    throw new IllegalArgumentException("-w: missing closing parenthesis.");
                }
                for (String part : text.substring(open + 1, text.length() - 1).split(",")) {
                    int colon = part.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String key = part.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = part.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
                    if (key.equals("coverage") || key.equals("c")) {
                        try {
                            coverage = Math.max(0, Math.min(1, Double.parseDouble(value)));
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("-w: coverage takes a number from 0 to 1.");
                        }
                    } else {
                        overrides.put(key.replaceAll("s$", ""), value);
                    }
                }
            }
            return new Spec(profile, coverage, overrides);
        }
    }

    /** What a sampled block looks like: which of its eight sample points are inside, and its full block. */
    record Cell(int bits, BlockData full) {
    }

    private static final Map<String, BlockData> VARIANTS = new ConcurrentHashMap<>();

    private Smoothblocks() {
    }

    /** Index of a sample point: bit 0 for +x, bit 1 for +y, bit 2 for +z. */
    static int bit(boolean px, boolean py, boolean pz) {
        return (px ? 1 : 0) | (py ? 2 : 0) | (pz ? 4 : 0);
    }

    /** The blocks to place, shaping blocks included. */
    static Long2ObjectOpenHashMap<BlockData> shape(Long2ObjectOpenHashMap<Cell> cells, Spec spec) {
        double fullAt = 0.5 + spec.coverage() / 2;
        double shapeAt = 0.5 - spec.coverage() / 2;
        Long2ObjectOpenHashMap<BlockData> out = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<Cell> entry : cells.long2ObjectEntrySet()) {
            Cell cell = entry.getValue();
            int count = Integer.bitCount(cell.bits());
            double fraction = count / 8.0;
            if (count == 8 || fraction >= fullAt && fullAt < 1) {
                out.put(entry.getLongKey(), cell.full());
                continue;
            }
            if (fraction < shapeAt || count == 0) {
                continue;
            }
            BlockData shaped = shaped(cell, spec);
            if (shaped != null) {
                out.put(entry.getLongKey(), shaped);
            } else if (fraction >= 0.5) {
                out.put(entry.getLongKey(), cell.full());
            }
        }
        if (spec.profile() == Profile.PANES) {
            connect(out);
        }
        return out;
    }

    private static BlockData shaped(Cell cell, Spec spec) {
        int bits = cell.bits();
        int lower = Integer.bitCount(bits & 0b00110011);
        int upper = Integer.bitCount(bits & 0b11001100);
        return switch (spec.profile()) {
            case PANES -> variant(cell.full(), Kind.PANE, spec);
            case LAYERS -> {
                if (upper > 0 || !(Material.SNOW.createBlockData() instanceof Snow snow)) {
                    yield null;
                }
                snow.setLayers(Math.max(1, Math.min(snow.getMaximumLayers(), lower * 2)));
                yield snow;
            }
            case SLABS_AND_STAIRS, SLABS_AND_STAIRS_2D -> {
                BlockData stair = stair(cell, bits, lower, upper, spec);
                yield stair != null ? stair : slab(cell, lower, upper, spec);
            }
            case SLABS -> slab(cell, lower, upper, spec);
        };
    }

    private static BlockData slab(Cell cell, int lower, int upper, Spec spec) {
        if (lower == upper) {
            return null;
        }
        BlockData data = variant(cell.full(), Kind.SLAB, spec);
        if (!(data instanceof Slab slab)) {
            return null;
        }
        slab.setType(lower > upper ? Slab.Type.BOTTOM : Slab.Type.TOP);
        return slab;
    }

    /** A straight stair: one half full, and two neighbouring samples of the other half. */
    private static BlockData stair(Cell cell, int bits, int lower, int upper, Spec spec) {
        boolean bottom = lower == 4 && upper == 2;
        boolean top = upper == 4 && lower == 2;
        if (!bottom && !top) {
            return null;
        }
        int y = bottom ? 1 : 0;
        BlockFace facing = null;
        if (has(bits, 1, y, 0) && has(bits, 1, y, 1)) {
            facing = BlockFace.EAST;
        } else if (has(bits, 0, y, 0) && has(bits, 0, y, 1)) {
            facing = BlockFace.WEST;
        } else if (has(bits, 0, y, 1) && has(bits, 1, y, 1)) {
            facing = BlockFace.SOUTH;
        } else if (has(bits, 0, y, 0) && has(bits, 1, y, 0)) {
            facing = BlockFace.NORTH;
        }
        if (facing == null) {
            return null;
        }
        BlockData data = variant(cell.full(), Kind.STAIRS, spec);
        if (!(data instanceof Stairs stairs)) {
            return null;
        }
        stairs.setFacing(facing);
        stairs.setHalf(bottom ? Bisected.Half.BOTTOM : Bisected.Half.TOP);
        return stairs;
    }

    private static boolean has(int bits, int x, int y, int z) {
        return (bits & (1 << bit(x == 1, y == 1, z == 1))) != 0;
    }

    /** Panes connect to whatever stands next to them. */
    private static void connect(Long2ObjectOpenHashMap<BlockData> blocks) {
        for (Long2ObjectMap.Entry<BlockData> entry : blocks.long2ObjectEntrySet()) {
            if (!(entry.getValue() instanceof MultipleFacing facing)) {
                continue;
            }
            MultipleFacing connected = (MultipleFacing) facing.clone();
            long key = entry.getLongKey();
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                if (connected.getAllowedFaces().contains(face)) {
                    connected.setFace(face, blocks.containsKey(Keys.offset(key, face.getModX(), 0, face.getModZ())));
                }
            }
            entry.setValue(connected);
        }
    }

    /** The shaping block of this kind for this block, cached: it is asked for every surface block. */
    static BlockData variant(BlockData full, Kind kind, Spec spec) {
        String override = spec.overrides().get(kind.name().toLowerCase(Locale.ROOT));
        String cacheKey = kind + ":" + (override != null ? override : full.getMaterial().name());
        BlockData cached = VARIANTS.computeIfAbsent(cacheKey, key -> {
            Material material = override != null ? named(override, kind) : named(full.getMaterial(), kind);
            if (material == null && override == null) {
                material = closest(full.getMaterial(), kind);
            }
            return material == null ? full : material.createBlockData();
        });
        return cached.clone();
    }

    private static Material named(String name, Kind kind) {
        Material direct = Material.matchMaterial(name);
        if (direct != null && direct.isBlock() && matches(direct, kind)) {
            return direct;
        }
        Material block = direct != null ? direct : Material.matchMaterial(name + "_planks");
        return block == null ? null : named(block, kind);
    }

    private static Material named(Material material, Kind kind) {
        if (matches(material, kind)) {
            return material;
        }
        String name = material.getKey().getKey();
        String base = name.replaceAll("_planks$", "").replaceAll("_bricks$", "_brick").replaceAll("_tiles$", "_tile")
                .replaceAll("_block$", "");
        String[] tries = switch (kind) {
            case SLAB -> new String[]{base + "_slab", name + "_slab"};
            case STAIRS -> new String[]{base + "_stairs", name + "_stairs"};
            case PANE -> name.equals("iron_block") ? new String[]{"iron_bars"} : new String[]{name + "_pane", base + "_pane"};
        };
        for (String attempt : tries) {
            Material found = Material.matchMaterial(attempt);
            if (found != null && found.isBlock()) {
                return found;
            }
        }
        return null;
    }

    private static boolean matches(Material material, Kind kind) {
        String name = material.name();
        return switch (kind) {
            case SLAB -> name.endsWith("_SLAB");
            case STAIRS -> name.endsWith("_STAIRS");
            case PANE -> name.endsWith("_PANE") || material == Material.IRON_BARS;
        };
    }

    /** The variant closest in color, when the block has none (clay gets the closest slab). */
    private static Material closest(Material material, Kind kind) {
        int rgb = Colors.rgb(material);
        if (rgb < 0) {
            return null;
        }
        Material best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Material candidate : Material.values()) {
            if (candidate.isLegacy() || !candidate.isBlock() || !matches(candidate, kind)
                    || candidate.name().startsWith("PETRIFIED")) {
                continue;
            }
            int color = Colors.rgb(candidate);
            if (color < 0) {
                continue;
            }
            double distance = Colors.distance(rgb, color);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
