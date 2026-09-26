package com.stackmc.trowel.pattern;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.MarkerSupport;
import com.stackmc.trowel.engine.Clipboard;
import com.stackmc.trowel.engine.NamedMarkers;
import com.stackmc.trowel.expr.Expression;
import com.stackmc.trowel.geom.Noise;
import com.stackmc.trowel.geom.NoiseSpec;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;

/**
 * Pattern parsing: the FastAsyncWorldEdit syntax, and the ezEdits one for palette
 * patterns.
 *
 * <p>Simple:</p>
 * <ul>
 *   <li>{@code stone}, {@code oak_slab[type=top]}, or a marker: {@code checkpoint};</li>
 *   <li>{@code 60%stone,30%andesite,10%cobblestone}: a weighted draw; {@code ##magma} counts
 *       for all its blocks there;</li>
 *   <li>{@code #hand}, {@code #hotbar}, {@code #aim}: the held block, the hotbar, the aimed block.</li>
 * </ul>
 *
 * <p>With a palette (block order matters, see {@link Palette}):</p>
 * <ul>
 *   <li>{@code #noise[palette][noise][scale][seed]}: a tuned noise ({@link NoiseSpec});</li>
 *   <li>{@code #cracks[palette][scale]}, {@code #marble[...]}, {@code #ridged[...]}...: every
 *       Trowel noise, and the ezEdits shortcuts {@code #smoothcells}, {@code #voronoiedge};</li>
 *   <li>{@code #local[palette][noise]}: the noise taken in the shape, not in the world (a bark
 *       that follows the trunk of a spline);</li>
 *   <li>{@code #expr[palette][expression]}: the value of the expression picks the block, nothing
 *       if it is negative; {@code #expri} for a whole block number;</li>
 *   <li>{@code #gradient[palette][axis][noise][blend]}: axes x, y, z, -y, radial, sphere, or
 *       those of a shape: t (along the path), u, v, r (towards the edge), a (around);</li>
 *   <li>{@code #vgradient[palette][x,y,z][distance]}, {@code #rgradient[palette][distance]}:
 *       from the player, along a vector or around them;</li>
 *   <li>{@code #random[palette]}, {@code #stripes[palette][axis][width]},
 *       {@code #clipboard[dx,dy,dz]}: the clipboard as tiling.</li>
 * </ul>
 *
 * <p>The older forms are still read: {@code #noise:ridged:8:a,b}, {@code #voronoi:6:a,b},
 * {@code #gradient:a,b}, {@code #palette:stone}.</p>
 */
public final class Patterns {

