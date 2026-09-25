package com.stackmc.trowel.brush;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings of a brush, stored in the item itself: two brushes in the hotbar are two
 * different tools, like with goPaint.
 */
public record BrushSettings(BrushType type, int size, int height, int intensity, int chance, int falloff,
                            int iterations, String preset, String pattern, String mask, boolean surface,
                            boolean random, String profile, String noise) {

    public static final List<String> PRESETS = List.of("melt", "fill", "smooth", "lift", "floatclean");
    public static final List<String> PROFILES = List.of("dome", "cone", "plateau", "noise");
    /** Flatten: fill and cut, fill only, or cut only. */
    public static final List<String> FLATTEN_MODES = List.of("both", "fill", "cut");

    /** The shapes this brush knows; the first is the default. */
    public static List<String> profilesFor(BrushType type) {
        return type == BrushType.FLATTEN ? FLATTEN_MODES : PROFILES;
    }

    public static BrushSettings defaults(BrushType type, String pattern) {
        return new BrushSettings(type, 3,
                switch (type) {
                    case OVERLAY, RING -> 1;
                    case SPIKE -> 8;
                    case UNDERLAY, SNOWCONE -> 2;
                    case EXTRUDE -> 4;
                    case IVY -> 8;
                    case STALACTITE -> 6;
                    case CRACKS -> 1;
                    default -> 3;
                },
                2,
                switch (type) {
                    case SPLATTER -> 25;
                    case SCATTER -> 15;
                    case IVY -> 35;
                    case STALACTITE -> 20;
                    default -> 100;
                },
                switch (type) {
                    case PAINT, SMOOTH, BOULDER -> 40;
                    case BLOB -> 50;
                    default -> 0;
                },
                switch (type) {
                    case SPLATTER, CRACKS -> 3;
                    case SMOOTH -> 2;
                    default -> 1;
                },
                "melt", pattern == null ? defaultPattern(type) : pattern, "", false,
                type == BrushType.STAMP, profilesFor(type).get(0), "fractal");
    }

    private static String defaultPattern(BrushType type) {
        return switch (type) {
            case RAISE -> "";
            case SHELL, CRACKS -> "air";
            case IVY -> "vine";
            case STALACTITE -> "pointed_dripstone";
            case SCREE -> "gravel,cobblestone,andesite,tuff";
            default -> "stone";
        };
    }

    public BrushSettings clamp(int maxSize) {
        return new BrushSettings(type, bound(size, 0, maxSize), bound(height, 1, 64), bound(intensity, 1, 16),
                bound(chance, 1, 100), bound(falloff, 0, 100), bound(iterations, 1, 10),
                PRESETS.contains(preset) ? preset : "melt", pattern == null ? "" : pattern.trim(),
                mask == null ? "" : mask.trim(), surface, random,
                profilesFor(type).contains(profile) ? profile : profilesFor(type).get(0),
                com.stackmc.trowel.geom.Noise.Kind.parse(noise) == null ? "fractal" : noise);
    }

    /**
     * The same brush, of another type. Only the settings both types share, with the same meaning
     * and the same default, are kept; the others take the new type's default. Without this, the
     * falloff of a paint or the chance of a splatter punched holes in a ball.
     */
    public BrushSettings withType(BrushType next) {
        BrushSettings base = defaults(next, null);
        BrushSettings mine = defaults(type, null);
        boolean sameHeight = shared(next, BrushType.Setting.HEIGHT)
                && java.util.Objects.equals(type.getHeightLabel(), next.getHeightLabel());
        boolean sameChance = shared(next, BrushType.Setting.CHANCE) && mine.chance == base.chance;
        boolean sameFalloff = shared(next, BrushType.Setting.FALLOFF) && mine.falloff == base.falloff
                && type.label(BrushType.Setting.FALLOFF).equals(next.label(BrushType.Setting.FALLOFF));
        boolean keepPattern = type.uses(BrushType.Setting.PATTERN) && pattern != null && !pattern.isBlank();
        return new BrushSettings(next,
                shared(next, BrushType.Setting.SIZE) ? size : base.size,
                sameHeight ? height : base.height,
                shared(next, BrushType.Setting.INTENSITY) ? intensity : base.intensity,
                sameChance ? chance : base.chance,
                sameFalloff ? falloff : base.falloff,
                shared(next, BrushType.Setting.ITERATIONS) ? iterations : base.iterations,
                shared(next, BrushType.Setting.PRESET) ? preset : base.preset,
                keepPattern ? pattern : base.pattern,
                shared(next, BrushType.Setting.MASK) ? mask : base.mask,
                shared(next, BrushType.Setting.SURFACE) ? surface : base.surface,
                shared(next, BrushType.Setting.RANDOM) ? random : base.random,
                profilesFor(next).contains(profile) ? profile : base.profile,
                shared(next, BrushType.Setting.NOISE) ? noise : base.noise);
    }

    private boolean shared(BrushType next, BrushType.Setting setting) {
        return type.uses(setting) && next.uses(setting);
    }

    public BrushSettings withPattern(String next) {
        return new BrushSettings(type, size, height, intensity, chance, falloff, iterations, preset, next, mask,
                surface, random, profile, noise);
    }

    public BrushSettings withMask(String next) {
        return new BrushSettings(type, size, height, intensity, chance, falloff, iterations, preset, pattern, next,
                surface, random, profile, noise);
    }

    public BrushSettings withProfile(String next) {
        return new BrushSettings(type, size, height, intensity, chance, falloff, iterations, preset, pattern, mask,
                surface, random, next, noise);
    }

    public BrushSettings withNoise(String next) {
        return new BrushSettings(type, size, height, intensity, chance, falloff, iterations, preset, pattern, mask,
                surface, random, profile, next);
    }

    public BrushSettings withSize(int next) {
        return new BrushSettings(type, next, height, intensity, chance, falloff, iterations, preset, pattern, mask,
                surface, random, profile, noise);
    }

    /**
     * The settings as this brush type sees them: those it does not use are neutral.
     *
     * <p>A brush changes type keeping its settings: a paint with 40% falloff turned into
     * "Flatten" kept its 40%, invisible in its window, and left holes even at 100% chance.</p>
     */
    public BrushSettings effective() {
        return new BrushSettings(type, size, height, intensity,
                type.uses(BrushType.Setting.CHANCE) ? chance : 100,
                type.uses(BrushType.Setting.FALLOFF) ? falloff : 0,
                iterations, preset,
                type.uses(BrushType.Setting.PATTERN) ? pattern : "",
                type.uses(BrushType.Setting.MASK) ? mask : "",
                type.uses(BrushType.Setting.SURFACE) && surface,
                type.uses(BrushType.Setting.RANDOM) && random,
                profile, noise);
    }

    /** {@code key=value;...}, the pattern last: it may contain {@code =}. */
    public String encode() {
        return "type=" + type.id() + ";size=" + size + ";height=" + height + ";intensity=" + intensity
                + ";chance=" + chance + ";falloff=" + falloff + ";iterations=" + iterations + ";preset=" + preset
                + ";surface=" + surface + ";random=" + random + ";profile=" + profile + ";noise=" + noise
                + ";mask=" + mask + ";pattern=" + pattern;
    }

    public static BrushSettings decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                values.put(part.substring(0, equals), part.substring(equals + 1));
            }
        }
        BrushType type = BrushType.parse(values.get("type"));
        if (type == null) {
            return null;
        }
        BrushSettings base = defaults(type, values.getOrDefault("pattern", "stone"));
        return new BrushSettings(type,
                number(values.get("size"), base.size()),
                number(values.get("height"), base.height()),
                number(values.get("intensity"), base.intensity()),
                number(values.get("chance"), base.chance()),
                number(values.get("falloff"), base.falloff()),
                number(values.get("iterations"), base.iterations()),
                values.getOrDefault("preset", base.preset()),
                values.getOrDefault("pattern", base.pattern()),
                values.getOrDefault("mask", ""),
                Boolean.parseBoolean(values.getOrDefault("surface", "false")),
                Boolean.parseBoolean(values.getOrDefault("random", String.valueOf(base.random()))),
                values.getOrDefault("profile", base.profile()),
                values.getOrDefault("noise", base.noise()));
    }

    private static int number(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int bound(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
