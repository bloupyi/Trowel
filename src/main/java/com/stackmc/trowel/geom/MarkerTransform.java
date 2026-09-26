package com.stackmc.trowel.geom;

import com.stackmc.trowel.api.ParamKind;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The settings of a marker, rotated with it.
 *
 * <p>A zone extends from its block towards positive X, Y and Z, or towards negative ones for a
 * negative size. Rotated, the block stays where it lands and {@link #spans} gives the signed sizes
 * that cover the same blocks.</p>
 */
public final class MarkerTransform {

    private MarkerTransform() {
    }

    public static Map<String, String> params(Map<String, String> params, Map<String, ParamKind> kinds, Transform t) {
        Map<String, String> out = new LinkedHashMap<>(params);
        String sizeX = null;
        String sizeZ = null;
        for (Map.Entry<String, ParamKind> entry : kinds.entrySet()) {
            String key = entry.getKey();
            String value = params.get(key);
            switch (entry.getValue()) {
                case ANGLE -> {
                    if (value != null) {
                        out.put(key, angle(value, t));
                    }
                }
                case PATH -> {
                    if (value != null) {
                        out.put(key, path(value, t));
                    }
                }
                case DIRECTION -> {
                    if (value != null) {
                        out.put(key, t.direction(value));
                    }
                }
                case SIZE_X -> sizeX = key;
                case SIZE_Z -> sizeZ = key;
                case SIZE_Y -> {
                }
            }
        }
        if (t.swapsXZ() && sizeX != null && sizeZ != null) {
            String x = params.get(sizeX);
            String z = params.get(sizeZ);
            out.remove(sizeX);
            out.remove(sizeZ);
            if (z != null) {
                out.put(sizeX, z);
            }
            if (x != null) {
                out.put(sizeZ, x);
            }
        }
        return out;
    }

    public static String angle(String value, Transform t) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("keep")) {
            return value;
        }
        if (Transform.vector(lower) != null) {
            return t.direction(lower);
        }
        try {
            long rounded = Math.round(t.yaw(Double.parseDouble(lower)));
            return String.valueOf(rounded == 180 ? -180 : rounded);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    public static String path(String raw, Transform t) {
        StringBuilder out = new StringBuilder();
        for (String point : raw.split(";")) {
            String[] parts = point.split(",");
            if (parts.length != 3) {
                return raw;
            }
            try {
                int[] turned = t.apply(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()));
                if (!out.isEmpty()) {
                    out.append(';');
                }
                out.append(turned[0]).append(',').append(turned[1]).append(',').append(turned[2]);
            } catch (NumberFormatException e) {
                return raw;
            }
        }
        return out.toString();
    }

    /** Signed sizes, once rotated, of a zone of signed sizes w, h, d anchored at (x, y, z). */
    public static int[] spans(int x, int y, int z, int w, int h, int d, Transform t) {
        int[] a = t.apply(x, y, z);
        int[] b = t.apply(x + reach(w), y + reach(h), z + reach(d));
        return new int[]{span(b[0] - a[0]), span(b[1] - a[1]), span(b[2] - a[2])};
    }

    private static int reach(int size) {
        return size > 0 ? size - 1 : size + 1;
    }

    private static int span(int delta) {
        return delta >= 0 ? delta + 1 : delta - 1;
    }
}
