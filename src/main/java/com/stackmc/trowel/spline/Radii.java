package com.stackmc.trowel.spline;

import java.util.ArrayList;
import java.util.List;

/**
 * The radius along a spline, as keyframes like ezEdits.
 *
 * <p>{@code 5}: 5 everywhere. {@code 1,12}: from 1 at the start to 12 at the end. {@code 1,12,1}:
 * 12 in the middle. {@code 1,0.2:12,1}: 12 reached at 20% of the way. Missing places are
 * spread between their neighbours; between two keyframes, the radius slides smoothly.</p>
 */
public final class Radii {

    private final double[] at;
    private final double[] radius;

    private Radii(double[] at, double[] radius) {
        this.at = at;
        this.radius = radius;
    }

    public static Radii constant(double r) {
        return new Radii(new double[]{0, 1}, new double[]{r, r});
    }

    public static Radii parse(String raw, double max) {
        String[] parts = raw.trim().split(",");
        int n = parts.length;
        Double[] positions = new Double[n];
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            String part = parts[i].trim();
            int colon = part.indexOf(':');
            try {
                if (colon >= 0) {
                    positions[i] = Double.parseDouble(part.substring(0, colon));
                    values[i] = Double.parseDouble(part.substring(colon + 1));
                } else {
                    values[i] = Double.parseDouble(part);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Radii: '" + part + "' is not a number (write 5, or 1,12,1, "
                        + "or 0:1,0.5:12,1:1).");
            }
            if (values[i] <= 0) {
                throw new IllegalArgumentException("Radii: every radius must be positive.");
            }
            if (values[i] > max) {
                throw new IllegalArgumentException("Radii: " + values[i] + " exceeds the maximum of " + max + ".");
            }
        }
        if (n == 1) {
            return constant(values[0]);
        }
        if (positions[0] == null) {
            positions[0] = 0.0;
        }
        if (positions[n - 1] == null) {
            positions[n - 1] = 1.0;
        }
        if (positions[0] != 0 || positions[n - 1] != 1) {
            throw new IllegalArgumentException("Radii: the first place is 0 and the last is 1.");
        }
        int last = 0;
        for (int i = 1; i < n; i++) {
            if (positions[i] == null) {
                continue;
            }
            for (int k = last + 1; k < i; k++) {
                positions[k] = positions[last] + (positions[i] - positions[last]) * (k - last) / (i - last);
            }
            if (positions[i] <= positions[last]) {
                throw new IllegalArgumentException("Radii: places must increase.");
            }
            last = i;
        }
        double[] at = new double[n];
        for (int i = 0; i < n; i++) {
            at[i] = positions[i];
        }
        return new Radii(at, values);
    }

    /** The radius at place {@code t} along the path, from 0 to 1. */
    public double at(double t) {
        if (t <= at[0]) {
            return radius[0];
        }
        for (int i = 1; i < at.length; i++) {
            if (t <= at[i]) {
                double f = (t - at[i - 1]) / (at[i] - at[i - 1]);
                f = f * f * (3 - 2 * f);
                return radius[i - 1] + (radius[i] - radius[i - 1]) * f;
            }
        }
        return radius[radius.length - 1];
    }

    public double max() {
        double m = 0;
        for (double r : radius) {
            m = Math.max(m, r);
        }
        return m;
    }

    public String describe() {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < at.length; i++) {
            parts.add(trim(at[i]) + ":" + trim(radius[i]));
        }
        return String.join(",", parts);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 100) / 100.0);
    }
}