    private static final java.util.regex.Pattern WEIGHT =
            java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)%(.+)$");

    /** Bracket patterns, for help and completion. */
    public static final Map<String, String> SPECIALS = new LinkedHashMap<>();

    static {
        SPECIALS.put("#noise[", "#noise[palette][noise][scale][seed]");
        SPECIALS.put("#local[", "#local[palette][noise]: texture that follows the shape");
        SPECIALS.put("#expr[", "#expr[palette][expression]");
        SPECIALS.put("#expri[", "#expri[palette][expression]: block number");
        SPECIALS.put("#gradient[", "#gradient[palette][axis][noise][blend]");
        SPECIALS.put("#vgradient[", "#vgradient[palette][x,y,z][distance]");
        SPECIALS.put("#rgradient[", "#rgradient[palette][distance]");
        SPECIALS.put("#random[", "#random[palette]");
        SPECIALS.put("#stripes[", "#stripes[palette][axis][width]");
        SPECIALS.put("#clipboard", "#clipboard[dx,dy,dz]: the clipboard as tiling");
        SPECIALS.put("#ridged[", "#ridged[palette][scale]");
        SPECIALS.put("#smoothcells[", "#smoothcells[palette][scale]");
        SPECIALS.put("#voronoiedge[", "#voronoiedge[palette][scale]");
        SPECIALS.put("#color[", "#color[#ff8800 or red][count]: the block closest to a color");
        SPECIALS.put("#turbulence[", "#turbulence[palette][scale]");
        SPECIALS.put("#electric[", "#electric[palette][scale]");
    }

    /** What a pattern may need besides blocks: clipboard, player position. */
    public record Context(MarkerSupport markers, Clipboard clipboard, int originX, int originY, int originZ) {
        public static Context of(MarkerSupport markers) {
            return new Context(markers, null, 0, 0, 0);
        }
    }

    private Patterns() {
    }

    public static Pattern parse(String raw, MarkerSupport markers) {
        return parse(raw, Context.of(markers));
    }

    public static Pattern parse(String raw, Context context) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty pattern.");
        }
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        Special special = Special.read(text);
        if (special != null) {
            return special(special, context);
        }
        if (lower.equals("#clipboard") || lower.equals("#copy")) {
            return clipboard(context, "0,0,0");
        }
        if (lower.startsWith("#gradient:")) {
            return new GradientPattern(palette(text.substring("#gradient:".length()), context), "y", null, 0,
                    null, true);
        }
        if (lower.startsWith("#") && !lower.startsWith("##") && !lower.startsWith("#palette:") && lower.contains(":")) {
            return legacyNoise(text, lower, context);
        }
        if (lower.startsWith("#") && !lower.startsWith("##") && !lower.startsWith("#palette:")) {
            throw new IllegalArgumentException("Unknown pattern: " + text + ". Special patterns: "
                    + String.join(", ", SPECIALS.values()));
        }
        return weighted(text, context.markers());
    }

    /** {@code #noise:[type:]scale:blocks}, {@code #voronoi:6:...}, {@code #cracks:6:...}, {@code #marble:8:...}. */
    private static Pattern legacyNoise(String text, String lower, Context context) {
        String name = lower.substring(1, lower.indexOf(':'));
        Noise.Kind kind = name.equals("noise") ? Noise.Kind.FRACTAL : Noise.Kind.parse(name);
        if (kind == null) {
            throw new IllegalArgumentException("Unknown pattern: #" + name + ". Special patterns: "
                    + String.join(", ", SPECIALS.values()));
        }
        String rest = text.substring(text.indexOf(':') + 1);
        int colon = rest.indexOf(':');
        if (colon > 0 && name.equals("noise") && Noise.Kind.parse(rest.substring(0, colon)) != null) {
            kind = Noise.Kind.parse(rest.substring(0, colon));
            rest = rest.substring(colon + 1);
            colon = rest.indexOf(':');
        }
        if (colon < 0) {
            throw new IllegalArgumentException("Write #noise[palette][noise], or #noise:[type:]<scale>:<blocks>, "
                    + "for example #noise:ridged:8:stone,andesite.");
        }
        double scale = number(rest.substring(0, colon), "Scale");
        return new NoisePattern(NoiseSpec.of(kind, 1 / scale), palette(rest.substring(colon + 1), context), false);
    }

    private static Pattern special(Special s, Context context) {
        return switch (s.name()) {
            case "noise", "np", "eznp", "eznoisepattern" -> {
                NoiseSpec spec = NoiseSpec.parse(s.arg(1, "perlin"), 0.1);
                yield new NoisePattern(zoom(spec, s, 2, 3), palette(s.need(0, "#noise[palette][noise]"), context), false);
            }
            case "local", "lnoise", "localnoise" -> {
                NoiseSpec spec = NoiseSpec.parse(s.arg(1, "perlin"), 0.15);
                yield new NoisePattern(zoom(spec, s, 2, 3), palette(s.need(0, "#local[palette][noise]"), context), true);
            }
            case "ridged" -> preset(s, context, "perlin(ft:ridged,fo:4)");
            case "smoothcells" -> preset(s, context, "cellular(cr:1)");
            case "voronoiedge" -> preset(s, context, "cellular(cr:edge)");
            case "expr", "ex", "expression", "=" -> new ExprPattern(palette(s.need(0, "#expr[palette][expression]"), context),
                    ExprPattern.compile(s.need(1, "#expr[palette][expression]")), false, null);
            case "expri" -> new ExprPattern(palette(s.need(0, "#expri[palette][expression]"), context),
                    ExprPattern.compile(s.need(1, "#expri[palette][expression]")), true, null);
            case "gradient", "grad" -> {
                String noise = s.arg(2, null);
                yield new GradientPattern(palette(s.need(0, "#gradient[palette][axis]"), context),
                        s.arg(1, "y").toLowerCase(Locale.ROOT), noise == null ? null : NoiseSpec.parse(noise, 0.1),
                        s.number(3, 0.3), null, true);
            }
            case "vgradient", "vgradientp", "vectorgradientpattern" -> {
                double[] v = vector(s.need(1, "#vgradient[palette][x,y,z][distance]"));
                double distance = Math.max(0.5, s.number(2, 16));
                String noise = s.arg(3, null);
                yield new OriginGradient(palette(s.need(0, "#vgradient[palette][x,y,z][distance]"), context), context,
                        v, distance, noise == null ? null : NoiseSpec.parse(noise, 0.1), s.number(4, 0.3));
            }
            case "rgradient", "rgradientp", "radialgradientpattern" -> {
                double distance = Math.max(0.5, s.number(1, 16));
                String noise = s.arg(2, null);
                yield new OriginGradient(palette(s.need(0, "#rgradient[palette][distance]"), context), context,
                        null, distance, noise == null ? null : NoiseSpec.parse(noise, 0.1), s.number(3, 0.3));
            }
            case "random", "palette" -> {
                Palette palette = palette(s.need(0, "#random[palette]"), context);
                yield (x, y, z) -> palette.get(ThreadLocalRandom.current().nextInt(palette.size()));
            }
            case "stripes", "bands" -> {
                Palette palette = palette(s.need(0, "#stripes[palette][axis][width]"), context);
                String axis = s.arg(1, "y").toLowerCase(Locale.ROOT);
                int width = (int) Math.max(1, s.number(2, 1));
                yield (x, y, z) -> {
                    int c = switch (axis) {
                        case "x" -> x;
                        case "z" -> z;
                        case "xz", "diag" -> x + z;
                        case "xy" -> x + y;
                        case "zy", "yz" -> z + y;
                        default -> y;
                    };
                    return palette.get(Math.floorMod(Math.floorDiv(c, width), palette.size()));
                };
            }
            case "clipboard", "copy", "cb" -> clipboard(context, s.arg(0, "0,0,0"));
            case "color", "colour", "cc" -> {
                List<BlockData> closest = Colors.closest(Colors.parse(s.need(0, "#color[color][count]")),
                        (int) Math.max(1, s.number(1, 1)));
                if (closest.size() == 1) {
                    BlockData only = closest.get(0);
                    yield (x, y, z) -> only;
                }
                yield (x, y, z) -> closest.get(ThreadLocalRandom.current().nextInt(closest.size()));
            }
            default -> {
                Noise.Kind kind = Noise.Kind.parse(s.name());
                if (kind == null) {
                    throw new IllegalArgumentException("Unknown pattern: #" + s.name() + ". Patterns: "
                            + String.join(", ", SPECIALS.values()));
                }
                double scale = Math.max(0.25, s.number(1, 8));
                NoiseSpec spec = NoiseSpec.of(kind, 1 / scale);
                yield new NoisePattern(seeded(spec, s, 2), palette(s.need(0, "#" + s.name() + "[palette][scale]"), context),
                        false);
            }
        };
    }

    private static Pattern preset(Special s, Context context, String spec) {
        double scale = Math.max(0.25, s.number(1, 10));
        NoiseSpec parsed = NoiseSpec.parse(spec, 1 / scale);
        return new NoisePattern(seeded(parsed, s, 2), palette(s.need(0, "#" + s.name() + "[palette][scale]"), context),
                false);
    }

    /** A {@code [scale]} that enlarges the noise, and a {@code [seed]}. */
    private static NoiseSpec zoom(NoiseSpec spec, Special s, int scaleIndex, int seedIndex) {
        double scale = s.number(scaleIndex, 1);
        NoiseSpec out = scale > 0 && scale != 1 ? spec.withFrequency(spec.frequency() / scale) : spec;
        return seeded(out, s, seedIndex);
    }

    private static NoiseSpec seeded(NoiseSpec spec, Special s, int seedIndex) {
        if (s.arg(seedIndex, null) == null) {
            return spec;
        }
        long seed = (long) s.number(seedIndex, 0);
        return spec.withSeed(seed == -1 ? ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE) : seed);
    }

    private static Pattern clipboard(Context context, String offset) {
        Clipboard clipboard = context.clipboard();
        if (clipboard == null) {
            throw new IllegalArgumentException("#clipboard: empty clipboard, //copy first.");
        }
        double[] o = vector(offset);
        int ox = (int) o[0];
        int oy = (int) o[1];
        int oz = (int) o[2];
        Clipboard carrying = clipboard.withCarriedMarkers();
        return (x, y, z) -> carrying.tiled(x - ox, y - oy, z - oz);
    }

    private static double[] vector(String raw) {
        String[] parts = raw.replace("(", "").replace(")", "").split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Vector expected: x,y,z, not '" + raw + "'.");
        }
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = number(parts[i], "Vector", true);
        }
        return out;
    }

    /** An ordered palette, read like ezEdits. */
    public static Palette palette(String raw, Context context) {
        return Palette.parse(raw, context.markers());
    }

    private static final java.util.regex.Pattern LEGACY_ID = java.util.regex.Pattern.compile("\\d{1,3}(:\\d{1,2})?");
    private static volatile Map<Integer, Material> legacyIds;

    /** A numeric block id from before 1.13, as ezEdits still accepts it: {@code 251:8} is light gray concrete. */
    private static BlockData legacy(String token) {
        Map<Integer, Material> ids = legacyIds;
        if (ids == null) {
            Map<Integer, Material> built = new java.util.HashMap<>();
            for (Material material : Material.values()) {
                if (material.isLegacy() && material.isBlock()) {
                    built.putIfAbsent(material.getId(), material);
                }
            }
            legacyIds = ids = built;
        }
        int colon = token.indexOf(':');
        int id = Integer.parseInt(colon < 0 ? token : token.substring(0, colon));
        int data = colon < 0 ? 0 : Integer.parseInt(token.substring(colon + 1));
        Material material = ids.get(id);
        if (material == null || data > 15) {
            throw new IllegalArgumentException("Unknown block id: " + token);
        }
        BlockData block = Bukkit.getUnsafe().fromLegacy(material, (byte) data);
        if (block == null || block.getMaterial().isAir() && id != 0) {
            throw new IllegalArgumentException("Unknown block id: " + token);
        }
        return block;
    }

    /** A single block, or a marker by name. */
    public static BlockData block(String token, MarkerSupport markers) {
        String name = token.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("marker:")) {
            lower = lower.substring("marker:".length());
        }
        Material marker = markers.resolve(lower);
        if (marker != null) {
            return NamedMarkers.of(marker);
        }
        if (LEGACY_ID.matcher(lower).matches()) {
            return legacy(lower);
        }
        try {
            BlockData data = Bukkit.createBlockData(lower);
            if (data.getMaterial().isBlock()) {
                return data;
            }
        } catch (IllegalArgumentException ignored) {
            // An unknown state falls back to the bare block, if it exists.
        }
        int state = lower.indexOf('[');
        Material material = Material.matchMaterial(state < 0 ? lower : lower.substring(0, state));
        if (material == null || !material.isBlock()) {
            throw new IllegalArgumentException("Unknown block: " + name);
        }
        return material.createBlockData();
    }

    /**
     * Replaces {@code #hand}, {@code #hotbar} and {@code #aim} with the player's blocks.
     *
     * <p>Done on the main thread, before the operation: inventory and world cannot be read elsewhere.</p>
     */
    public static String expand(String raw, Player player) {
        if (raw == null) {
            return null;
        }
        String out = raw;
        String lower = out.toLowerCase(Locale.ROOT);
        if (lower.contains("#hand")) {
            Material held = player.getInventory().getItemInMainHand().getType();
            if (!held.isBlock() || held.isAir()) {
                throw new IllegalArgumentException("#hand: hold a block.");
            }
            out = out.replaceAll("(?i)#hand", held.getKey().getKey());
        }
        if (lower.contains("#hotbar")) {
            String hotbar = hotbar(player);
            if (hotbar == null) {
                throw new IllegalArgumentException("#hotbar: no block in your hotbar.");
            }
            out = out.replaceAll("(?i)#hotbar", hotbar);
        }
        if (lower.contains("#aim")) {
            var hit = player.rayTraceBlocks(64, FluidCollisionMode.NEVER);
            Block block = hit == null ? null : hit.getHitBlock();
            if (block == null) {
                throw new IllegalArgumentException("#aim: look at a block.");
            }
            out = out.replaceAll("(?i)#aim", Matcher.quoteReplacement(block.getBlockData().getAsString(true)
                    .replace("minecraft:", "")));
        }
        return out;
    }

    /** The hotbar blocks, weighted by their amount; {@code null} if there are none. */
    public static String hotbar(Player player) {
        Map<Material, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack != null && stack.getType().isBlock() && !stack.getType().isAir()) {
                counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return null;
        }
        if (counts.size() == 1) {
            return counts.keySet().iterator().next().getKey().getKey();
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((material, amount) -> parts.add(amount + "%" + material.getKey().getKey()));
        return String.join(",", parts);
    }

    private static Pattern weighted(String text, MarkerSupport markers) {
        List<BlockData> blocks = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (String token : Tokens.split(Palettes.expand(text), ',')) {
            double weight = 1;
            Matcher matcher = WEIGHT.matcher(token);
            if (matcher.matches()) {
                weight = number(matcher.group(1), "Weight");
                token = matcher.group(2);
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("##") || lower.startsWith("-") || lower.startsWith("[")) {
                List<String> members = Palette.parseTokens(token);
                for (String member : members) {
                    blocks.add(block(member, markers));
                    weights.add(weight / members.size());
                }
                continue;
            }
            blocks.add(block(token, markers));
            weights.add(weight);
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Empty pattern.");
        }
        if (blocks.size() == 1) {
            BlockData only = blocks.get(0);
            return (x, y, z) -> only;
        }
        double[] cumulative = new double[blocks.size()];
        double total = 0;
        for (int i = 0; i < blocks.size(); i++) {
            total += Math.max(0, weights.get(i));
            cumulative[i] = total;
        }
        if (total <= 0) {
            throw new IllegalArgumentException("The pattern weights are all zero.");
        }
        BlockData[] all = blocks.toArray(new BlockData[0]);
        double sum = total;
        return (x, y, z) -> {
            double roll = ThreadLocalRandom.current().nextDouble(sum);
            for (int i = 0; i < cumulative.length; i++) {
                if (roll < cumulative[i]) {
                    return all[i];
                }
            }
            return all[all.length - 1];
        };
    }

    private static double number(String raw, String label) {
        return number(raw, label, false);
    }

    private static double number(String raw, String label, boolean signed) {
        try {
            double value = Double.parseDouble(raw.trim());
            if (!signed && value <= 0) {
                throw new IllegalArgumentException(label + " must be positive.");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " invalid: " + raw);
        }
    }

    // ---------------------------------------------------------------- patterns

    /** A tuned noise picks the block of the palette; in the world, or in the shape ({@code local}). */
    private record NoisePattern(NoiseSpec spec, Palette palette, boolean local) implements Pattern {
        @Override
        public BlockData at(int x, int y, int z) {
            return palette.pick(spec.sample(x, y, z));
        }

        @Override
        public BlockData at(int x, int y, int z, Local l) {
            if (!local || l == null) {
                return at(x, y, z);
            }
            double radius = Math.max(1, l.radius);
            return palette.pick(spec.sample(l.u * radius, l.s, l.v * radius));
        }
    }

    /**
     * An expression picks the block: its value within ]0, 1] walks the palette, nothing is placed
     * if it is negative or zero.
     *
     * <p>Variables: x, y, z (the block), nx, ny, nz (from -1 to 1 within the operation), and in a
     * shape u, v, w, t, s, r, a, radius, value (see {@link Local}).</p>
     */
    static final class ExprPattern implements Pattern {
        static final String[] INPUTS = {"x", "y", "z", "nx", "ny", "nz", "u", "v", "w", "t", "s", "r", "a", "radius",
                "value"};

        private final Palette palette;
        private final Expression expression;
        private final boolean integer;
        private final Box box;
        private final ThreadLocal<Expression.Frame> frames;

        ExprPattern(Palette palette, Expression expression, boolean integer, Box box) {
            this.palette = palette;
            this.expression = expression;
            this.integer = integer;
            this.box = box;
            this.frames = ThreadLocal.withInitial(expression::frame);
        }

        static Expression compile(String source) {
            return Expression.compile(source, INPUTS);
        }

        @Override
        public BlockData at(int x, int y, int z) {
            return at(x, y, z, null);
        }

        @Override
        public BlockData at(int x, int y, int z, Local l) {
            Expression.Frame f = frames.get();
            double nx = 0;
            double ny = 0;
            double nz = 0;
            if (box != null) {
                nx = normal(x, box.minX(), box.maxX());
                ny = normal(y, box.minY(), box.maxY());
                nz = normal(z, box.minZ(), box.maxZ());
            }
            if (l == null) {
                f.inputs(x, y, z, nx, ny, nz, 0, 0, 0, 0, 0, 0, 0, 1, 1);
            } else {
                f.inputs(x, y, z, nx, ny, nz, l.u, l.v, l.w, l.t, l.s, l.r, l.a, l.radius, l.value);
            }
            f.blockX = x;
            f.blockY = y;
            f.blockZ = z;
            double v = expression.run(f);
            if (!(v > 0)) {
                return null;
            }
            if (integer) {
                return palette.get((int) Math.ceil(v) - 1);
            }
            return v >= 1 ? palette.get(palette.size() - 1) : palette.get((int) Math.ceil(v * palette.size()) - 1);
        }

        private static double normal(int v, int min, int max) {
            return max == min ? 0 : (v - min) / (double) (max - min) * 2 - 1;
        }

        @Override
        public Pattern within(Box bounds) {
            return new ExprPattern(palette, expression, integer, bounds);
        }

        @Override
        public Pattern within(int minY, int maxY) {
            return this;
        }
    }

    /** From the first block to the last along an axis, with a noise blend or a light dither. */
    private record GradientPattern(Palette palette, String axis, NoiseSpec noise, double amount, Box box,
                                   boolean dither) implements Pattern {

        @Override
        public BlockData at(int x, int y, int z) {
            return at(x, y, z, null);
        }

        @Override
        public BlockData at(int x, int y, int z, Local l) {
            double t = position(x, y, z, l);
            int n = palette.size();
            if (noise != null) {
                t += (noise.sample(x, y, z) - 0.5) * amount * 2;
            } else if (dither) {
                t += (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.8 / n;
            }
            return palette.pick(t);
        }

        private double position(int x, int y, int z, Local l) {
            boolean reverse = axis.startsWith("-");
            String a = reverse ? axis.substring(1) : axis;
            double t;
            if (l != null && switch (a) {
                case "t", "u", "v", "r", "a", "w", "value" -> true;
                default -> false;
            }) {
                t = switch (a) {
                    case "t" -> l.t;
                    case "u" -> (l.u + 1) / 2;
                    case "v" -> (l.v + 1) / 2;
                    case "r" -> l.r;
                    case "a" -> l.a / (Math.PI * 2);
                    case "w" -> l.w - Math.floor(l.w);
                    default -> l.value;
                };
            } else if (box == null) {
                t = 0.5;
            } else {
                t = switch (a) {
                    case "x" -> (x - box.minX() + 0.5) / box.width();
                    case "z" -> (z - box.minZ() + 0.5) / box.depth();
                    case "radial", "cyl" -> {
                        double cx = (box.minX() + box.maxX() + 1) / 2.0;
                        double cz = (box.minZ() + box.maxZ() + 1) / 2.0;
                        double half = Math.max(box.width(), box.depth()) / 2.0;
                        yield Math.hypot(x + 0.5 - cx, z + 0.5 - cz) / half;
                    }
                    case "sphere", "spherical" -> {
                        double cx = (box.minX() + box.maxX() + 1) / 2.0;
                        double cy = (box.minY() + box.maxY() + 1) / 2.0;
                        double cz = (box.minZ() + box.maxZ() + 1) / 2.0;
                        double half = Math.max(box.width(), Math.max(box.height(), box.depth())) / 2.0;
                        double dx = x + 0.5 - cx;
                        double dy = y + 0.5 - cy;
                        double dz = z + 0.5 - cz;
                        yield Math.sqrt(dx * dx + dy * dy + dz * dz) / half;
                    }
                    case "t", "u", "v", "r", "a", "w", "value" -> 0.5;
                    default -> {
                        if (a.contains(",")) {
                            double[] v = vector(a);
                            double norm = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                            if (norm == 0) {
                                yield 0.5;
                            }
                            double lo = Double.MAX_VALUE;
                            double hi = -Double.MAX_VALUE;
                            for (int i = 0; i < 8; i++) {
                                double px = (i & 1) == 0 ? box.minX() : box.maxX() + 1;
                                double py = (i & 2) == 0 ? box.minY() : box.maxY() + 1;
                                double pz = (i & 4) == 0 ? box.minZ() : box.maxZ() + 1;
                                double d = (px * v[0] + py * v[1] + pz * v[2]) / norm;
                                lo = Math.min(lo, d);
                                hi = Math.max(hi, d);
                            }
                            double d = ((x + 0.5) * v[0] + (y + 0.5) * v[1] + (z + 0.5) * v[2]) / norm;
                            yield hi == lo ? 0.5 : (d - lo) / (hi - lo);
                        }
                        yield (y - box.minY() + 0.5) / box.height();
                    }
                };
            }
            return reverse ? 1 - t : t;
        }

        @Override
        public Pattern within(Box bounds) {
            return new GradientPattern(palette, axis, noise, amount, bounds, dither);
        }

        @Override
        public Pattern within(int minY, int maxY) {
            return new GradientPattern(palette, axis, noise, amount,
                    new Box(Integer.MIN_VALUE / 4, minY, Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4, maxY,
                            Integer.MAX_VALUE / 4), dither);
        }
    }

    /** A gradient starting from the player: along a vector, or around them. */
    private record OriginGradient(Palette palette, Context context, double[] vector, double distance, NoiseSpec noise,
                                  double amount) implements Pattern {
        @Override
        public BlockData at(int x, int y, int z) {
            double dx = x + 0.5 - context.originX() - 0.5;
            double dy = y + 0.5 - context.originY() - 0.5;
            double dz = z + 0.5 - context.originZ() - 0.5;
            double t;
            if (vector == null) {
                t = Math.sqrt(dx * dx + dy * dy + dz * dz) / distance;
            } else {
                double norm = Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
                t = norm == 0 ? 0 : (dx * vector[0] + dy * vector[1] + dz * vector[2]) / norm / distance;
            }
            if (noise != null) {
                t += (noise.sample(x, y, z) - 0.5) * amount * 2;
            } else {
                t += (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.8 / palette.size();
            }
            return palette.pick(t);
        }
    }
}
