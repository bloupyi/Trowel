package com.stackmc.trowel.geom;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A fully tuned noise, in the spirit of FastNoiseLite and ezEdits:
 * {@code cellular(f:0.15,cr:edge,cj:0.8,s:4)}, {@code perlin(f:0.05,ft:ridged,fo:5,wa:12)}.
 *
 * <p>The type comes first: {@code perlin}, {@code value}, {@code valuecubic}, {@code white},
 * {@code cellular}, {@code gabor}, or one of the {@link Noise.Kind} noises ({@code marble},
 * {@code strata}...). In parentheses, {@code name:value} settings separated by commas, in any
 * order, with their long or short name:</p>
 *
 * <ul>
 *   <li>{@code frequency (f)}, or {@code scale (sc)} its inverse; {@code seed (s)}, -1 for random;</li>
 *   <li>{@code x}, {@code y}, {@code z}: stretch an axis (y:0.1 lengthens ten times vertically);</li>
 *   <li>{@code fractaltype (ft)}: none, fbm, ridged, billow, pingpong; {@code octaves (fo)},
 *       {@code lacunarity (fl)}, {@code gain (fg)}, {@code weightedstrength (fw)}, {@code pingpongstrength (fp)};</li>
 *   <li>cellular: {@code jitter (cj)}, {@code distance (cd)}: e, sq, man, cheb, hybrid;
 *       {@code return (cr)}: cell, 1, sq, inv, 2, add, sub, mul, div, edge;</li>
 *   <li>gabor: {@code gaborfrequency (gf)}, {@code radius (gr)}, {@code impulses (gn)},
 *       {@code orientation (go)} in degrees, {@code jitter (gj)}, {@code spread (gfs)};</li>
 *   <li>warp: {@code warpamp (wa)} in blocks, {@code warpfreq (wf)}, {@code warpoctaves (wo)};</li>
 *   <li>output: {@code invert (i)}, {@code lower (l)} and {@code upper (u)} (bounds of the raw
 *       noise within [-1, 1], like ezEdits' forced mapping), {@code power (p)},
 *       {@code contrast (c)}, {@code steps (st)} for terraces.</li>
 * </ul>
 *
 * <p>The returned value is always within [0, 1[: a pattern turns it into a block of its
 * palette, a mask compares it to a threshold.</p>
 */
public final class NoiseSpec {

    public enum Base { PERLIN, VALUE, VALUECUBIC, WHITE, CELLULAR, GABOR, KIND }

    public enum Fractal { NONE, FBM, RIDGED, BILLOW, PINGPONG }

    public enum CellDistance { EUCLIDEAN, EUCLIDEAN_SQ, MANHATTAN, CHEBYSHEV, HYBRID }

    public enum CellReturn {
        CELL, DISTANCE, DISTANCE_SQ, INVERSE, LOG, EXP, DISTANCE2, ADD, SUB, MUL, DIV, DISTANCE2_SQ, DISTANCE2_INV,
        DISTANCE2_LOG, DISTANCE2_EXP, EDGE, ROUNDED
    }

    private Base base = Base.PERLIN;
    private Noise.Kind kind;
    private double frequency = 0.1;
    private long seed;
    private double sx = 1;
    private double sy = 1;
    private double sz = 1;
    private Fractal fractal = Fractal.FBM;
    private int octaves = 3;
    private double lacunarity = 2;
    private double gain = 0.5;
    private double weighted;
    private double pingpong = 2;
    private double jitter = 1;
    private CellDistance distance = CellDistance.EUCLIDEAN;
    private CellReturn returns = CellReturn.CELL;
    private double gaborFrequency = 2.5;
    private double gaborRadius = 0.9;
    private int impulses = 4;
    private double orientation;
    private double orientationJitter = 0.15;
    private double spread = 0.1;
    private double warpAmp;
    private double warpFreq = 1;
    private int warpOctaves = 1;
    private boolean invert;
    private double lower = Double.NaN;
    private double upper = Double.NaN;
    private double power = 1;
    private double contrast = 1;
    private int steps;
    private String text;

    private NoiseSpec() {
    }

    /** The default soft fractal noise, at this frequency. */
    public static NoiseSpec of(Noise.Kind kind, double frequency) {
        NoiseSpec spec = new NoiseSpec();
        spec.base = Base.KIND;
        spec.kind = kind;
        spec.fractal = Fractal.NONE;
        spec.frequency = frequency;
        spec.text = kind.id() + "(f:" + trim(frequency) + ")";
        return spec;
    }

    public String text() {
        return text;
    }

    public double frequency() {
        return frequency;
    }

    public NoiseSpec withFrequency(double f) {
        NoiseSpec copy = copy();
        copy.frequency = f;
        return copy;
    }

    public NoiseSpec withSeed(long value) {
        NoiseSpec copy = copy();
        copy.seed = value;
        return copy;
    }

    private NoiseSpec copy() {
        NoiseSpec c = new NoiseSpec();
        c.base = base;
        c.kind = kind;
        c.frequency = frequency;
        c.seed = seed;
        c.sx = sx;
        c.sy = sy;
        c.sz = sz;
        c.fractal = fractal;
        c.octaves = octaves;
        c.lacunarity = lacunarity;
        c.gain = gain;
        c.weighted = weighted;
        c.pingpong = pingpong;
        c.jitter = jitter;
        c.distance = distance;
        c.returns = returns;
        c.gaborFrequency = gaborFrequency;
        c.gaborRadius = gaborRadius;
        c.impulses = impulses;
        c.orientation = orientation;
        c.orientationJitter = orientationJitter;
        c.spread = spread;
        c.warpAmp = warpAmp;
        c.warpFreq = warpFreq;
        c.warpOctaves = warpOctaves;
        c.invert = invert;
        c.lower = lower;
        c.upper = upper;
        c.power = power;
        c.contrast = contrast;
        c.steps = steps;
        c.text = text;
        return c;
    }

    // ----------------------------------------------------------------- parsing

    /** Known types, for completion. */
    public static List<String> types() {
        List<String> out = new java.util.ArrayList<>(List.of("perlin", "value", "valuecubic", "white", "cellular", "gabor"));
        for (Noise.Kind kind : Noise.Kind.values()) {
            if (!out.contains(kind.id())) {
                out.add(kind.id());
            }
        }
        return out;
    }

    /** Settings, short name to what they do, for help and completion. */
    public static final Map<String, String> PARAMETERS = new LinkedHashMap<>();

    static {
        PARAMETERS.put("f", "frequency (0.1 = patches of 10 blocks)");
        PARAMETERS.put("sc", "scale in blocks (inverse of f)");
        PARAMETERS.put("s", "seed, -1 for random");
        PARAMETERS.put("x", "x axis stretch");
        PARAMETERS.put("y", "y axis stretch");
        PARAMETERS.put("z", "z axis stretch");
        PARAMETERS.put("ft", "fractal: none, fbm, ridged, billow, pingpong");
        PARAMETERS.put("fo", "octaves");
        PARAMETERS.put("fl", "lacunarity");
        PARAMETERS.put("fg", "gain");
        PARAMETERS.put("fw", "weighted strength");
        PARAMETERS.put("fp", "pingpong strength");
        PARAMETERS.put("cj", "cells: disorder (0 grid, 1 random)");
        PARAMETERS.put("cd", "cells: distance e, sq, man, cheb, hybrid");
        PARAMETERS.put("cr", "cells: return cell, 1, sq, inv, 2, add, sub, mul, div, edge");
        PARAMETERS.put("gf", "gabor: stripe frequency");
        PARAMETERS.put("gr", "gabor: kernel size");
        PARAMETERS.put("gn", "gabor: kernels per cell");
        PARAMETERS.put("go", "gabor: orientation (degrees)");
        PARAMETERS.put("gj", "gabor: orientation disorder");
        PARAMETERS.put("gfs", "gabor: frequency spread");
        PARAMETERS.put("wa", "warp: amplitude in blocks");
        PARAMETERS.put("wf", "warp: relative frequency");
        PARAMETERS.put("wo", "warp: octaves");
        PARAMETERS.put("i", "invert (true/false)");
        PARAMETERS.put("l", "lower bound of the raw noise (-1..1)");
        PARAMETERS.put("u", "upper bound of the raw noise (-1..1)");
        PARAMETERS.put("p", "power (contrast of the lows)");
        PARAMETERS.put("c", "contrast around the middle");
        PARAMETERS.put("st", "steps");
    }

    /**
     * Reads {@code type(name:value,...)}, {@code type} or, for compatibility, a bare noise name.
     *
     * @param defaultFrequency frequency if the text gives none
     */
    public static NoiseSpec parse(String raw, double defaultFrequency) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty noise.");
        }
        String text = raw.trim();
        if (text.startsWith("@@")) {
            text = text.substring(2);
        }
        int open = text.indexOf('(');
        String type = (open < 0 ? text : text.substring(0, open)).trim().toLowerCase(Locale.ROOT);
        String body = "";
        if (open >= 0) {
            if (!text.endsWith(")")) {
                throw new IllegalArgumentException("Noise: missing closing parenthesis in " + raw);
            }
            body = text.substring(open + 1, text.length() - 1);
        }
        NoiseSpec spec = new NoiseSpec();
        spec.frequency = defaultFrequency;
        spec.text = raw.trim();
        switch (type) {
            case "perlin", "pe", "gradient", "simplex", "opensimplex2", "si", "opensimplex2s", "sm" -> spec.base = Base.PERLIN;
            case "value", "va" -> spec.base = Base.VALUE;
            case "valuecubic", "vc" -> spec.base = Base.VALUECUBIC;
            case "white", "wh" -> {
                spec.base = Base.WHITE;
                spec.fractal = Fractal.NONE;
            }
            case "cellular", "ce", "cell" -> {
                spec.base = Base.CELLULAR;
                spec.fractal = Fractal.NONE;
            }
            case "gabor", "gb" -> {
                spec.base = Base.GABOR;
                spec.fractal = Fractal.NONE;
            }
            case "shard", "sh" -> {
                spec.base = Base.CELLULAR;
                spec.fractal = Fractal.NONE;
                spec.returns = CellReturn.EDGE;
                spec.distance = CellDistance.MANHATTAN;
            }
            default -> {
                Noise.Kind kind = Noise.Kind.parse(type);
                if (kind == null) {
                    throw new IllegalArgumentException("Unknown noise: " + type + ". Types: " + String.join(", ", types()));
                }
                spec.base = Base.KIND;
                spec.kind = kind;
                spec.fractal = Fractal.NONE;
            }
        }
        if (!body.isBlank()) {
            for (String part : body.split(",")) {
                int colon = part.indexOf(':');
                if (colon <= 0) {
                    throw new IllegalArgumentException("Noise: setting '" + part.trim() + "' without a value (name:value).");
                }
                spec.set(part.substring(0, colon).trim().toLowerCase(Locale.ROOT), part.substring(colon + 1).trim());
            }
        }
        if (spec.seed == -1) {
            spec.seed = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        }
        return spec;
    }

    private void set(String name, String value) {
        String v = value.toLowerCase(Locale.ROOT);
        switch (name) {
            case "frequency", "freq", "f" -> frequency = positive(v, name);
            case "scale", "sc", "size" -> frequency = 1 / positive(v, name);
            case "seed", "s" -> seed = (long) number(v, name);
            case "xscaling", "x" -> sx = number(v, name);
            case "yscaling", "y" -> sy = number(v, name);
            case "zscaling", "z" -> sz = number(v, name);
            case "fractaltype", "ft", "fractal" -> fractal = switch (v) {
                case "none", "n" -> Fractal.NONE;
                case "fbm", "f" -> Fractal.FBM;
                case "ridged", "r" -> Fractal.RIDGED;
                case "billow", "b" -> Fractal.BILLOW;
                case "pingpong", "p" -> Fractal.PINGPONG;
                default -> throw new IllegalArgumentException("Fractal: none, fbm, ridged, billow or pingpong.");
            };
            case "octaves", "fo", "oct" -> octaves = (int) Math.max(1, Math.min(10, number(v, name)));
            case "lacunarity", "fl", "lac" -> lacunarity = number(v, name);
            case "gain", "fg" -> gain = number(v, name);
            case "weightedstrength", "fw" -> weighted = number(v, name);
            case "pingpongstrength", "fp" -> pingpong = number(v, name);
            case "cellularjittermodifier", "jitter", "cj", "jit" -> jitter = number(v, name);
            case "cellulardistancefunction", "distance", "cd" -> distance = switch (v) {
                case "euclidean", "e" -> CellDistance.EUCLIDEAN;
                case "euclideansq", "sq" -> CellDistance.EUCLIDEAN_SQ;
                case "manhattan", "man", "m1", "minkowski1" -> CellDistance.MANHATTAN;
                case "chebyshev", "cheb", "m99", "minkowski99" -> CellDistance.CHEBYSHEV;
                case "hybrid", "h", "rounded", "r", "m4", "minkowski4" -> CellDistance.HYBRID;
                default -> throw new IllegalArgumentException("Distance: e, sq, man, cheb or hybrid.");
            };
            case "cellularreturntype", "return", "cr" -> returns = switch (v) {
                case "cellvalue", "cell" -> CellReturn.CELL;
                case "distance", "1" -> CellReturn.DISTANCE;
                case "distancesquared", "sq" -> CellReturn.DISTANCE_SQ;
                case "distanceinverse", "inv" -> CellReturn.INVERSE;
                case "distancelog", "log" -> CellReturn.LOG;
                case "distanceexp", "exp" -> CellReturn.EXP;
                case "distance2sq", "2sq" -> CellReturn.DISTANCE2_SQ;
                case "distance2inv", "2inv" -> CellReturn.DISTANCE2_INV;
                case "distance2log", "2log" -> CellReturn.DISTANCE2_LOG;
                case "distance2exp", "2exp" -> CellReturn.DISTANCE2_EXP;
                case "rounded", "r" -> CellReturn.ROUNDED;
                case "distance2", "2" -> CellReturn.DISTANCE2;
                case "distance2add", "2add", "add" -> CellReturn.ADD;
                case "distance2sub", "2sub", "sub" -> CellReturn.SUB;
                case "distance2mul", "2mul", "mul" -> CellReturn.MUL;
                case "distance2div", "2div", "div" -> CellReturn.DIV;
                case "edge", "e" -> CellReturn.EDGE;
                default -> throw new IllegalArgumentException("Return: cell, 1, sq, inv, log, exp, 2, 2add, 2sub, 2mul, "
                        + "2div, 2sq, 2inv, 2log, 2exp, edge or r (rounded).");
            };
            case "gaborfrequency", "gf" -> gaborFrequency = number(v, name);
            case "gaborradius", "gr" -> gaborRadius = positive(v, name);
            case "gaborimpulsespercell", "gn" -> impulses = (int) Math.max(1, Math.min(8, number(v, name)));
            case "gabororientation", "go" -> orientation = Math.toRadians(number(v, name));
            case "gabororientationjitter", "gj" -> orientationJitter = number(v, name);
            case "gaborfrequencyspread", "gfs" -> spread = number(v, name);
            case "domainwarpamp", "warpamp", "wa", "warp" -> warpAmp = number(v, name);
            case "domainwarpfreq", "warpfreq", "wf" -> warpFreq = positive(v, name);
            case "domainwarpoct", "warpoctaves", "wo" -> warpOctaves = (int) Math.max(1, Math.min(4, number(v, name)));
            case "inverted", "invert", "i" -> invert = v.equals("true") || v.equals("1") || v.equals("yes");
            case "lowerbound", "lower", "l" -> lower = number(v, name);
            case "upperbound", "upper", "u" -> upper = number(v, name);
            case "power", "p", "pow" -> power = positive(v, name);
            case "contrast", "c" -> contrast = number(v, name);
            case "steps", "st", "terrace" -> steps = (int) Math.max(0, Math.min(64, number(v, name)));
            case "valuemapping", "m", "mn", "mx" -> {
                // ezEdits forced mapping: the l and u bounds are enough here.
            }
            default -> throw new IllegalArgumentException("Unknown noise setting: " + name + ". Settings: "
                    + String.join(", ", PARAMETERS.keySet()));
        }
    }

    private static double number(String raw, String name) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Noise: " + name + " takes a number, not '" + raw + "'.");
        }
    }

    private static double positive(String raw, String name) {
        double v = number(raw, name);
        if (v <= 0) {
            throw new IllegalArgumentException("Noise: " + name + " must be positive.");
        }
        return v;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ------------------------------------------------------------- computing

    /** The noise value at this point of the world, within [0, 1[. */
    public double sample(double x, double y, double z) {
        double ox = seedOffset(1);
        double oy = seedOffset(2);
        double oz = seedOffset(3);
        double px = x * frequency * sx + ox;
        double py = y * frequency * sy + oy;
        double pz = z * frequency * sz + oz;
        if (warpAmp != 0) {
            double a = warpAmp * frequency;
            double wx = 0;
            double wy = 0;
            double wz = 0;
            double amp = 1;
            double f = warpFreq;
            for (int i = 0; i < warpOctaves; i++) {
                wx += amp * Noise.gradient(px * f + 13.7, py * f + 5.1, pz * f + 91.3);
                wy += amp * Noise.gradient(px * f + 47.2, py * f + 71.9, pz * f + 3.3);
                wz += amp * Noise.gradient(px * f + 83.5, py * f + 29.4, pz * f + 61.8);
                amp *= 0.5;
                f *= 2;
            }
            px += wx * a;
            py += wy * a;
            pz += wz * a;
        }
        double raw = fractal(px, py, pz);
        if (!Double.isNaN(lower) || !Double.isNaN(upper)) {
            double lo = Double.isNaN(lower) ? -1 : lower;
            double hi = Double.isNaN(upper) ? 1 : upper;
            raw = hi == lo ? (raw >= lo ? 1 : -1) : (raw - lo) / (hi - lo) * 2 - 1;
        }
        double v = (raw + 1) / 2;
        if (invert) {
            v = 1 - v;
        }
        if (contrast != 1) {
            v = (v - 0.5) * contrast + 0.5;
        }
        v = Math.max(0, Math.min(1, v));
        if (power != 1) {
            v = Math.pow(v, power);
        }
        if (steps > 1) {
            v = (Math.floor(v * steps) + 0.5) / steps;
        }
        return Math.min(0.999999, Math.max(0, v));
    }

    private double seedOffset(int axis) {
        if (seed == 0) {
            return 0;
        }
        long h = mix(seed * 0x9E3779B97F4A7C15L + axis);
        return ((h >>> 11) * 0x1.0p-53) * 8192;
    }

    /** Sum of the octaves, within [-1, 1]. */
    private double fractal(double x, double y, double z) {
        if (fractal == Fractal.NONE) {
            return base(x, y, z, 0);
        }
        double sum = 0;
        double amp = 1;
        double total = 0;
        double freq = 1;
        for (int i = 0; i < octaves; i++) {
            double n = base(x * freq, y * freq, z * freq, i);
            double v = switch (fractal) {
                case RIDGED -> {
                    double r = 1 - Math.abs(n);
                    yield r * r * 2 - 1;
                }
                case BILLOW -> Math.abs(n) * 2 - 1;
                case PINGPONG -> {
                    double t = (n + 1) * pingpong;
                    t = t - 2 * Math.floor(t / 2);
                    yield (t < 1 ? t : 2 - t) * 2 - 1;
                }
                default -> n;
            };
            sum += v * amp;
            total += amp;
            amp *= gain * (1 - weighted + weighted * Math.min(1, (v + 1) / 2));
            freq *= lacunarity;
        }
        double out = total == 0 ? 0 : sum / total;
        if (fractal == Fractal.FBM) {
            out *= 1.35;
        }
        return Math.max(-1, Math.min(1, out));
    }

    /** One octave, within [-1, 1]. */
    private double base(double x, double y, double z, int octave) {
        return switch (base) {
            case PERLIN -> Math.max(-1, Math.min(1, Noise.gradient(x + octave * 31.7, y + octave * 17.1, z + octave * 11.3) * 1.2));
            case VALUE -> value(x + octave * 31.7, y, z, false);
            case VALUECUBIC -> value(x + octave * 31.7, y, z, true);
            case WHITE -> unit(hash((int) Math.floor(x * 7), (int) Math.floor(y * 7), (int) Math.floor(z * 7), octave)) * 2 - 1;
            case CELLULAR -> cellular(x, y, z, octave);
            case GABOR -> gabor(x, y, z, octave);
            case KIND -> Noise.sample(kind, x, y, z) * 2 - 1;
        };
    }

    private static double value(double x, double y, double z, boolean cubic) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double tx = x - x0;
        double ty = y - y0;
        double tz = z - z0;
        if (cubic) {
            tx = tx * tx * tx * (tx * (tx * 6 - 15) + 10);
            ty = ty * ty * ty * (ty * (ty * 6 - 15) + 10);
            tz = tz * tz * tz * (tz * (tz * 6 - 15) + 10);
        } else {
            tx = tx * tx * (3 - 2 * tx);
            ty = ty * ty * (3 - 2 * ty);
            tz = tz * tz * (3 - 2 * tz);
        }
        double c000 = unit(hash(x0, y0, z0, 5));
        double c100 = unit(hash(x0 + 1, y0, z0, 5));
        double c010 = unit(hash(x0, y0 + 1, z0, 5));
        double c110 = unit(hash(x0 + 1, y0 + 1, z0, 5));
        double c001 = unit(hash(x0, y0, z0 + 1, 5));
        double c101 = unit(hash(x0 + 1, y0, z0 + 1, 5));
        double c011 = unit(hash(x0, y0 + 1, z0 + 1, 5));
        double c111 = unit(hash(x0 + 1, y0 + 1, z0 + 1, 5));
        double a = lerp(tx, c000, c100);
        double b = lerp(tx, c010, c110);
        double c = lerp(tx, c001, c101);
        double d = lerp(tx, c011, c111);
        return lerp(tz, lerp(ty, a, b), lerp(ty, c, d)) * 2 - 1;
    }

    private double cellular(double x, double y, double z, int octave) {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        double f1 = Double.MAX_VALUE;
        double f2 = Double.MAX_VALUE;
        long owner = 0;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (int k = -1; k <= 1; k++) {
                    long h = hash(cx + i, cy + j, cz + k, 7 + octave);
                    double dx = cx + i + 0.5 + (unit(h) - 0.5) * jitter - x;
                    double dy = cy + j + 0.5 + (unit(h >>> 21) - 0.5) * jitter - y;
                    double dz = cz + k + 0.5 + (unit(h >>> 42) - 0.5) * jitter - z;
                    double d = switch (distance) {
                        case EUCLIDEAN, EUCLIDEAN_SQ -> dx * dx + dy * dy + dz * dz;
                        case MANHATTAN -> Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        case CHEBYSHEV -> Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
                        case HYBRID -> Math.abs(dx) + Math.abs(dy) + Math.abs(dz) + (dx * dx + dy * dy + dz * dz);
                    };
                    if (d < f1) {
                        f2 = f1;
                        f1 = d;
                        owner = h;
                    } else if (d < f2) {
                        f2 = d;
                    }
                }
            }
        }
        if (distance == CellDistance.EUCLIDEAN) {
            f1 = Math.sqrt(f1);
            f2 = Math.sqrt(f2);
        } else if (distance == CellDistance.HYBRID) {
            f1 *= 0.5;
            f2 *= 0.5;
        }
        double v = switch (returns) {
            case CELL -> unit(owner * 0x9E3779B97F4A7C15L);
            case DISTANCE -> f1;
            case DISTANCE_SQ -> f1 * f1;
            case INVERSE -> 1 - Math.min(1, f1);
            case DISTANCE2 -> f2;
            case ADD -> (f1 + f2) * 0.5;
            case SUB -> (f2 - f1);
            case MUL -> f1 * f2;
            case DIV -> f2 == 0 ? 0 : f1 / f2;
            case LOG -> Math.log1p(f1 * 1.718281828);
            case EXP -> 1 - Math.exp(-f1 * 2);
            case DISTANCE2_SQ -> f2 * f2;
            case DISTANCE2_INV -> 1 - Math.min(1, f2);
            case DISTANCE2_LOG -> Math.log1p(f2 * 1.718281828);
            case DISTANCE2_EXP -> 1 - Math.exp(-f2 * 2);
            case EDGE -> Math.min(1, (f2 - f1) * 1.8);
            // Pebbles: a dome inside each cell, rounded down to zero at the edges.
            case ROUNDED -> Math.sqrt(Math.max(0, 1 - Math.pow(Math.min(1, f1 / Math.max(1e-6, (f1 + f2) * 0.5)), 2)));
        };
        return Math.max(-1, Math.min(1, v * 2 - 1));
    }

    /** Gabor noise: oriented stripes, in sparse impulses (kernel convolution). */
    private double gabor(double x, double y, double z, int octave) {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        double sum = 0;
        double radius2 = gaborRadius * gaborRadius;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (int k = -1; k <= 1; k++) {
                    long h = hash(cx + i, cy + j, cz + k, 31 + octave);
                    for (int n = 0; n < impulses; n++) {
                        h = mix(h + n * 0x632BE59BD9B4E019L);
                        double px = cx + i + unit(h) - x;
                        double py = cy + j + unit(h >>> 13) - y;
                        double pz = cz + k + unit(h >>> 26) - z;
                        double d2 = px * px + py * py + pz * pz;
                        if (d2 > radius2) {
                            continue;
                        }
                        double angle = orientation + (unit(h >>> 39) - 0.5) * Math.PI * 2 * orientationJitter;
                        double freq = gaborFrequency * (1 + (unit(h >>> 5) - 0.5) * 2 * spread);
                        double along = px * Math.cos(angle) + pz * Math.sin(angle) + py * 0.15;
                        double weight = ((h >>> 50) & 1) == 0 ? 1 : -1;
                        sum += weight * Math.exp(-Math.PI * d2 / radius2 * 2) * Math.cos(2 * Math.PI * freq * along);
                    }
                }
            }
        }
        return Math.max(-1, Math.min(1, sum / Math.sqrt(impulses) * 0.9));
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static long hash(int x, int y, int z, int seed) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ seed * 0xD6E8FEB86659FD93L;
        return mix(h);
    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        return h ^ (h >>> 33);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
