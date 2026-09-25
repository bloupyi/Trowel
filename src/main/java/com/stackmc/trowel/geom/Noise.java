package com.stackmc.trowel.geom;

import java.util.Locale;

/**
 * Deterministic noises to texture and generate: the same point always gives the same value,
 * so two passes of the same pattern join up.
 *
 * <p>The base is a gradient noise (improved Perlin): unlike a value noise, it does not show the
 * block grid. On top come the families of common terrain tools (Arceon, ezEdits, WorldPainter):
 * fractal, ridges, cells, cracks, and more built patterns (strata, marble, rings, hexagons)
 * that look good on a wall or a floor.</p>
 */
public final class Noise {

    /** A noise family; all return a value within [0, 1[. */
    public enum Kind {
        /** Soft fractal noise: hills, natural patches. */
        FRACTAL("fractal", "Fractal"),
        /** A single octave: large smooth waves. */
        SMOOTH("smooth", "Smooth"),
        /** Sharp ridges: mountains, veins. */
        RIDGED("ridged", "Ridges"),
        /** Rounded bumps: clouds, moss. */
        BILLOW("billow", "Billows"),
        /** Fractal twisted by itself: swirls, flowing rock. */
        WARP("warp", "Swirls"),
        /** Stepped fractal: terraces, rice fields. */
        TERRACE("terrace", "Terraces"),
        /** Winding bands: marble, veined wood. */
        MARBLE("marble", "Marble"),
        /** Wavy horizontal layers: sedimentary cliffs, badlands. */
        STRATA("strata", "Strata"),
        /** Cells: slabs, stones, scales. */
        CELLS("cells", "Cells"),
        /** Distance to the cell centers: bubbles, pebbles. */
        DISTANCE("distance", "Distance"),
        /** Cell edges: cracks, crazing. */
        CRACKS("cracks", "Cracks"),
        /** Circles around each cell: tree rings, ripples. */
        RINGS("rings", "Rings"),
        /** Diagonal waves: rippled sand, tiles. */
        WAVES("waves", "Waves"),
        /** Vertical cells: basalt columns, prisms. */
        COLUMNS("columns", "Columns"),
        /** Vertical streaks: drips, facade wear. */
        DRIPS("drips", "Drips"),
        /** Spiral around the center: galaxies, rosettes. */
        SPIRAL("spiral", "Spiral"),
        /** Checkerboard: floors, chessboards. */
        CHECKER("checker", "Checker"),
        /** Hexagons: tilings, beehives. */
        HEXAGONS("hexagons", "Hexagons"),
        /** Regular dither: dotted transitions without clumps. */
        DITHER("dither", "Dither"),
        /** Random, without neighbourhood: dust, grain. */
        WHITE("white", "Random"),
        /** Perlin turbulence: the sum of absolute values, wisps of smoke. */
        TURBULENCE("turbulence", "Turbulence"),
        /** Lightning: thin branching lines, like Arceon's electric noise. */
        ELECTRIC("electric", "Lightning");

        private final String id;
        private final String label;

