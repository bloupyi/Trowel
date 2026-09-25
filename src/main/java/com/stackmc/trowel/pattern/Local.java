package com.stackmc.trowel.pattern;

/**
 * Where a block sits in the shape that places it: its place in a spline section, along its
 * path, and the value the shape gives it.
 *
 * <p>A shape fills this object before asking its pattern for a block; a pattern that knows how
 * to use it (local noise, gradient along the path, expression) then textures the shape itself
 * rather than the world: bark follows the trunk, rings follow the curve. One object per
 * computation, reused for every block: no allocation.</p>
 */
public final class Local {

    /** Section: -1 to 1 on both axes. */
    public double u;
    public double v;
    /** Along the path, in radii (like ezEdits' z). */
    public double w;
    /** Along the path, from 0 at the start to 1 at the end. */
    public double t;
    /** Along the path, in blocks. */
    public double s;
    /** Distance to the axis, from 0 at the center to 1 at the edge. */
    public double r;
    /** Angle around the axis, in radians, from 0 to 2 pi. */
    public double a;
    /** Radius of the shape at this point, in blocks. */
    public double radius = 1;
    /** Value the shape gives the block: within ]0, 1] usually, raw for an expression. */
    public double value = 1;

    public Local set(double u, double v, double w, double t, double s, double radius, double value) {
        this.u = u;
        this.v = v;
        this.w = w;
        this.t = t;
        this.s = s;
        this.radius = radius;
        this.value = value;
        this.r = Math.sqrt(u * u + v * v);
        double angle = Math.atan2(v, u);
        this.a = angle < 0 ? angle + Math.PI * 2 : angle;
        return this;
    }
}
