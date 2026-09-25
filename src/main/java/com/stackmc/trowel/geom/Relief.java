package com.stackmc.trowel.geom;

import java.util.Locale;

/**
 * The reliefs of the terrain generator, like Arceon's Terragen: a height within [0, 1]
 * for each column.
 */
public enum Relief {
    HILLS("hills", "Hills"),
    MOUNTAINS("mountains", "Mountains"),
    PLAINS("plains", "Plains"),
    DUNES("dunes", "Dunes"),
    MESA("mesa", "Mesas"),
    ISLANDS("islands", "Islands"),
    CRATERS("craters", "Craters"),
    CANYONS("canyons", "Canyons"),
    VOLCANOES("volcanoes", "Volcanoes"),
    VALLEYS("valleys", "Valleys"),
    WARPED("warped", "Warped");

    private final String id;
    private final String label;

    Relief(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static Relief parse(String raw) {
        if (raw == null) {
            return null;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        for (Relief relief : values()) {
            if (relief.id.equals(lower) || relief.label.toLowerCase(Locale.ROOT).equals(lower)) {
                return relief;
            }
        }
        return null;
    }

    /**
     * Relative height of the column (x, z).
     *
     * @param scale  size of the shapes, in blocks
     * @param dx     position of the column relative to the center of the area, normalized within [-1, 1]
     * @param dz     same along Z
     */
    public double height(int x, int z, double scale, double dx, double dz) {
        double u = x / scale;
        double v = z / scale;
        double base = Noise.fractal(u, 0, v);
        return clamp(switch (this) {
            case HILLS -> base;
            case MOUNTAINS -> Math.pow(Noise.sample(Noise.Kind.RIDGED, u, 0, v), 1.4);
            case PLAINS -> 0.15 + base * 0.25;
            case DUNES -> 0.25 + 0.5 * (0.5 + 0.5 * Math.sin(u * Math.PI * 2 + base * 4)) * (0.6 + 0.4 * base);
            case MESA -> Math.floor(base * 5) / 5 + 0.1;
            case ISLANDS -> base * Math.max(0, 1 - (dx * dx + dz * dz)) * 1.4;
            case CRATERS -> Math.min(1, Noise.sample(Noise.Kind.DISTANCE, u, 0, v) * 1.3) * 0.7 + base * 0.3;
            case CANYONS -> 0.35 + 0.65 * Math.min(1, Noise.sample(Noise.Kind.CRACKS, u, 0, v) * 3) * (0.7 + 0.3 * base);
            case VOLCANOES -> {
                double cone = Math.max(0, 1 - Noise.sample(Noise.Kind.DISTANCE, u * 0.6, 0, v * 0.6) * 1.6);
                double crater = cone > 0.78 ? 0.78 - (cone - 0.78) * 1.8 : cone;
                yield 0.08 + crater * 0.85 + base * 0.07;
            }
            case VALLEYS -> 1 - 0.8 * Noise.sample(Noise.Kind.RIDGED, u * 0.7, 0, v * 0.7) - 0.1 * base;
            case WARPED -> Noise.sample(Noise.Kind.WARP, u * 0.7, 0, v * 0.7);
        });
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