        Kind(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public static Kind parse(String raw) {
            if (raw == null) {
                return null;
            }
            String lower = raw.trim().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.id.equals(lower) || kind.label.toLowerCase(Locale.ROOT).equals(lower)) {
                    return kind;
                }
            }
            return switch (lower) {
                case "perlin", "simplex", "fbm" -> FRACTAL;
                case "voronoi", "cellular" -> CELLS;
                case "worley" -> DISTANCE;
                case "crack" -> CRACKS;
                case "random" -> WHITE;
                case "warped" -> WARP;
                case "cell", "arcvor" -> CELLS;
                case "lightning", "veins" -> ELECTRIC;
                case "layers", "sediment" -> STRATA;
                case "basalt" -> COLUMNS;
                case "hex", "honeycomb" -> HEXAGONS;
                case "bayer" -> DITHER;
                default -> null;
            };
        }
    }

    private static final int[] PERMUTATION = new int[512];
    private static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    private static final double SQRT3 = Math.sqrt(3);

    static {
        int[] base = new int[256];
        for (int i = 0; i < 256; i++) {
            base[i] = i;
        }
        long seed = 0x5DEECE66DL;
        for (int i = 255; i > 0; i--) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int j = (int) ((seed >>> 33) % (i + 1));
            int swap = base[i];
            base[i] = base[j];
            base[j] = swap;
        }
        for (int i = 0; i < 512; i++) {
            PERMUTATION[i] = base[i & 255];
        }
    }

    private Noise() {
    }

    public static double sample(Kind kind, double x, double y, double z) {
        return clamp(switch (kind) {
            case FRACTAL -> fractal(x, y, z);
            case SMOOTH -> 0.5 + perlin(x, y, z) * 0.75;
            case RIDGED -> octaves(x, y, z, 1);
            case BILLOW -> octaves(x, y, z, 2);
            case WARP -> fractal(x + 2.5 * fractal(x + 5.2, y + 1.3, z + 7.1),
                    y + 2.5 * fractal(x + 1.7, y + 9.2, z + 3.4),
                    z + 2.5 * fractal(x + 8.3, y + 2.8, z + 4.6));
            case TERRACE -> (Math.floor(fractal(x, y, z) * 6) + 0.5) / 6;
            case MARBLE -> 0.5 + 0.5 * Math.sin((x * 0.8 + y * 0.4 + z * 0.6 + 4 * (fractal(x, y, z) - 0.5)) * Math.PI);
            case STRATA -> fraction(y + 0.9 * (fractal(x * 0.35, y * 0.35, z * 0.35) - 0.5));
            case CELLS -> voronoi(x, y, z);
            case DISTANCE -> cells(x, y, z)[0];
            case CRACKS -> {
                double[] d = cells(x, y, z);
                yield (d[1] - d[0]) * 1.6;
            }
            case RINGS -> fraction(cells(x, y, z)[0] * 3.5 + 0.25 * (fractal(x, y, z) - 0.5));
            case WAVES -> 0.5 + 0.5 * Math.sin((x + z * 0.6) * Math.PI + 2.2 * (fractal(x * 0.5, y * 0.5, z * 0.5) - 0.5));
            case COLUMNS -> voronoi(x * 1.5, 0, z * 1.5);
            case DRIPS -> fractal(x * 1.6, y * 0.12, z * 1.6);
            case SPIRAL -> fraction(Math.atan2(z, x) / (2 * Math.PI) * 3 + Math.sqrt(x * x + z * z) * 0.35
                    + 0.15 * (fractal(x, y, z) - 0.5));
            case CHECKER -> ((((long) Math.floor(x) + (long) Math.floor(y) + (long) Math.floor(z)) & 1) == 0) ? 0.25 : 0.75;
            case HEXAGONS -> hexagon(x, z);
            case DITHER -> dither(x, y, z);
            case WHITE -> unit(hash((int) Math.floor(x * 97), (int) Math.floor(y * 97), (int) Math.floor(z * 97), 11));
            case TURBULENCE -> {
                double sum = 0;
                double amplitude = 1;
                double total = 0;
                double f = 1;
                for (int i = 0; i < 5; i++) {
                    sum += Math.abs(perlin(x * f + i * 19.3, y * f + i * 7.1, z * f + i * 3.7)) * amplitude;
                    total += amplitude;
                    amplitude *= 0.5;
                    f *= 2;
                }
                yield sum / total * 2.2;
            }
            case ELECTRIC -> {
                double n = perlin(x, y, z) * 0.7 + perlin(x * 2.1 + 5.3, y * 2.1 + 1.7, z * 2.1 + 8.9) * 0.3;
                yield Math.pow(Math.max(0, 1 - Math.abs(n) * 2.2), 6);
            }
        });
    }

    /** Soft fractal noise, within [0, 1[. */
    public static double fractal(double x, double y, double z) {
        return octaves(x, y, z, 0);
    }

    /**
     * Four octaves of gradient noise; {@code shape} 0 for fractal, 1 for ridges,
     * 2 for billows.
     *
     * <p>The sum of octaves bunches towards the middle: it is stretched so each band of a
     * pattern gets used about as much.</p>
     */
    private static double octaves(double x, double y, double z, int shape) {
        double sum = 0;
        double amplitude = 1;
        double total = 0;
        double frequency = 1;
        for (int octave = 0; octave < 4; octave++) {
            double n = perlin(x * frequency + octave * 31.7, y * frequency + octave * 17.1, z * frequency + octave * 11.3);
            double v = switch (shape) {
                case 1 -> {
                    double ridge = 1 - Math.abs(n);
                    yield ridge * ridge;
                }
                case 2 -> Math.abs(n);
                default -> n * 0.5 + 0.5;
            };
            sum += v * amplitude;
            total += amplitude;
            amplitude *= 0.5;
            frequency *= 2;
        }
        double v = sum / total;
        return clamp(switch (shape) {
            case 1 -> (v - 0.55) * 1.8 + 0.5;
            case 2 -> (v - 0.25) * 1.9 + 0.35;
            default -> (v - 0.5) * 2.4 + 0.5;
        });
    }

    /** Gradient noise, within [-1, 1], for those composing their own noises. */
    public static double gradient(double x, double y, double z) {
        return perlin(x, y, z);
    }

    /** Ken Perlin's improved gradient noise, within [-1, 1]. */
    static double perlin(double x, double y, double z) {
        int xi = (int) Math.floor(x) & 255;
        int yi = (int) Math.floor(y) & 255;
        int zi = (int) Math.floor(z) & 255;
        x -= Math.floor(x);
        y -= Math.floor(y);
        z -= Math.floor(z);
        double u = fade(x);
        double v = fade(y);
        double w = fade(z);
        int[] p = PERMUTATION;
        int a = p[xi] + yi;
        int aa = p[a] + zi;
        int ab = p[a + 1] + zi;
        int b = p[xi + 1] + yi;
        int ba = p[b] + zi;
        int bb = p[b + 1] + zi;
        return lerp(w,
                lerp(v, lerp(u, grad(p[aa], x, y, z), grad(p[ba], x - 1, y, z)),
                        lerp(u, grad(p[ab], x, y - 1, z), grad(p[bb], x - 1, y - 1, z))),
                lerp(v, lerp(u, grad(p[aa + 1], x, y, z - 1), grad(p[ba + 1], x - 1, y, z - 1)),
                        lerp(u, grad(p[ab + 1], x, y - 1, z - 1), grad(p[bb + 1], x - 1, y - 1, z - 1))));
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /** Closest Voronoi cell, as a value within [0, 1[ specific to the cell. */
    public static double voronoi(double x, double y, double z) {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        double best = Double.MAX_VALUE;
        long owner = 0;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (int k = -1; k <= 1; k++) {
                    long h = hash(cx + i, cy + j, cz + k, 7);
                    double d = distance(cx + i, cy + j, cz + k, h, x, y, z);
                    if (d < best) {
                        best = d;
                        owner = h;
                    }
                }
            }
        }
        return unit(owner * 0x9E3779B97F4A7C15L);
    }

    /** Distances to the closest and second closest cell center. */
    private static double[] cells(double x, double y, double z) {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        double first = Double.MAX_VALUE;
        double second = Double.MAX_VALUE;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (int k = -1; k <= 1; k++) {
                    long h = hash(cx + i, cy + j, cz + k, 7);
                    double d = Math.sqrt(distance(cx + i, cy + j, cz + k, h, x, y, z));
                    if (d < first) {
                        second = first;
                        first = d;
                    } else if (d < second) {
                        second = d;
                    }
                }
            }
        }
        return new double[]{first, second};
    }

    private static double distance(int gx, int gy, int gz, long h, double x, double y, double z) {
        double px = gx + unit(h);
        double py = gy + unit(h >>> 21);
        double pz = gz + unit(h >>> 42);
        return (px - x) * (px - x) + (py - y) * (py - y) + (pz - z) * (pz - z);
    }

    /** The hexagon containing (x, z), as a value specific to the hexagon. */
    private static double hexagon(double x, double z) {
        double q = SQRT3 / 3 * x - z / 3;
        double r = 2.0 / 3 * z;
        double s = -q - r;
        long rq = Math.round(q);
        long rr = Math.round(r);
        long rs = Math.round(s);
        double dq = Math.abs(rq - q);
        double dr = Math.abs(rr - r);
        double ds = Math.abs(rs - s);
        if (dq > dr && dq > ds) {
            rq = -rr - rs;
        } else if (dr > ds) {
            rr = -rq - rs;
        }
        return unit(hash((int) rq, 0, (int) rr, 3));
    }

    /** 4 x 4 Bayer matrix: a regular threshold, without clumps or holes. */
    private static double dither(double x, double y, double z) {
        int ix = (int) Math.floorMod((long) Math.floor(x), 4);
        int iy = (int) Math.floorMod((long) Math.floor(y), 4);
        int iz = (int) Math.floorMod((long) Math.floor(z), 4);
        return (BAYER[ix][(iz + iy * 2) & 3] + 0.5) / 16;
    }

    private static double fraction(double v) {
        return v - Math.floor(v);
    }

    private static double clamp(double v) {
        return Math.min(0.999999, Math.max(0, v));
    }

    private static long hash(int x, int y, int z, int seed) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ seed * 0xD6E8FEB86659FD93L;
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
