package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.geom.Shapes;
import com.stackmc.trowel.pattern.Mask;
import com.stackmc.trowel.pattern.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.GlassPane;
import org.bukkit.block.data.type.Wall;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Operations on a selection. Each returns a computation, run off the main thread. */
public final class RegionOps {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final Set<Material> NATURAL = EnumSet.of(Material.STONE, Material.DIRT, Material.GRASS_BLOCK,
            Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM, Material.ANDESITE,
            Material.DIORITE, Material.GRANITE, Material.DEEPSLATE, Material.TUFF);

    private RegionOps() {
    }

    @FunctionalInterface
    private interface CellVisitor {
        void visit(int x, int y, int z);
    }

    private static void each(EditContext context, Box box, CellVisitor visitor) {
        Progress progress = context.progress();
        progress.phase("computing", box.width());
        for (int x = box.minX(); x <= box.maxX(); x++) {
            progress.tick(1);
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    visitor.visit(x, y, z);
                }
            }
        }
    }

    public static Engine.Compute set(Box box, Pattern pattern) {
        return context -> {
            ChangeSet changes = context.changes();
            Pattern within = pattern.within(box.minY(), box.maxY());
            each(context, box, (x, y, z) -> changes.set(x, y, z, within.at(x, y, z)));
            return changes;
        };
    }

    public static Engine.Compute replace(Box box, Mask mask, Pattern pattern) {
        return context -> {
            ChangeSet changes = context.changes();
            Pattern within = pattern.within(box.minY(), box.maxY());
            each(context, box, (x, y, z) -> {
                if (mask.test(x, y, z, context.view())) {
                    changes.set(x, y, z, within.at(x, y, z));
                }
            });
            return changes;
        };
    }

    public static Engine.Compute walls(Box box, Pattern pattern) {
        return context -> {
            ChangeSet changes = context.changes();
            Pattern within = pattern.within(box.minY(), box.maxY());
            each(context, box, (x, y, z) -> {
                if (x == box.minX() || x == box.maxX() || z == box.minZ() || z == box.maxZ()) {
                    changes.set(x, y, z, within.at(x, y, z));
                }
            });
            return changes;
        };
    }

    public static Engine.Compute outline(Box box, Pattern pattern) {
        return context -> {
            ChangeSet changes = context.changes();
            Pattern within = pattern.within(box.minY(), box.maxY());
            each(context, box, (x, y, z) -> {
                if (x == box.minX() || x == box.maxX() || z == box.minZ() || z == box.maxZ()
                        || y == box.minY() || y == box.maxY()) {
                    changes.set(x, y, z, within.at(x, y, z));
                }
            });
            return changes;
        };
    }

    /** Places the pattern on the top block of each column, {@code depth} layers deep. */
    public static Engine.Compute overlay(Box box, Pattern pattern, int depth) {
        return context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.terrain();
            Pattern within = pattern.within(box.minY(), box.maxY() + depth);
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    for (int y = box.maxY(); y >= box.minY(); y--) {
                        if (!view.air(x, y, z)) {
                            for (int i = 1; i <= depth; i++) {
                                if (view.air(x, y + i, z)) {
                                    changes.set(x, y + i, z, within.at(x, y + i, z));
                                }
                            }
                            break;
                        }
                    }
                }
            }
            return changes;
        };
    }

    public static Engine.Compute center(Box box, Pattern pattern) {
        return context -> {
            ChangeSet changes = context.changes();
            for (int x = Math.floorDiv(box.minX() + box.maxX(), 2); x <= Math.floorDiv(box.minX() + box.maxX() + 1, 2); x++) {
                for (int y = Math.floorDiv(box.minY() + box.maxY(), 2); y <= Math.floorDiv(box.minY() + box.maxY() + 1, 2); y++) {
                    for (int z = Math.floorDiv(box.minZ() + box.maxZ(), 2); z <= Math.floorDiv(box.minZ() + box.maxZ() + 1, 2); z++) {
                        changes.set(x, y, z, pattern.at(x, y, z));
                    }
                }
            }
            return changes;
        };
    }

    /**
     * Hollows solid volumes, keeping a shell {@code thickness} blocks thick.
     *
     * <p>Distance to air spreads from the blocks touching it: everything farther than the
     * thickness is the inside.</p>
     */
    public static Engine.Compute hollow(Box box, int thickness, Pattern fill) {
        return context -> {
            BlockView view = context.view();
            int w = box.width();
            int h = box.height();
            int[] distance = new int[(int) box.volume()];
            java.util.Arrays.fill(distance, -1);
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            each(context, box, (x, y, z) -> {
                if (!view.air(x, y, z) && view.exposed(x, y, z)) {
                    distance[index(box, w, h, x, y, z)] = 0;
                    queue.add(new int[]{x, y, z});
                }
            });
            while (!queue.isEmpty()) {
                int[] cell = queue.poll();
                int d = distance[index(box, w, h, cell[0], cell[1], cell[2])];
                if (d >= thickness) {
                    continue;
                }
                for (int[] n : NEIGHBOURS) {
                    int x = cell[0] + n[0];
                    int y = cell[1] + n[1];
                    int z = cell[2] + n[2];
                    if (!box.contains(x, y, z) || view.air(x, y, z)) {
                        continue;
                    }
                    int i = index(box, w, h, x, y, z);
                    if (distance[i] < 0) {
                        distance[i] = d + 1;
                        queue.add(new int[]{x, y, z});
                    }
                }
            }
            ChangeSet changes = context.changes();
            Pattern within = fill.within(box.minY(), box.maxY());
            each(context, box, (x, y, z) -> {
                int d = distance[index(box, w, h, x, y, z)];
                if (!view.air(x, y, z) && (d < 0 || d >= thickness)) {
                    changes.set(x, y, z, within.at(x, y, z));
                }
            });
            return changes;
        };
    }

    private static int index(Box box, int w, int h, int x, int y, int z) {
        return (x - box.minX()) + w * ((y - box.minY()) + h * (z - box.minZ()));
    }

    /** Grass on the surface, three layers of dirt, then stone, on natural blocks. */
    public static Engine.Compute naturalize(Box box) {
        return context -> {
            BlockData grass = Material.GRASS_BLOCK.createBlockData();
            BlockData dirt = Material.DIRT.createBlockData();
            BlockData stone = Material.STONE.createBlockData();
            ChangeSet changes = context.changes();
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    int depth = 0;
                    for (int y = box.maxY(); y >= box.minY(); y--) {
                        Material type = context.view().type(x, y, z);
                        if (!type.isSolid()) {
                            depth = 0;
                            continue;
                        }
                        depth++;
                        if (NATURAL.contains(type)) {
                            changes.set(x, y, z, depth == 1 ? grass : depth <= 4 ? dirt : stone);
                        }
                    }
                }
            }
            return changes;
        };
    }

    /** Smooths the relief of the selection, column by column. */
    public static Engine.Compute smooth(Box box, int iterations, Mask mask) {
        return context -> {
            BlockView view = context.terrain();
            int w = box.width();
            int d = box.depth();
            double[][] heights = new double[w][d];
            int[][] original = new int[w][d];
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < d; j++) {
                    int x = box.minX() + i;
                    int z = box.minZ() + j;
                    int h = Terrain.surface(view, x, z, box.maxY(), box.minY());
                    original[i][j] = h == Terrain.NONE && view.solid(x, box.maxY(), z) ? box.maxY() : h;
                    heights[i][j] = original[i][j];
                }
            }
            for (int pass = 0; pass < iterations; pass++) {
                heights = blur(heights, original);
            }
            ChangeSet changes = context.changes();
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < d; j++) {
                    int h = original[i][j];
                    if (h == Terrain.NONE) {
                        continue;
                    }
                    int x = box.minX() + i;
                    int z = box.minZ() + j;
                    if (mask != null && !mask.test(x, h, z, context.view())) {
                        continue;
                    }
                    int target = Math.max(box.minY(), Math.min(box.maxY(), (int) Math.round(heights[i][j])));
                    Terrain.reshape(changes, view, x, z, h, target, null, context.air());
                }
            }
            return changes;
        };
    }

    /** A 3x3 gaussian blur on a height map; empty columns do not count. */
    public static double[][] blur(double[][] heights, int[][] valid) {
        int w = heights.length;
        int d = heights[0].length;
        double[][] out = new double[w][d];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < d; j++) {
                if (valid[i][j] == Terrain.NONE) {
                    out[i][j] = heights[i][j];
                    continue;
                }
                double sum = 0;
                double weight = 0;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int a = i + di;
                        int b = j + dj;
                        if (a < 0 || b < 0 || a >= w || b >= d || valid[a][b] == Terrain.NONE) {
                            continue;
                        }
                        double k = (di == 0 ? 2 : 1) * (dj == 0 ? 2 : 1);
                        sum += heights[a][b] * k;
                        weight += k;
                    }
                }
                out[i][j] = sum / weight;
            }
        }
        return out;
    }

    /**
     * Changes wood type keeping the shape: {@code oak} to {@code spruce} changes oak stairs,
     * slabs, fences and doors, same facing and same state.
     */
    public static Engine.Compute typeReplace(Box box, String from, String to) {
        String source = from.toLowerCase(java.util.Locale.ROOT);
        String target = to.toLowerCase(java.util.Locale.ROOT);
        return context -> {
            Map<String, BlockData> cache = new HashMap<>();
            java.util.Set<String> impossible = new java.util.HashSet<>();
            ChangeSet changes = context.changes();
            each(context, box, (x, y, z) -> {
                BlockData data = context.view().get(x, y, z);
                if (data.getMaterial().isAir()) {
                    return;
                }
                String text = data.getAsString();
                int bracket = text.indexOf('[');
                String full = bracket < 0 ? text : text.substring(0, bracket);
                int colon = full.indexOf(':');
                String namespace = colon < 0 ? "" : full.substring(0, colon + 1);
                String id = full.substring(colon + 1);
                if (!id.contains(source) || impossible.contains(text)) {
                    return;
                }
                BlockData swapped = cache.get(text);
                if (swapped == null) {
                    String state = bracket < 0 ? "" : text.substring(bracket);
                    String newId = namespace + id.replace(source, target);
                    swapped = parse(newId + state);
                    if (swapped == null) {
                        swapped = parse(newId);
                    }
                    if (swapped == null) {
                        impossible.add(text);
                        return;
                    }
                    cache.put(text, swapped);
                }
                changes.set(x, y, z, swapped);
            });
            return changes;
        };
    }

    private static BlockData parse(String text) {
        try {
            return Bukkit.createBlockData(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Redoes the connections of fences, panes, bars and walls.
     *
     * <p>Blocks placed without physics keep the state they are given: a fence pasted alone in
     * the air stays connected to neighbours that do not exist. Like Arceon's Fix Connect,
     * every block is connected to what really surrounds it.</p>
     */
    public static Engine.Compute fixConnections(Box box) {
        return context -> {
            BlockView view = context.terrain();
            ChangeSet changes = context.changes();
            each(context, box, (x, y, z) -> {
                BlockData data = view.get(x, y, z);
                if (!(data instanceof Fence) && !(data instanceof GlassPane) && !(data instanceof Wall)) {
                    return;
                }
                BlockData copy = data.clone();
                boolean[] links = new boolean[4];
                for (int i = 0; i < SIDES.length; i++) {
                    BlockFace face = SIDES[i];
                    BlockData near = view.get(x + face.getModX(), y, z + face.getModZ());
                    links[i] = connects(copy, near, face);
                    if (copy instanceof Fence fence && fence.getAllowedFaces().contains(face)) {
                        fence.setFace(face, links[i]);
                    } else if (copy instanceof GlassPane pane && pane.getAllowedFaces().contains(face)) {
                        pane.setFace(face, links[i]);
                    } else if (copy instanceof Wall wall) {
                        wall.setHeight(face, links[i] ? Wall.Height.LOW : Wall.Height.NONE);
                    }
                }
                if (copy instanceof Wall wall) {
                    boolean straight = (links[0] && links[2] && !links[1] && !links[3])
                            || (links[1] && links[3] && !links[0] && !links[2]);
                    wall.setUp(!straight || !view.air(x, y + 1, z));
                }
                if (!copy.equals(data)) {
                    changes.set(x, y, z, copy);
                }
            });
            return changes;
        };
    }

    private static boolean connects(BlockData self, BlockData near, BlockFace face) {
        Material type = near.getMaterial();
        if (type.isAir()) {
            return false;
        }
        String mine = family(self);
        if (near instanceof Gate gate) {
            BlockFace facing = gate.getFacing();
            boolean gateAlongX = facing == BlockFace.EAST || facing == BlockFace.WEST;
            boolean faceAlongX = face == BlockFace.EAST || face == BlockFace.WEST;
            return gateAlongX != faceAlongX && !mine.equals("pane");
        }
        String theirs = family(near);
        if (theirs != null) {
            return mine.equals(theirs) || (mine.equals("pane") && theirs.equals("wall"))
                    || (mine.equals("wall") && theirs.equals("pane"));
        }
        if (Tag.LEAVES.isTagged(type) || type == Material.BARRIER || Tag.SHULKER_BOXES.isTagged(type)
                || type == Material.PUMPKIN || type == Material.CARVED_PUMPKIN || type == Material.JACK_O_LANTERN
                || type == Material.MELON) {
            return false;
        }
        return near.isFaceSturdy(face.getOppositeFace(), BlockSupport.FULL);
    }

    /** What connects to what: panes to bars and walls, fences to each other. */
    private static String family(BlockData data) {
        Material type = data.getMaterial();
        if (type == Material.IRON_BARS || data instanceof GlassPane) {
            return "pane";
        }
        if (data instanceof Wall) {
            return "wall";
        }
        if (data instanceof Fence) {
            return type == Material.NETHER_BRICK_FENCE ? "nether" : "wood";
        }
        return null;
    }

    /** Moves the selection: the old place empties, markers follow with their settings. */
    public static Engine.Compute move(Box box, int dx, int dy, int dz) {
        return context -> {
            ChangeSet changes = context.changes();
            each(context, box, (x, y, z) -> changes.set(x, y, z, context.air()));
            each(context, box, (x, y, z) -> copyCell(context, changes, x, y, z, dx, dy, dz, false));
            return changes;
        };
    }

    /** Repeats the selection {@code count} times in this direction, stuck to itself. */
    public static Engine.Compute stack(Box box, int count, int ux, int uy, int uz, boolean skipAir) {
        return context -> {
            ChangeSet changes = context.changes();
            for (int i = 1; i <= count; i++) {
                int dx = ux * box.width() * i;
                int dy = uy * box.height() * i;
                int dz = uz * box.depth() * i;
                each(context, box, (x, y, z) -> copyCell(context, changes, x, y, z, dx, dy, dz, skipAir));
            }
            return changes;
        };
    }

    private static void copyCell(EditContext context, ChangeSet changes, int x, int y, int z,
                                 int dx, int dy, int dz, boolean skipAir) {
        BlockData data = context.view().get(x, y, z);
        if (skipAir && data.getMaterial().isAir()) {
            return;
        }
        long target = Keys.pack(x + dx, y + dy, z + dz);
        changes.set(target, data);
        if (context.view().marker(x, y, z)) {
            changes.params(target, context.paramsAt(Keys.pack(x, y, z)));
        }
    }

    /** A generated shape, filled with the pattern. */
    public static Engine.Compute shape(Pattern pattern, Box bounds, Consumer<Shapes.Cell> generator) {
        return context -> {
            ChangeSet changes = context.changes();
            Pattern within = pattern.within(bounds.minY(), bounds.maxY());
            generator.accept((x, y, z) -> changes.set(x, y, z, within.at(x, y, z)));
            return changes;
        };
    }

    public static long count(EditContext context, Box box, Mask mask) {
        long[] count = {0};
        each(context, box, (x, y, z) -> {
            if (mask.test(x, y, z, context.view())) {
                count[0]++;
            }
        });
        return count[0];
    }

    public static Map<Material, Long> distribution(EditContext context, Box box) {
        Map<Material, Long> counts = new EnumMap<>(Material.class);
        each(context, box, (x, y, z) -> counts.merge(context.view().type(x, y, z), 1L, Long::sum));
        return counts;
    }
}
