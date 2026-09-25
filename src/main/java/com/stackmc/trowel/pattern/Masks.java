package com.stackmc.trowel.pattern;

import com.stackmc.trowel.api.MarkerSupport;
import com.stackmc.trowel.engine.BlockView;
import com.stackmc.trowel.engine.Terrain;
import com.stackmc.trowel.expr.Expression;
import com.stackmc.trowel.geom.Noise;
import com.stackmc.trowel.geom.NoiseSpec;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mask parsing. Several terms separated by spaces or {@code &} must all be true.
 *
 * <ul>
 *   <li>{@code stone,dirt}, {@code oak_slab[type=top]}, a marker;</li>
 *   <li>{@code !stone}: everything but;</li>
 *   <li>{@code #air}, {@code #existing}, {@code #solid}, {@code #surface}, {@code #exposed};</li>
 *   <li>{@code #marker}, {@code #marker:checkpoint}: marker blocks;</li>
 *   <li>{@code #angle:20:60}: slope of the relief, in degrees;</li>
 *   <li>{@code #noise:ridged:8:60}: where the noise exceeds 60%;</li>
 *   <li>{@code #y:64:80}: height;</li>
 *   <li>{@code >grass_block}: placed on; {@code <stone}: under;</li>
 *   <li>{@code ~water} or {@code ~water@3}: nearby;</li>
 *   <li>{@code %30}: randomly, 30% of the blocks;</li>
 *   <li>{@code =x*x+z*z<100}, {@code #expr[expression]}: where the expression is true (x, y, z of
 *       the block; solid(dx,dy,dz) and air(dx,dy,dz) read around); {@code =} takes everything after;</li>
 *   <li>{@code #noise[noise][threshold]}: where a tuned noise exceeds the threshold (0 to 1, or in %);</li>
 *   <li>{@code #near[mask][distance]}, {@code #near[mask][min][max]}: at that distance;</li>
 *   <li>{@code #fullblock}, {@code #lightsource}, {@code #attached}, {@code #wall}, {@code #ceiling},
 *       {@code #floor}, {@code #palette[palette]}, {@code #slope[min][max]}, {@code #random[%]}.</li>
 * </ul>
 */
public final class Masks {

    private Masks() {
    }

    public static Mask parse(String raw, MarkerSupport markers) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty mask.");
        }
        String text = raw.trim();
        Mask mask = null;
        int equals = expressionStart(text);
        if (equals >= 0) {
            mask = expression(text.substring(equals + 1));
            text = text.substring(0, equals).trim();
        }
        if (!text.isEmpty()) {
            for (String term : Special.split(Palettes.expand(text), " \t&")) {
                Mask next = term(term, markers);
                mask = mask == null ? next : mask.and(next);
            }
        }
        if (mask == null) {
            throw new IllegalArgumentException("Empty mask.");
        }
        return mask;
    }

    /** Where a {@code =expression} term starts: at the beginning, or after a space or a &. */
    private static int expressionStart(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == '(') {
                depth++;
            } else if (c == ']' || c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == '=' && depth == 0 && (i == 0 || text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '&')) {
                return i;
            }
        }
        return -1;
    }

    /** True where the expression is positive; x, y, z are the block's. */
    public static Mask expression(String source) {
        Expression expression = Expression.compile(source, "x", "y", "z");
        ThreadLocal<Expression.Frame> frames = ThreadLocal.withInitial(expression::frame);
        return (x, y, z, view) -> {
            Expression.Frame f = frames.get();
            f.inputs(x, y, z);
            f.blockX = x;
            f.blockY = y;
            f.blockZ = z;
            f.world = new Query(view);
            return expression.run(f) > 0;
        };
    }

    private record Query(BlockView view) implements Expression.WorldQuery {
        @Override
        public boolean solid(int x, int y, int z) {
            return view.solid(x, y, z);
        }

        @Override
        public boolean air(int x, int y, int z) {
            return view.air(x, y, z);
        }
    }

    private static Mask special(Special s, MarkerSupport markers) {
        return switch (s.name()) {
            case "expr", "ex", "expression" -> expression(s.need(0, "#expr[expression]"));
            case "noise", "eznm", "eznoisemask", "nm" -> {
                boolean ez = !s.name().equals("noise");
                NoiseSpec spec = NoiseSpec.parse(s.need(0, "#noise[noise][threshold]"), 0.1);
                double scale = ez ? s.number(1, 1) : 1;
                if (scale > 0 && scale != 1) {
                    spec = spec.withFrequency(spec.frequency() / scale);
                }
                double threshold = s.number(ez ? 2 : 1, 0.5);
                double limit = threshold > 1 ? threshold / 100 : threshold;
                NoiseSpec noise = spec;
                yield (x, y, z, view) -> noise.sample(x, y, z) >= limit;
            }
            case "near" -> {
                Mask inner = parse(s.need(0, "#near[mask][distance]"), markers);
                double min = s.args().size() > 2 ? s.number(1, 0) : 0;
                double max = Math.max(1, Math.min(12, s.args().size() > 2 ? s.number(2, 3) : s.number(1, 3)));
                yield sphereNear(inner, min, max);
            }
            case "palette", "fuzzypalette", "fpalette" -> {
                Set<Material> types = EnumSet.noneOf(Material.class);
                for (BlockData data : Palette.parse(s.need(0, "#palette[palette]"), markers).blocks()) {
                    types.add(data.getMaterial());
                }
                yield (x, y, z, view) -> types.contains(view.type(x, y, z));
            }
            case "slope", "angle" -> {
                double min = s.number(0, 0);
                double max = s.number(1, 90);
                yield (x, y, z, view) -> {
                    double slope = Terrain.slope(view, x, y, z);
                    return slope >= min && slope <= max;
                };
            }
            case "random", "percent" -> {
                double chance = s.number(0, 50) / 100.0;
                yield (x, y, z, view) -> ThreadLocalRandom.current().nextDouble() < chance;
            }
            case "lightsource" -> {
                int min = (int) s.number(0, 1);
                int max = (int) s.number(1, 15);
                yield (x, y, z, view) -> {
                    int light = view.get(x, y, z).getLightEmission();
                    return light >= min && light <= max;
                };
            }
            case "above", "below", "prox", "proximity" -> {
                Mask inner = parse(s.need(0, "#" + s.name() + "[mask][distance]"), markers);
                int distance = (int) Math.max(1, Math.min(64, s.number(1, 1)));
                boolean up = s.name().equals("above");
                boolean both = s.name().startsWith("prox");
                yield (x, y, z, view) -> {
                    for (int d = 1; d <= distance; d++) {
                        if ((both || up) && inner.test(x, y - d, z, view)) {
                            return true;
                        }
                        if ((both || !up) && inner.test(x, y + d, z, view)) {
                            return true;
                        }
                    }
                    return false;
                };
            }
            case "prox3d" -> sphereNear(parse(s.need(0, "#prox3d[mask][distance]"), markers), 0,
                    Math.max(1, Math.min(12, s.number(1, 3))));
            case "ygradient", "ygrad" -> {
                double min = s.number(0, 0);
                double max = s.number(1, 100);
                yield (x, y, z, view) -> {
                    double chance = max == min ? (y <= min ? 1 : 0) : (max - y) / (max - min);
                    return ThreadLocalRandom.current().nextDouble() < chance;
                };
            }
            case "ambient" -> {
                int min = (int) Math.max(1, s.number(0, 2));
                yield (x, y, z, view) -> {
                    int air = 0;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if ((dx != 0 || dy != 0 || dz != 0) && view.air(x + dx, y + dy, z + dz)) {
                                    air++;
                                }
                            }
                        }
                    }
                    return air >= min;
                };
            }
            case "color", "colour", "cc" -> {
                Colors.prepare();
                int target = Colors.parse(s.need(0, "#color[color][tolerance]"));
                double tolerance = s.number(1, 30);
                yield (x, y, z, view) -> Colors.distance(target, Colors.rgb(view.type(x, y, z))) <= tolerance;
            }
            default -> {
                Noise.Kind kind = Noise.Kind.parse(s.name());
                if (kind == null) {
                    throw new IllegalArgumentException("Unknown mask: #" + s.name() + ".");
                }
                double scale = Math.max(0.25, s.number(0, 8));
                double coverage = s.number(1, 50);
                double limit = 1 - (coverage > 1 ? coverage / 100 : coverage);
                NoiseSpec noise = NoiseSpec.of(kind, 1 / scale);
                yield (x, y, z, view) -> noise.sample(x, y, z) >= limit;
            }
        };
    }

    /** True within {@code max} blocks (and at least {@code min}) of a block passing the mask. */
    private static Mask sphereNear(Mask inner, double min, double max) {
        int r = (int) Math.ceil(max);
        return (x, y, z, view) -> {
            if (inner.test(x, y, z, view)) {
                return false;
            }
            double best = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (d > max || d >= best || (dx == 0 && dy == 0 && dz == 0)) {
                            continue;
                        }
                        if (inner.test(x + dx, y + dy, z + dz, view)) {
                            best = d;
                        }
                    }
                }
            }
            return best <= max && best >= min;
        };
    }

    private static Mask term(String term, MarkerSupport markers) {
        Special special = Special.read(term);
        if (special != null) {
            return special(special, markers);
        }
        if (term.startsWith("!")) {
            return term(term.substring(1), markers).negate();
        }
        if (term.startsWith(">")) {
            Mask under = term(term.substring(1), markers);
            return (x, y, z, view) -> under.test(x, y - 1, z, view);
        }
        if (term.startsWith("<")) {
            Mask over = term(term.substring(1), markers);
            return (x, y, z, view) -> over.test(x, y + 1, z, view);
        }
        if (term.startsWith("~")) {
            String body = term.substring(1);
            int radius = 1;
            int at = body.lastIndexOf('@');
            if (at > 0) {
                radius = Math.max(1, Math.min(8, (int) number(body.substring(at + 1))));
                body = body.substring(0, at);
            }
            return near(term(body, markers), radius);
        }
        if (term.startsWith("%")) {
            double chance = number(term.substring(1)) / 100.0;
            return (x, y, z, view) -> ThreadLocalRandom.current().nextDouble() < chance;
        }
        String lower = term.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "#air" -> {
                return (x, y, z, view) -> view.air(x, y, z);
            }
            case "#existing" -> {
                return (x, y, z, view) -> !view.air(x, y, z);
            }
            case "#solid" -> {
                return (x, y, z, view) -> view.solid(x, y, z);
            }
            case "#surface" -> {
                return (x, y, z, view) -> view.solid(x, y, z) && !view.solid(x, y + 1, z);
            }
            case "#exposed" -> {
                return (x, y, z, view) -> !view.air(x, y, z) && view.exposed(x, y, z);
            }
            case "#marker" -> {
                return (x, y, z, view) -> view.marker(x, y, z);
            }
            case "#fullblock", "#full" -> {
                return (x, y, z, view) -> view.get(x, y, z).isOccluding();
            }
            case "#lightsource", "#light" -> {
                return (x, y, z, view) -> view.get(x, y, z).getLightEmission() > 0;
            }
            case "#attached" -> {
                return (x, y, z, view) -> !view.air(x, y, z) && (!view.air(x + 1, y, z) || !view.air(x - 1, y, z)
                        || !view.air(x, y + 1, z) || !view.air(x, y - 1, z) || !view.air(x, y, z + 1)
                        || !view.air(x, y, z - 1));
            }
            case "#wall" -> {
                return (x, y, z, view) -> view.solid(x, y, z) && (view.air(x + 1, y, z) || view.air(x - 1, y, z)
                        || view.air(x, y, z + 1) || view.air(x, y, z - 1));
            }
            case "#ceiling" -> {
                return (x, y, z, view) -> view.solid(x, y, z) && view.air(x, y - 1, z);
            }
            case "#floor" -> {
                return (x, y, z, view) -> view.solid(x, y, z) && view.air(x, y + 1, z);
            }
            default -> {
            }
        }
        if (lower.startsWith("#marker:")) {
            Material marker = markers.resolve(lower.substring("#marker:".length()));
            if (marker == null) {
                throw new IllegalArgumentException("Unknown marker: " + term.substring("#marker:".length()));
            }
            return (x, y, z, view) -> view.marker(x, y, z) && view.type(x, y, z) == marker;
        }
        if (lower.startsWith("#noise:")) {
            String[] parts = lower.split(":");
            if (parts.length != 4 || Noise.Kind.parse(parts[1]) == null) {
                throw new IllegalArgumentException("Write #noise:<type>:<scale>:<threshold %>, for example #noise:fractal:8:55.");
            }
            Noise.Kind kind = Noise.Kind.parse(parts[1]);
            double scale = Math.max(0.5, number(parts[2]));
            double threshold = number(parts[3]) / 100.0;
            return (x, y, z, view) -> Noise.sample(kind, x / scale, y / scale, z / scale) >= threshold;
        }
        if (lower.startsWith("#angle:") || lower.startsWith("#y:")) {
            String[] parts = lower.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Write " + parts[0] + ":<min>:<max>.");
            }
            double min = number(parts[1]);
            double max = number(parts[2]);
            if (lower.startsWith("#y:")) {
                return (x, y, z, view) -> y >= min && y <= max;
            }
            return (x, y, z, view) -> {
                double slope = Terrain.slope(view, x, y, z);
                return slope >= min && slope <= max;
            };
        }
        if (lower.startsWith("#")) {
            throw new IllegalArgumentException("Unknown mask: " + term);
        }
        return blocks(term, markers);
    }

    private static Mask near(Mask inner, int radius) {
        return (x, y, z, view) -> {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if ((dx != 0 || dy != 0 || dz != 0) && inner.test(x + dx, y + dy, z + dz, view)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        };
    }

    private static Mask blocks(String list, MarkerSupport markers) {
        Set<Material> types = EnumSet.noneOf(Material.class);
        Set<Material> named = EnumSet.noneOf(Material.class);
        List<BlockData> states = new ArrayList<>();
        for (String token : Tokens.split(list, ',')) {
            String lower = token.toLowerCase(Locale.ROOT).replaceFirst("^\\d+(\\.\\d+)?%", "");
            Material marker = markers.resolve(lower.startsWith("marker:") ? lower.substring(7) : lower);
            if (marker != null) {
                named.add(marker);
                continue;
            }
            if (lower.contains("[")) {
                try {
                    states.add(Bukkit.createBlockData(lower));
                    continue;
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown block: " + token);
                }
            }
            Material material = Material.matchMaterial(lower);
            if (material == null || !material.isBlock()) {
                throw new IllegalArgumentException("Unknown block: " + token);
            }
            types.add(material);
        }
        if (states.isEmpty()) {
            return (x, y, z, view) -> {
                Material type = view.type(x, y, z);
                return types.contains(type) || named.contains(type) && view.marker(x, y, z);
            };
        }
        return (x, y, z, view) -> {
            BlockData data = view.get(x, y, z);
            if (types.contains(data.getMaterial()) || named.contains(data.getMaterial()) && view.marker(x, y, z)) {
                return true;
            }
            for (BlockData state : states) {
                if (state.matches(data)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static double number(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number: " + raw);
        }
    }
}
