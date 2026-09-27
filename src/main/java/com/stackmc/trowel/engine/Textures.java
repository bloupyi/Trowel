package com.stackmc.trowel.engine;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.geom.NoiseSpec;
import com.stackmc.trowel.pattern.Mask;
import com.stackmc.trowel.pattern.Palette;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import org.bukkit.block.data.BlockData;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Texture a structure already built, like {@code //eztexture}: each block the mask selects
 * gets a value (light, hollows, slope, noise...), and the value picks its block in the palette.
 * The start of the palette goes to exposed or lit spots, the end to hollows and shade;
 * {@code -##palette} reverses it. Ambient follows ezEdits instead: hollows at the start, flat
 * surfaces in the middle, edges at the end.
 */
public final class Textures {

    public enum Kind {
        AMBIENT("Ambient: hollows at the start of the palette, edges at the end"),
        CURVATURE("Curvature: edges on one side, nooks on the other"),
        SUN("Sun: by orientation towards a direction, shadows possible"),
        LIGHT("Lamp: from your position"),
        AXIS("Axis: gradient along x, y or z"),
        NOISE("Tuned noise"),
        CELLS("Cells: one shade per cell"),
        DEPTH("Depth: distance to air"),
        SLOPE("Relief slope"),
        RANDOM("Random"),
        BLEND("Blend: softens transitions between palette blocks"),
        SHIFT("Shift: each palette block becomes its neighbour");

        private final String help;

        Kind(String help) {
            this.help = help;
        }

        public String help() {
            return help;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind parse(String raw) {
            String lower = raw.toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.id().equals(lower)) {
                    return kind;
                }
            }
            return switch (lower) {
                case "ao", "occlusion" -> AMBIENT;
                case "curve" -> CURVATURE;
                case "sunlight" -> SUN;
                case "pointlight", "point" -> LIGHT;
                case "axisgradient", "gradient", "height" -> AXIS;
                case "voronoi" -> CELLS;
                default -> throw new IllegalArgumentException("Textures: " + String.join(", ",
                        Arrays.stream(values()).map(Kind::id).toList()) + ".");
            };
        }
    }

    /**
     * @param radius     analysis radius (occlusion, curvature, normal)
     * @param brightness shifts towards the start (positive) or the end (negative) of the palette
     * @param contrast   sharpens (positive) or softens (negative) the differences
     * @param vector     direction of the sun, or position of the lamp
     * @param interval   angles (degrees) covered by the palette, for sun and lamp
     * @param shadows    strength of cast shadows (0: none)
     * @param axis       x, y or z
     * @param relative   gradient per column rather than over the whole selection
     * @param amount     number of cells, or offset for {@code shift}
     * @param dither     dithering, to blend the bands (0: sharp)
     */
    public record Settings(Kind kind, double radius, double brightness, double contrast, double[] vector,
                           double[] interval, double shadows, char axis, boolean relative, double amount,
                           NoiseSpec noise, double range, double dither) {
    }

    private Textures() {
    }

    public static Engine.Compute texture(Box region, Mask mask, Palette palette, Settings s) {
        return context -> {
            int margin = (int) Math.ceil(Math.max(1, s.radius())) + 1;
            Box box = region.grow(margin);
            Sculpt.Grid grid = new Sculpt.Grid(context, box);
            BlockView real = context.view();
            ChangeSet changes = context.changes();
            int n = grid.solid.length;
            int[] sat = s.kind() == Kind.CURVATURE ? summed(grid) : null;
            int[] depth = s.kind() == Kind.DEPTH ? depth(grid, (int) Math.max(1, s.amount())) : null;
            NoiseSpec cells = s.kind() == Kind.CELLS
                    ? NoiseSpec.parse("cellular(cr:cell)", 1 / Math.max(1, Math.cbrt(region.volume() / Math.max(1, s.amount()))))
                    : null;
            java.util.Map<BlockData, Integer> indexOf = new java.util.HashMap<>();
            List<BlockData> blocks = palette.blocks();
            for (int i = blocks.size() - 1; i >= 0; i--) {
                indexOf.put(blocks.get(i), i);
            }
            int pn = palette.size();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            context.progress().phase("texture", region.width());
            for (int x = region.minX(); x <= region.maxX(); x++) {
                context.progress().tick(1);
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    for (int z = region.minZ(); z <= region.maxZ(); z++) {
                        int i = grid.index(x, y, z);
                        if (!grid.solid[i] || (mask != null && !mask.test(x, y, z, real))) {
                            continue;
                        }
                        if (s.kind() == Kind.SHIFT || s.kind() == Kind.BLEND) {
                            Integer index = indexOf.get(grid.data[i]);
                            if (index == null) {
                                continue;
                            }
                            int next = s.kind() == Kind.SHIFT ? index + (int) Math.round(s.amount())
                                    : blend(grid, indexOf, x, y, z, (int) Math.max(1, s.radius()), index);
                            BlockData out = palette.get(next);
                            if (!out.equals(grid.data[i])) {
                                changes.set(x, y, z, out);
                            }
                            continue;
                        }
                        double v = switch (s.kind()) {
                            case AMBIENT -> ambient(grid, x, y, z, s.radius());
                            case CURVATURE -> {
                                double occlusion = occlusion(grid, sat, x, y, z, (int) Math.max(1, s.radius()));
                                yield Math.max(0, Math.min(1, 0.5 + (occlusion - 0.5) * 3));
                            }
                            case SUN -> light(grid, x, y, z, s, s.vector(), false);
                            case LIGHT -> light(grid, x, y, z, s, s.vector(), true);
                            case AXIS -> axis(grid, region, x, y, z, s);
                            case NOISE -> s.noise().sample(x, y, z);
                            case CELLS -> cells.sample(x, y, z);
                            case DEPTH -> Math.min(1, (depth[i] - 1) / Math.max(1, s.amount()));
                            case SLOPE -> Terrain.slope(context.terrain(), x, y, z) / 90;
                            case RANDOM -> random.nextDouble();
                            default -> 0.5;
                        };
                        v = (v - 0.5) * (1 + s.contrast()) + 0.5 - s.brightness();
                        if (s.dither() > 0) {
                            v += (random.nextDouble() - 0.5) * s.dither() / pn;
                        }
                        BlockData out = palette.pick(Math.max(0, Math.min(0.999999, v)));
                        if (!out.equals(grid.data[i])) {
                            changes.set(x, y, z, out);
                        }
                    }
                }
            }
            return changes;
        };
    }

    /** Summed-area table: the number of solid blocks in any box, in constant time. */
    private static int[] summed(Sculpt.Grid g) {
        int w = g.w + 1;
        int h = g.h + 1;
        int d = g.d + 1;
        int[] sat = new int[w * h * d];
        for (int z = 1; z < d; z++) {
            for (int y = 1; y < h; y++) {
                for (int x = 1; x < w; x++) {
                    int v = g.solid[(x - 1) + g.w * ((y - 1) + g.h * (z - 1))] ? 1 : 0;
                    sat[x + w * (y + h * z)] = v
                            + sat[(x - 1) + w * (y + h * z)] + sat[x + w * ((y - 1) + h * z)] + sat[x + w * (y + h * (z - 1))]
                            - sat[(x - 1) + w * ((y - 1) + h * z)] - sat[(x - 1) + w * (y + h * (z - 1))]
                            - sat[x + w * ((y - 1) + h * (z - 1))] + sat[(x - 1) + w * ((y - 1) + h * (z - 1))];
                }
            }
        }
        return sat;
    }

    private static double occlusion(Sculpt.Grid g, int[] sat, int x, int y, int z, int r) {
        int x0 = Math.max(0, x - r - g.box.minX());
        int y0 = Math.max(0, y - r - g.box.minY());
        int z0 = Math.max(0, z - r - g.box.minZ());
        int x1 = Math.min(g.w, x + r + 1 - g.box.minX());
        int y1 = Math.min(g.h, y + r + 1 - g.box.minY());
        int z1 = Math.min(g.d, z + r + 1 - g.box.minZ());
        int w = g.w + 1;
        int h = g.h + 1;
        int count = sat[x1 + w * (y1 + h * z1)] - sat[x0 + w * (y1 + h * z1)] - sat[x1 + w * (y0 + h * z1)]
                - sat[x1 + w * (y1 + h * z0)] + sat[x0 + w * (y0 + h * z1)] + sat[x0 + w * (y1 + h * z0)]
                + sat[x1 + w * (y0 + h * z0)] - sat[x0 + w * (y0 + h * z0)];
        int volume = (x1 - x0) * (y1 - y0) * (z1 - z0);
        return volume == 0 ? 0 : (count - 1) / (double) Math.max(1, volume - 1);
    }

    /**
     * Ambient, like ezEdits: the share of solid blocks in a ball around the block. A flat surface
     * is half buried and lands in the middle of the palette, edges and bumps towards its end,
     * hollows and nooks towards its start.
     */
    static double ambient(Sculpt.Grid g, int x, int y, int z, double radius) {
        int r = (int) Math.ceil(radius);
        double r2 = radius * radius + 0.5;
        int solid = 0;
        int total = 0;
        int flat = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if ((dx == 0 && dy == 0 && dz == 0) || dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    total++;
                    if (dy <= 0) {
                        flat++;
                    }
                    int ax = x + dx;
                    int ay = y + dy;
                    int az = z + dz;
                    if (g.contains(ax, ay, az) && g.solid[g.index(ax, ay, az)]) {
                        solid++;
                    }
                }
            }
        }
        if (total == 0) {
            return 0.5;
        }
        // Measured against flat ground, whose own layer counts as solid.
        return Math.max(0, Math.min(1, 0.5 - (solid - flat) / (double) total * 2));
    }

    /** The normal of a surface: the sum of directions towards air, within the radius. */
    private static double[] normal(Sculpt.Grid g, int x, int y, int z, int r) {
        double nx = 0;
        double ny = 0;
        double nz = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if ((dx == 0 && dy == 0 && dz == 0) || dx * dx + dy * dy + dz * dz > r * r + r) {
                        continue;
                    }
                    int ax = x + dx;
                    int ay = y + dy;
                    int az = z + dz;
                    boolean air = !g.contains(ax, ay, az) || !g.solid[g.index(ax, ay, az)];
                    if (air) {
                        nx += dx;
                        ny += dy;
                        nz += dz;
                    }
                }
            }
        }
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return len < 1e-9 ? null : new double[]{nx / len, ny / len, nz / len};
    }

    private static double light(Sculpt.Grid g, int x, int y, int z, Settings s, double[] vector, boolean point) {
        double[] n = normal(g, x, y, z, (int) Math.max(1, s.radius()));
        if (n == null) {
            return 1;
        }
        double lx;
        double ly;
        double lz;
        double distance = 0;
        if (point) {
            lx = vector[0] - (x + 0.5);
            ly = vector[1] - (y + 0.5);
            lz = vector[2] - (z + 0.5);
            distance = Math.sqrt(lx * lx + ly * ly + lz * lz);
        } else {
            lx = -vector[0];
            ly = -vector[1];
            lz = -vector[2];
        }
        double len = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (len < 1e-9) {
            return 0;
        }
        lx /= len;
        ly /= len;
        lz /= len;
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, n[0] * lx + n[1] * ly + n[2] * lz))));
        double v = (angle - s.interval()[0]) / Math.max(1e-6, s.interval()[1] - s.interval()[0]);
        if (point && s.range() > 0) {
            v += distance / s.range();
        }
        if (s.shadows() > 0 && shadowed(g, x, y, z, lx, ly, lz, point ? distance : 64)) {
            v += s.shadows();
        }
        return Math.max(0, Math.min(1, v));
    }

    private static boolean shadowed(Sculpt.Grid g, int x, int y, int z, double dx, double dy, double dz, double max) {
        double px = x + 0.5 + dx * 1.5;
        double py = y + 0.5 + dy * 1.5;
        double pz = z + 0.5 + dz * 1.5;
        for (double t = 1.5; t < max; t += 0.5) {
            int bx = (int) Math.floor(px);
            int by = (int) Math.floor(py);
            int bz = (int) Math.floor(pz);
            if (!g.contains(bx, by, bz)) {
                return false;
            }
            if (g.solid[g.index(bx, by, bz)]) {
                return true;
            }
            px += dx * 0.5;
            py += dy * 0.5;
            pz += dz * 0.5;
        }
        return false;
    }

    private static double axis(Sculpt.Grid g, Box region, int x, int y, int z, Settings s) {
        char a = s.axis();
        if (!s.relative()) {
            return switch (a) {
                case 'x' -> (x - region.minX() + 0.5) / region.width();
                case 'z' -> (z - region.minZ() + 0.5) / region.depth();
                default -> (y - region.minY() + 0.5) / region.height();
            };
        }
        // Per column: from the first to the last solid block of the column along the axis.
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        int from = a == 'x' ? region.minX() : a == 'z' ? region.minZ() : region.minY();
        int to = a == 'x' ? region.maxX() : a == 'z' ? region.maxZ() : region.maxY();
        for (int c = from; c <= to; c++) {
            int cx = a == 'x' ? c : x;
            int cy = a == 'y' ? c : y;
            int cz = a == 'z' ? c : z;
            if (g.solid[g.index(cx, cy, cz)]) {
                lo = Math.min(lo, c);
                hi = Math.max(hi, c);
            }
        }
        int coord = a == 'x' ? x : a == 'z' ? z : y;
        return hi == lo ? 0.5 : (coord - lo) / (double) (hi - lo);
    }

    /** Distance of each solid cell to air, in face steps, up to {@code max}. */
    private static int[] depth(Sculpt.Grid g, int max) {
        int n = g.solid.length;
        int[] dist = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        for (int i = 0; i < n; i++) {
            if (!g.solid[i]) {
                dist[i] = 0;
                queue.enqueue(i);
            }
        }
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int i = queue.dequeueInt();
            if (dist[i] >= max + 1) {
                continue;
            }
            int x = g.x(i);
            int y = g.y(i);
            int z = g.z(i);
            for (int[] f : faces) {
                int nx = x + f[0];
                int ny = y + f[1];
                int nz = z + f[2];
                if (!g.contains(nx, ny, nz)) {
                    continue;
                }
                int j = g.index(nx, ny, nz);
                if (dist[j] > dist[i] + 1) {
                    dist[j] = dist[i] + 1;
                    queue.enqueue(j);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (dist[i] == Integer.MAX_VALUE) {
                dist[i] = max + 1;
            }
        }
        return dist;
    }

    private static int blend(Sculpt.Grid g, java.util.Map<BlockData, Integer> indexOf, int x, int y, int z, int r, int self) {
        double sum = 0;
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int ax = x + dx;
                    int ay = y + dy;
                    int az = z + dz;
                    if (!g.contains(ax, ay, az)) {
                        continue;
                    }
                    Integer index = indexOf.get(g.data[g.index(ax, ay, az)]);
                    if (index != null) {
                        sum += index;
                        count++;
                    }
                }
            }
        }
        return count == 0 ? self : (int) Math.round(sum / count);
    }
}
