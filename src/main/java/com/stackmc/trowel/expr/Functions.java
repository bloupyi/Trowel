package com.stackmc.trowel.expr;

import com.stackmc.trowel.expr.Expression.Node;
import com.stackmc.trowel.geom.Noise;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The functions and constants of expressions.
 *
 * <p>WorldEdit's are there, with the same signature: {@code perlin(seed, x, y, z, freq,
 * octaves, persistence)}, {@code voronoi(seed, x, y, z, freq)}, {@code ridgedmulti(seed, x, y, z,
 * freq, octaves)}, which return a value within [-1, 1]. Each Trowel noise is also a function
 * of (x, y, z) returning [0, 1[: {@code cracks(x*4, y*4, z*4)}. On top come shader tools
 * (smoothstep, mix, fract...), signed distance shapes (sdsphere, sdbox, sdtorus, smin) and two
 * world queries relative to the evaluated block: {@code solid(dx, dy, dz)},
 * {@code air(dx, dy, dz)}.</p>
 */
public final class Functions {

    public static final Map<String, Double> CONSTANTS = Map.of(
            "pi", Math.PI, "e", Math.E, "tau", Math.PI * 2, "phi", (1 + Math.sqrt(5)) / 2,
            "true", 1.0, "false", 0.0, "inf", Double.POSITIVE_INFINITY);

    @FunctionalInterface
    private interface Builder {
        Node build(Node[] a);
    }

    private record Entry(int min, int max, String usage, Builder builder) {
    }

    private static final Map<String, Entry> ALL = new LinkedHashMap<>();

    static {
        unary("sin", Math::sin);
        unary("cos", Math::cos);
        unary("tan", Math::tan);
        unary("asin", Math::asin);
        unary("acos", Math::acos);
        unary("atan", Math::atan);
        unary("sinh", Math::sinh);
        unary("cosh", Math::cosh);
        unary("tanh", Math::tanh);
        unary("sqrt", Math::sqrt);
        unary("cbrt", Math::cbrt);
        unary("abs", Math::abs);
        unary("ceil", Math::ceil);
        unary("floor", Math::floor);
        unary("round", v -> (double) Math.round(v));
        unary("rint", Math::rint);
        unary("trunc", v -> v < 0 ? Math.ceil(v) : Math.floor(v));
        unary("exp", Math::exp);
        unary("ln", Math::log);
        unary("log", Math::log);
        unary("log10", Math::log10);
        unary("log2", v -> Math.log(v) / Math.log(2));
        unary("sign", Math::signum);
        unary("signum", Math::signum);
        unary("fract", v -> v - Math.floor(v));
        unary("deg", Math::toDegrees);
        unary("rad", Math::toRadians);
        unary("sq", v -> v * v);
        unary("saturate", v -> Math.max(0, Math.min(1, v)));
        unary("tri", v -> 1 - Math.abs(2 * (v - Math.floor(v)) - 1));
        unary("saw", v -> v - Math.floor(v));
        unary("square", v -> v - Math.floor(v) < 0.5 ? 1 : 0);
        unary("fact", v -> {
            double r = 1;
            for (int i = 2; i <= Math.min(170, (int) v); i++) {
                r *= i;
            }
            return r;
        });

        binary("atan2", Math::atan2);
        binary("pow", Math::pow);
        binary("mod", (a, b) -> b == 0 ? 0 : a - b * Math.floor(a / b));
        binary("step", (edge, v) -> v < edge ? 0 : 1);
        binary("hypot", Math::hypot);
        binary("pingpong", (v, len) -> {
            if (len == 0) {
                return 0;
            }
            double t = v - 2 * len * Math.floor(v / (2 * len));
            return len - Math.abs(t - len);
        });
        binary("randint", (a, b) -> {
            int lo = (int) Math.min(a, b);
            int hi = (int) Math.max(a, b);
            return lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
        });

        add("min", 1, 16, "min(a, b, ...)", a -> {
            if (a.length == 2) {
                Node x = a[0];
                Node y = a[1];
                return f -> Math.min(x.eval(f), y.eval(f));
            }
            return f -> {
                double m = a[0].eval(f);
                for (int i = 1; i < a.length; i++) {
                    m = Math.min(m, a[i].eval(f));
                }
                return m;
            };
        });
        add("max", 1, 16, "max(a, b, ...)", a -> {
            if (a.length == 2) {
                Node x = a[0];
                Node y = a[1];
                return f -> Math.max(x.eval(f), y.eval(f));
            }
            return f -> {
                double m = a[0].eval(f);
                for (int i = 1; i < a.length; i++) {
                    m = Math.max(m, a[i].eval(f));
                }
                return m;
            };
        });
        add("clamp", 3, 3, "clamp(v, min, max)", a -> f -> Math.max(a[1].eval(f), Math.min(a[2].eval(f), a[0].eval(f))));
        add("lerp", 3, 3, "lerp(a, b, t)", a -> f -> {
            double x = a[0].eval(f);
            return x + (a[1].eval(f) - x) * a[2].eval(f);
        });
        ALL.put("mix", ALL.get("lerp"));
        add("smoothstep", 3, 3, "smoothstep(edge0, edge1, v)", a -> f -> {
            double e0 = a[0].eval(f);
            double e1 = a[1].eval(f);
            double t = e1 == e0 ? (a[2].eval(f) < e0 ? 0 : 1) : Math.max(0, Math.min(1, (a[2].eval(f) - e0) / (e1 - e0)));
            return t * t * (3 - 2 * t);
        });
        add("remap", 5, 5, "remap(v, from0, from1, to0, to1)", a -> f -> {
            double from0 = a[1].eval(f);
            double from1 = a[2].eval(f);
            double t = from1 == from0 ? 0 : (a[0].eval(f) - from0) / (from1 - from0);
            double to0 = a[3].eval(f);
            return to0 + (a[4].eval(f) - to0) * t;
        });
        add("len", 1, 4, "len(x, y[, z])", a -> switch (a.length) {
            case 1 -> f -> Math.abs(a[0].eval(f));
            case 2 -> f -> Math.hypot(a[0].eval(f), a[1].eval(f));
            case 3 -> f -> {
                double x = a[0].eval(f);
                double y = a[1].eval(f);
                double z = a[2].eval(f);
                return Math.sqrt(x * x + y * y + z * z);
            };
            default -> f -> {
                double x = a[0].eval(f);
                double y = a[1].eval(f);
                double z = a[2].eval(f);
                double w = a[3].eval(f);
                return Math.sqrt(x * x + y * y + z * z + w * w);
            };
        });
        ALL.put("length", ALL.get("len"));
        add("dist", 6, 6, "dist(x1, y1, z1, x2, y2, z2)", a -> f -> {
            double dx = a[0].eval(f) - a[3].eval(f);
            double dy = a[1].eval(f) - a[4].eval(f);
            double dz = a[2].eval(f) - a[5].eval(f);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        });
        add("random", 0, 0, "random()", a -> f -> ThreadLocalRandom.current().nextDouble());
        ALL.put("rand", ALL.get("random"));
        add("hash", 1, 4, "hash(x[, y, z, seed]) within [0, 1[", a -> f -> {
            long h = 0x9E3779B97F4A7C15L;
            for (Node n : a) {
                h = mix(h ^ Double.doubleToLongBits(n.eval(f) + 0.0));
            }
            return (h >>> 11) * 0x1.0p-53;
        });
        add("select", 3, 3, "select(cond, if true, if false)", a -> f -> Expression.truth(a[0].eval(f)) ? a[1].eval(f) : a[2].eval(f));

        // Signed distance shapes: negative inside.
        add("sdsphere", 4, 4, "sdsphere(x, y, z, r)", a -> f -> {
            double x = a[0].eval(f);
            double y = a[1].eval(f);
            double z = a[2].eval(f);
            return Math.sqrt(x * x + y * y + z * z) - a[3].eval(f);
        });
        add("sdbox", 6, 6, "sdbox(x, y, z, half x, half y, half z)", a -> f -> {
            double qx = Math.abs(a[0].eval(f)) - a[3].eval(f);
            double qy = Math.abs(a[1].eval(f)) - a[4].eval(f);
            double qz = Math.abs(a[2].eval(f)) - a[5].eval(f);
            double ox = Math.max(qx, 0);
            double oy = Math.max(qy, 0);
            double oz = Math.max(qz, 0);
            return Math.sqrt(ox * ox + oy * oy + oz * oz) + Math.min(Math.max(qx, Math.max(qy, qz)), 0);
        });
        add("sdtorus", 5, 5, "sdtorus(x, y, z, major r, minor r)", a -> f -> {
            double x = a[0].eval(f);
            double y = a[1].eval(f);
            double z = a[2].eval(f);
            double q = Math.sqrt(x * x + z * z) - a[3].eval(f);
            return Math.sqrt(q * q + y * y) - a[4].eval(f);
        });
        add("sdcyl", 5, 5, "sdcyl(x, y, z, radius, half height)", a -> f -> {
            double x = a[0].eval(f);
            double z = a[2].eval(f);
            double dx = Math.sqrt(x * x + z * z) - a[3].eval(f);
            double dy = Math.abs(a[1].eval(f)) - a[4].eval(f);
            double ox = Math.max(dx, 0);
            double oy = Math.max(dy, 0);
            return Math.min(Math.max(dx, dy), 0) + Math.sqrt(ox * ox + oy * oy);
        });
        add("smin", 3, 3, "smin(a, b, k): smooth union", a -> f -> {
            double x = a[0].eval(f);
            double y = a[1].eval(f);
            double k = Math.max(1e-9, a[2].eval(f));
            double h = Math.max(0, Math.min(1, 0.5 + 0.5 * (y - x) / k));
            return y + (x - y) * h - k * h * (1 - h);
        });
        add("smax", 3, 3, "smax(a, b, k): smooth intersection", a -> f -> {
            double x = -a[0].eval(f);
            double y = -a[1].eval(f);
            double k = Math.max(1e-9, a[2].eval(f));
            double h = Math.max(0, Math.min(1, 0.5 + 0.5 * (y - x) / k));
            return -(y + (x - y) * h - k * h * (1 - h));
        });
        add("rotate", 3, 3, "rotate(a, b, angle): returns a rotated; see rotateb", a -> f -> {
            double x = a[0].eval(f);
            double y = a[1].eval(f);
            double t = a[2].eval(f);
            return x * Math.cos(t) - y * Math.sin(t);
        });
        add("rotateb", 3, 3, "rotateb(a, b, angle): returns b rotated", a -> f -> {
            double x = a[0].eval(f);
            double y = a[1].eval(f);
            double t = a[2].eval(f);
            return x * Math.sin(t) + y * Math.cos(t);
        });

        // WorldEdit style noises: within [-1, 1].
        add("perlin", 7, 7, "perlin(seed, x, y, z, freq, octaves, persistence)", a -> f -> {
            double seed = a[0].eval(f);
            double freq = a[4].eval(f);
            int octaves = Math.max(1, Math.min(12, (int) a[5].eval(f)));
            double persistence = a[6].eval(f);
            double ox = offset(seed, 1);
            double oy = offset(seed, 2);
            double oz = offset(seed, 3);
            double x = a[1].eval(f) * freq + ox;
            double y = a[2].eval(f) * freq + oy;
            double z = a[3].eval(f) * freq + oz;
            double sum = 0;
            double amplitude = 1;
            double total = 0;
            for (int i = 0; i < octaves; i++) {
                sum += Noise.gradient(x, y, z) * amplitude;
                total += amplitude;
                amplitude *= persistence;
                x *= 2;
                y *= 2;
                z *= 2;
            }
            return total == 0 ? 0 : Math.max(-1, Math.min(1, sum / total * 1.4));
        });
        add("voronoi", 5, 5, "voronoi(seed, x, y, z, freq)", a -> f -> {
            double seed = a[0].eval(f);
            double freq = a[4].eval(f);
            return Noise.voronoi(a[1].eval(f) * freq + offset(seed, 4), a[2].eval(f) * freq + offset(seed, 5),
                    a[3].eval(f) * freq + offset(seed, 6)) * 2 - 1;
        });
        add("ridgedmulti", 6, 6, "ridgedmulti(seed, x, y, z, freq, octaves)", a -> f -> {
            double seed = a[0].eval(f);
            double freq = a[4].eval(f);
            int octaves = Math.max(1, Math.min(12, (int) a[5].eval(f)));
            double x = a[1].eval(f) * freq + offset(seed, 7);
            double y = a[2].eval(f) * freq + offset(seed, 8);
            double z = a[3].eval(f) * freq + offset(seed, 9);
            double sum = 0;
            double amplitude = 1;
            double total = 0;
            for (int i = 0; i < octaves; i++) {
                double ridge = 1 - Math.abs(Noise.gradient(x, y, z));
                sum += ridge * ridge * amplitude;
                total += amplitude;
                amplitude *= 0.5;
                x *= 2;
                y *= 2;
                z *= 2;
            }
            return total == 0 ? 0 : sum / total * 2 - 1;
        });
        // Every Trowel noise, within [0, 1[.
        for (Noise.Kind kind : Noise.Kind.values()) {
            add(kind.id(), 3, 3, kind.id() + "(x, y, z) within [0, 1[", a -> f ->
                    Noise.sample(kind, a[0].eval(f), a[1].eval(f), a[2].eval(f)));
        }
        ALL.put("noise", ALL.get("fractal"));
        ALL.put("worley", ALL.get("distance"));
        add("simplex", 3, 3, "simplex(x, y, z) within [-1, 1]", a -> f -> Noise.gradient(a[0].eval(f), a[1].eval(f), a[2].eval(f)));

        // The world around the evaluated block.
        add("solid", 3, 3, "solid(dx, dy, dz): 1 if the block at this offset is solid", a -> f -> f.world != null
                && f.world.solid(f.blockX + (int) Math.round(a[0].eval(f)), f.blockY + (int) Math.round(a[1].eval(f)),
                f.blockZ + (int) Math.round(a[2].eval(f))) ? 1 : 0);
        add("air", 3, 3, "air(dx, dy, dz): 1 if the block at this offset is air", a -> f -> f.world == null
                || f.world.air(f.blockX + (int) Math.round(a[0].eval(f)), f.blockY + (int) Math.round(a[1].eval(f)),
                f.blockZ + (int) Math.round(a[2].eval(f))) ? 1 : 0);
    }

    private Functions() {
    }

    private static void unary(String name, java.util.function.DoubleUnaryOperator op) {
        add(name, 1, 1, name + "(v)", a -> {
            Node x = a[0];
            if (x instanceof Expression.Constant c) {
                return new Expression.Constant(op.applyAsDouble(c.value()));
            }
            return f -> op.applyAsDouble(x.eval(f));
        });
    }

    private static void binary(String name, java.util.function.DoubleBinaryOperator op) {
        add(name, 2, 2, name + "(a, b)", a -> {
            Node x = a[0];
            Node y = a[1];
            return f -> op.applyAsDouble(x.eval(f), y.eval(f));
        });
    }

    private static void add(String name, int min, int max, String usage, Builder builder) {
        ALL.put(name, new Entry(min, max, usage, builder));
    }

    static Node call(String name, Node[] args) {
        Entry entry = ALL.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("unknown function '" + name + "'");
        }
        if (args.length < entry.min || args.length > entry.max) {
            throw new IllegalArgumentException("'" + name + "' takes " + (entry.min == entry.max ? entry.min
                    : entry.min + " to " + entry.max) + " argument(s): " + entry.usage);
        }
        return entry.builder.build(args);
    }

    /** The functions and their usage, sorted, for the help. */
    public static Map<String, String> usages() {
        Map<String, String> out = new TreeMap<>();
        ALL.forEach((name, entry) -> out.put(name, entry.usage));
        return out;
    }

    public static List<String> names() {
        return List.copyOf(new TreeMap<>(ALL).keySet());
    }

    private static double offset(double seed, int axis) {
        long h = mix(Double.doubleToLongBits(seed + 0.0) * 31 + axis);
        return ((h >>> 11) * 0x1.0p-53) * 4096;
    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        return h ^ (h >>> 33);
    }
}
