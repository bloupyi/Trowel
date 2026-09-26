package com.stackmc.trowel.spline;

import com.stackmc.trowel.expr.Expression;
import com.stackmc.trowel.geom.Noise;
import com.stackmc.trowel.geom.NoiseSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The sections of a spline: what you see when cutting it, and what repeats along the path.
 *
 * <p>A section gets a point in its own frame: {@code u} and {@code v} from -1 to 1 across the
 * width (1 = the radius), {@code w} along the path counted in radii, {@code t} from 0 to 1 from
 * start to end. It returns a value: 0 for nothing, otherwise within ]0, 1], which picks the block
 * in the palette for value sections (noise, expression) and is 1 elsewhere.</p>
 *
 * <p>Settings are written in parentheses, like in ezEdits: {@code star(S:6,D:0.3)},
 * {@code chain(M:99,N:99,E:0.6)}, with the letter or the long name.</p>
 */
public final class Sections {

    /** A section ready to evaluate. */
    public interface Section {
        double eval(double u, double v, double w, double t);

        /** How far the shape overflows the radius: 1 for a circle, square root of 2 for a square. */
        default double reach() {
            return 1;
        }

        /** The value picks the block in a palette (noise, expression). */
        default boolean valued() {
            return false;
        }
    }

    public record Param(String key, String name, double value, String help) {
    }

    @FunctionalInterface
    private interface Factory {
        Section build(Map<String, Double> p);
    }

    public record Def(String id, String category, String help, List<Param> params, Factory factory,
                      List<String> aliases) {
    }

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();
    private static final double TAU = Math.PI * 2;
    private static final double SQRT2 = Math.sqrt(2);

    static {
        // ------------------------------------------------------------ 2D
        def("circle", "2d", "A round tube.", List.of("ci", "basic", "tube", "cylinder"), p -> (u, v, w, t) -> u * u + v * v <= 1 ? 1 : 0);
        def("square", "2d", "A square tube.", List.of("sq", "box"), p -> sec((u, v, w, t) -> Math.max(Math.abs(u), Math.abs(v)) <= 1 ? 1 : 0, SQRT2));
        def("diamond", "2d", "A diamond.", List.of("di"), p -> (u, v, w, t) -> Math.abs(u) + Math.abs(v) <= 1 ? 1 : 0);
        def("rounded", "2d", "A square with rounded corners.", List.of("rs", "roundedsquare"), p -> {
            double rr = clamp(p.get("R"), 0.01, 1);
            return sec((u, v, w, t) -> {
                double qx = Math.abs(u) - (1 - rr);
                double qy = Math.abs(v) - (1 - rr);
                double ox = Math.max(qx, 0);
                double oy = Math.max(qy, 0);
                return Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - rr <= 0 ? 1 : 0;
            }, SQRT2);
        }, param("R", "roundness", 0.4, "corner radius, 0 to 1"));
        def("supercircle", "2d", "Between the star (E<1), the diamond (1), the circle (2) and the square (large).", List.of("sc"), p -> {
            double e = Math.max(0.1, p.get("E"));
            return sec((u, v, w, t) -> Math.pow(Math.abs(u), e) + Math.pow(Math.abs(v), e) <= 1 ? 1 : 0, e > 2 ? SQRT2 : 1);
        }, param("E", "exponent", 2, "shape"));
        def("circles", "2d", "Circles in a crown: a rope with -t 90.", List.of("cc", "circlescircle", "rope"), p -> {
            int count = (int) clamp(p.get("C"), 1, 12);
            boolean filled = p.get("F") > 0;
            double sin = Math.sin(Math.PI / count);
            double rc = count == 1 ? 1 : sin / (1 + sin);
            double ring = count == 1 ? 0 : 1 - rc;
            double[][] centers = ring(count, ring);
            return (u, v, w, t) -> {
                if (filled && u * u + v * v <= ring * ring) {
                    return 1;
                }
                for (double[] c : centers) {
                    double dx = u - c[0];
                    double dy = v - c[1];
                    if (dx * dx + dy * dy <= rc * rc) {
                        return 1;
                    }
                }
                return 0;
            };
        }, param("C", "count", 3, "number of circles, 1 to 12"), param("F", "filled", 0, "1 to fill the center"));
        def("strands", "2d", "Strands side by side, of adjustable size.", List.of("sr"), p -> {
            int count = (int) clamp(p.get("C"), 1, 12);
            double size = clamp(p.get("S"), 0.02, 1);
            double sin = Math.sin(Math.PI / count);
            double max = count == 1 ? 1 : sin / (1 + sin);
            double rs = max * size;
            double[][] centers = ring(count, count == 1 ? 0 : 1 - max);
            return (u, v, w, t) -> {
                for (double[] c : centers) {
                    double dx = u - c[0];
                    double dy = v - c[1];
                    if (dx * dx + dy * dy <= rs * rs) {
                        return 1;
                    }
                }
                return 0;
            };
        }, param("C", "count", 2, "number of strands"), param("S", "size", 0.5, "size of each strand, 0 to 1"));
        def("pipe", "2d", "A hollow pipe.", List.of("pi", "ring"), p -> {
            double inner = clamp(p.get("I"), 0, 0.99);
            return (u, v, w, t) -> {
                double r2 = u * u + v * v;
                return r2 <= 1 && r2 >= inner * inner ? 1 : 0;
            };
        }, param("I", "inner", 0.8, "size of the hole, 0 to 1"));
        def("halfpipe", "2d", "A half pipe open upwards: gutter, slide.", List.of("hp", "channel"), p -> {
            double inner = clamp(p.get("I"), 0, 0.99);
            return (u, v, w, t) -> {
                double r2 = u * u + v * v;
                return r2 <= 1 && r2 >= inner * inner && v <= 0.05 ? 1 : 0;
            };
        }, param("I", "inner", 0.75, "size of the hollow, 0 to 1"));
        def("polygon", "2d", "A regular polygon.", List.of("po", "poly"), p -> polygon((int) clamp(p.get("S"), 3, 64)),
                param("S", "sides", 5, "number of sides"));
        ALIASES.put("triangle", "polygon");
        def("rectangle", "2d", "A rectangle placed in the section (-1 to 1).", List.of("re", "rect"), p -> {
            double x1 = Math.min(p.get("X1"), p.get("X2"));
            double x2 = Math.max(p.get("X1"), p.get("X2"));
            double y1 = Math.min(p.get("Y1"), p.get("Y2"));
            double y2 = Math.max(p.get("Y1"), p.get("Y2"));
            return sec((u, v, w, t) -> u >= x1 && u <= x2 && v >= y1 && v <= y2 ? 1 : 0, SQRT2);
        }, param("X1", "x1", -1, "corner"), param("Y1", "y1", -1, "corner"), param("X2", "x2", 1, "opposite corner"),
                param("Y2", "y2", 1, "opposite corner"));
        def("star", "2d", "A star.", List.of("st"), p -> {
            int sides = (int) clamp(p.get("S"), 3, 32);
            double inner = clamp(1 - p.get("D"), 0.02, 1);
            double beta = TAU / sides;
            double bx = inner * Math.cos(beta / 2) - 1;
            double by = inner * Math.sin(beta / 2);
            double nx = by;
            double ny = -bx;
            double na = nx;
            return (u, v, w, t) -> {
                double r = Math.sqrt(u * u + v * v);
                double phi = mod(Math.atan2(v, u) - Math.PI / 2, beta);
                double psi = Math.min(phi, beta - phi);
                double dot = nx * Math.cos(psi) + ny * Math.sin(psi);
                double boundary = dot <= 0 ? 1 : na / dot;
                return r <= boundary ? 1 : 0;
            };
        }, param("S", "sides", 5, "points"), param("D", "depth", 0.5, "depth of the notches, 0 to 1"));
        def("flower", "2d", "A flower with petals.", List.of("fl"), p -> {
            double count = clamp(p.get("C"), 1, 32);
            double depth = clamp(p.get("D"), 0, 1);
            return (u, v, w, t) -> {
                double r = Math.sqrt(u * u + v * v);
                double a = Math.atan2(v, u) - Math.PI / 2;
                return r <= 1 - depth + depth * Math.abs(Math.cos(count * a / 2)) ? 1 : 0;
            };
        }, param("C", "count", 5, "petals"), param("D", "depth", 0.5, "depth between the petals"));
        def("cross", "2d", "A cross.", List.of("plus"), p -> {
            double width = clamp(p.get("W"), 0.02, 1);
            return sec((u, v, w, t) -> (Math.abs(u) <= width && Math.abs(v) <= 1) || (Math.abs(v) <= width && Math.abs(u) <= 1)
                    ? 1 : 0, 1);
        }, param("W", "width", 0.34, "half width of the arms"));
        def("crescent", "2d", "A crescent.", List.of("moon"), p -> {
            double offset = p.get("O");
            double cut = p.get("R");
            return (u, v, w, t) -> u * u + v * v <= 1 && (u - offset) * (u - offset) + v * v >= cut * cut ? 1 : 0;
        }, param("O", "offset", 0.45, "offset of the carving circle"), param("R", "radius", 0.85, "radius of the carving circle"));
        def("gear", "2d", "A gear.", List.of("cog"), p -> {
            double teeth = clamp(p.get("T"), 3, 64);
            double depth = clamp(p.get("D"), 0, 0.9);
            double hole = clamp(p.get("I"), 0, 0.9);
            return (u, v, w, t) -> {
                double r = Math.sqrt(u * u + v * v);
                double a = Math.atan2(v, u);
                return r >= hole && r <= (Math.sin(teeth * a) < 0 ? 1 - depth : 1) ? 1 : 0;
            };
        }, param("T", "teeth", 10, "teeth"), param("D", "depth", 0.2, "height of the teeth"), param("I", "inner", 0, "central hole"));
        def("heart", "2d", "A heart.", List.of("he"), p -> sec((u, v, w, t) -> {
            double x = u * 1.25;
            double y = v * 1.25 + 0.2;
            double a = x * x + y * y - 1;
            return a * a * a - x * x * y * y * y <= 0 ? 1 : 0;
        }, 1.2));
        def("ellipse", "2d", "An ellipse.", List.of("el", "oval"), p -> {
            double ax = Math.max(0.05, p.get("X"));
            double ay = Math.max(0.05, p.get("Y"));
            return sec((u, v, w, t) -> (u / ax) * (u / ax) + (v / ay) * (v / ay) <= 1 ? 1 : 0, Math.max(ax, ay));
        }, param("X", "width", 1, "half width"), param("Y", "height", 0.5, "half height"));

        def("roof", "2d", "A gable roof (Arceon): place it with -n horizontal.", List.of("gable"), p -> {
            double height = Math.max(0.05, p.get("H"));
            double down = Math.max(0, p.get("D"));
            double noise = Math.max(0, p.get("N"));
            return sec((u, v, w, t) -> {
                double top = height * (1 - Math.abs(u));
                if (noise > 0) {
                    top += noise * 0.25 * Noise.gradient(u * 3.1, w * 1.7, 5.3);
                }
                return Math.abs(u) <= 1 && v >= -down && v <= top ? 1 : 0;
            }, Math.sqrt(1 + Math.max(height, down) * Math.max(height, down)));
        }, param("H", "height", 0.8, "ridge height, in half widths"), param("D", "down", 0,
                "goes below the base, in half widths"), param("N", "noise", 0, "irregularity"));
        def("road", "2d", "A flat road (Arceon): place it with -n horizontal.", List.of("path", "slab"), p -> {
            double thick = Math.max(0.02, p.get("T"));
            double noise = Math.max(0, p.get("N"));
            return sec((u, v, w, t) -> {
                double edge = 1 - noise * 0.3 * (0.5 + 0.5 * Noise.gradient(w * 2.3, u > 0 ? 3.1 : 7.9, 1.3));
                return Math.abs(u) <= edge && v <= 0 && v >= -thick ? 1 : 0;
            }, Math.sqrt(1 + thick * thick));
        }, param("T", "thickness", 0.35, "thickness, in half widths"), param("N", "noise", 0, "irregular edges"));

        // ------------------------------------------------------------ 3D
        def("beads", "3d", "Threaded beads.", List.of("be"), p -> {
            double period = 2 * (1 + Math.max(0, p.get("G")));
            return (u, v, w, t) -> {
                double c = mod(w, period) - period / 2;
                return u * u + v * v + c * c <= 1 ? 1 : 0;
            };
        }, param("G", "gap", 0, "gap between the beads"));
        def("cubes", "3d", "Threaded cubes.", List.of("cu"), p -> {
            double period = 2 * (1 + Math.max(0, p.get("G")));
            return sec((u, v, w, t) -> {
                double c = mod(w, period) - period / 2;
                return Math.abs(u) <= 1 && Math.abs(v) <= 1 && Math.abs(c) <= 1 ? 1 : 0;
            }, SQRT2);
        }, param("G", "gap", 0.5, "gap between the cubes"));
        def("braids", "3d", "A three strand braid.", List.of("br", "braid"), p -> {
            double thick = clamp(p.get("T"), 0.05, 1);
            double rs = 0.2 + 0.25 * thick;
            return (u, v, w, t) -> {
                double phase = w * Math.PI / 2;
                for (int k = 0; k < 3; k++) {
                    double ph = phase + TAU * k / 3;
                    double cx = (1 - rs) * Math.sin(ph);
                    double cy = 0.3 * Math.sin(2 * ph);
                    double dx = u - cx;
                    double dy = v - cy;
                    if (dx * dx + dy * dy <= rs * rs) {
                        return 1;
                    }
                }
                return 0;
            };
        }, param("T", "thickness", 0.5, "thickness of the strands"));
        def("chain", "3d", "A chain with crossed links.", List.of("ch", "chainlink"), p -> {
            double ext = Math.max(0, p.get("E"));
            double minor = clamp(0.25 * p.get("T"), 0.03, 0.9);
            double gap = p.get("G");
            double major = Math.max(0.2, p.get("M"));
            double cross = Math.max(0.2, p.get("N"));
            int place = (int) Math.round(p.get("P"));
            double a = 1 + ext;
            double step = Math.max(0.2, 2 * a - 3 * minor + gap);
            return sec((u, v, w, t) -> {
                long k0 = Math.round(w / step);
                for (long k = k0 - 1; k <= k0 + 1; k++) {
                    boolean odd = (k & 1) != 0;
                    if ((place == 1 && odd) || (place == 2 && !odd)) {
                        continue;
                    }
                    double c = w - k * step;
                    double lateral = odd ? v : u;
                    double other = odd ? u : v;
                    double rho = Math.pow(Math.pow(Math.abs(c / a), major) + Math.pow(Math.abs(lateral), major), 1 / major);
                    double dr = (rho - (1 - minor));
                    double d = Math.pow(Math.abs(dr) / minor, cross) + Math.pow(Math.abs(other) / minor, cross);
                    if (d <= 1) {
                        return 1;
                    }
                }
                return 0;
            }, 1.05);
        }, param("E", "extrusion", 0.2, "stretch of each link"), param("T", "thickness", 1, "thickness of the wire"),
                param("G", "gap", 0, "gap between links (negative: tight)"), param("M", "majorexponent", 3,
                        "shape of the link (2 oval, large: rectangle)"), param("N", "minorexponent", 3,
                        "shape of the wire (2 round, large: square)"), param("P", "place", 0, "0 both, 1 the first, 2 the second"));
        def("fishnet", "3d", "A net around a tube.", List.of("fi", "net"), p -> {
            double spacing = TAU / Math.max(2, Math.round(TAU / Math.max(0.2, p.get("S"))));
            double depth = clamp(p.get("D"), 0.02, 1);
            double width = Math.max(0.02, p.get("W"));
            return (u, v, w, t) -> {
                double r = Math.sqrt(u * u + v * v);
                if (r > 1 || r < 1 - depth) {
                    return 0;
                }
                double a = Math.atan2(v, u);
                double d1 = Math.abs(frac((a + w) / spacing) - 0.5) * spacing;
                double d2 = Math.abs(frac((a - w) / spacing) - 0.5) * spacing;
                return Math.max(d1, d2) >= spacing / 2 - width / 2 ? 1 : 0;
            };
        }, param("S", "spacing", 1, "spacing of the wires"), param("D", "depth", 0.2, "thickness of the net"), param("W", "width", 0.2,
                "width of the wires"));
        def("honeycomb", "3d", "Cells carved into the surface.", List.of("ho", "hex"), p -> {
            double size = Math.max(0.05, p.get("C")) * Math.PI;
            double wire = Math.max(0.005, p.get("W")) * Math.PI;
            double depth = clamp(p.get("D"), 0, 1);
            return (u, v, w, t) -> {
                double r = Math.sqrt(u * u + v * v);
                if (r > 1) {
                    return 0;
                }
                if (r < 1 - depth) {
                    return 1;
                }
                return hexEdge(Math.atan2(v, u), w, size) <= wire ? 1 : 0;
            };
        }, param("C", "cellsize", 0.25, "size of the cells"), param("W", "wirewidth", 0.06, "thickness of the walls"),
                param("D", "depth", 0.2, "depth, 0 to 1"));
        def("oscillate", "3d", "A ribbed tube.", List.of("os", "ridges"), p -> {
            double depth = clamp(p.get("D"), 0, 0.95);
            double interval = Math.max(0.05, p.get("I"));
            return (u, v, w, t) -> Math.sqrt(u * u + v * v) <= 1 - depth * (0.5 - 0.5 * Math.cos(TAU * w / interval)) ? 1 : 0;
        }, param("D", "depth", 0.2, "depth of the grooves"), param("I", "interval", 0.5, "spacing of the ribs"));
        def("rings", "3d", "Rings one after the other.", List.of("ri", "torus", "tori"), p -> {
            double ext = Math.max(0, p.get("E"));
            double thick = clamp(p.get("T"), 0.02, 1);
            double gap = p.get("G");
            double major = Math.max(0.2, p.get("M"));
            double minor = Math.max(0.2, p.get("N"));
            double step = Math.max(0.1, 2 * (thick + ext) * (1 + gap));
            return sec((u, v, w, t) -> {
                double c = Math.abs(w - Math.round(w / step) * step);
                double ce = Math.max(0, c - ext);
                double rho = Math.pow(Math.pow(Math.abs(u), major) + Math.pow(Math.abs(v), major), 1 / major);
                double d = Math.pow(Math.abs(rho - (1 - thick)) / thick, minor) + Math.pow(ce / thick, minor);
                return d <= 1 ? 1 : 0;
            }, major > 2 ? SQRT2 : 1);
        }, param("E", "extrusion", 0.2, "stretch along the path"), param("T", "thickness", 0.15, "thickness, 1: ball"),
                param("G", "gap", 0, "relative gap"), param("M", "majorexponent", 2, "shape of the ring"),
                param("N", "minorexponent", 2, "shape of the wire"));
        def("scales", "3d", "Overlapping scales.", List.of("sc3", "scale", "fishscales"), p -> {
            double columns = Math.max(3, Math.round(p.get("C")));
            double cw = TAU / columns;
            double spread = Math.max(0.3, p.get("H"));
            double rh = cw * Math.max(0.2, p.get("V")) * 0.5;
            double m = Math.max(0.5, p.get("M"));
            double depth = Math.max(0, p.get("D")) * 0.2;
            return (u, v, w, t) -> {
                double r = Math.sqrt(u * u + v * v);
                if (r > 1) {
                    return 0;
                }
                double a = Math.atan2(v, u);
                double best = 0;
                long row = (long) Math.floor(w / rh);
                for (long j = row - 1; j <= row + 1; j++) {
                    double offset = (j & 1) == 0 ? 0 : cw / 2;
                    double column = Math.round((a - offset) / cw);
                    for (double i = column - 1; i <= column + 1; i++) {
                        double cx = i * cw + offset;
                        double cy = (j + 1) * rh;
                        double x = angleDiff(a, cx) / (cw * spread * 0.5);
                        double y = (cy - w) / (rh * 1.6);
                        if (y < -0.05) {
                            continue;
                        }
                        double s = Math.pow(Math.abs(x), m) + Math.pow(Math.abs(y), m);
                        best = Math.max(best, 1 - s);
                    }
                }
                return r <= 1 - depth + depth * Math.pow(Math.max(0, best), 0.35) ? 1 : 0;
            };
        }, param("C", "columns", 8, "scales per turn"), param("H", "horizontaloffset", 1.05, "width"),
                param("V", "verticaloffset", 1.2, "length"), param("M", "majorexponent", 1.4, "shape"),
                param("D", "depthmultiplier", 1, "relief"));
        def("noodles", "3d", "Tangled noodles.", List.of("no", "spaghetti"), p -> {
            int amount = (int) clamp(p.get("A"), 1, 64);
            double density = clamp(p.get("D"), 0.05, 1);
            double frequency = Math.max(0.01, p.get("F"));
            double tangle = Math.max(0, p.get("T"));
            double width = Math.max(0.05, p.get("W"));
            double seed = p.get("S");
            double rs = Math.sqrt(density / amount) * 0.95;
            double[][] base = new double[amount][2];
            double golden = Math.PI * (3 - Math.sqrt(5));
            for (int k = 0; k < amount; k++) {
                double rr = Math.sqrt((k + 0.5) / amount) * (1 - rs);
                base[k][0] = rr * Math.cos(k * golden);
                base[k][1] = rr * Math.sin(k * golden);
            }
            return (u, v, w, t) -> {
                for (int k = 0; k < amount; k++) {
                    double cx = base[k][0] + tangle * 0.12 * Noise.gradient(k * 13.1 + seed, w * frequency, 3.7);
                    double cy = base[k][1] + tangle * 0.12 * Noise.gradient(k * 7.7 + 100 + seed, w * frequency, 9.1);
                    double len = Math.sqrt(cx * cx + cy * cy);
                    if (len > 1 - rs) {
                        cx *= (1 - rs) / len;
                        cy *= (1 - rs) / len;
                    }
                    double dx = u - cx;
                    double dy = v - cy;
                    if (dx * dx + dy * dy <= rs * rs * width * width) {
                        return 1;
                    }
                }
                return 0;
            };
        }, param("A", "amount", 12, "number of noodles"), param("D", "density", 0.7, "share of the section filled"),
                param("F", "frequency", 0.5, "speed of the waves"), param("T", "tangle", 3, "tangle"),
                param("W", "width", 0.8, "relative thickness"), param("S", "seed", 0, "seed"));
        def("spring", "3d", "A spring, a spiral around the path.", List.of("coil", "spiral"), p -> {
            double turns = p.get("K");
            double thick = clamp(p.get("T"), 0.02, 1);
            double radius = clamp(p.get("R"), 0, 1);
            double slope = Math.sqrt(1 + Math.pow(TAU * turns * radius, 2));
            return sec((u, v, w, t) -> {
                double r = Math.sqrt(u * u + v * v);
                double diff = angleDiff(Math.atan2(v, u), TAU * turns * w);
                double line = radius * diff / slope;
                double dr = r - radius;
                return dr * dr + line * line <= thick * thick ? 1 : 0;
            }, radius + thick);
        }, param("K", "turns", 0.35, "turns per path radius"), param("T", "thickness", 0.28, "thickness of the wire"),
                param("R", "radius", 0.72, "radius of the spiral"));
        def("helix", "3d", "Blades twisted around an axis.", List.of("blades", "twist", "drill"), p -> {
            int blades = (int) clamp(p.get("B"), 1, 16);
            double width = clamp(p.get("W"), 0.01, 1) * Math.PI / blades;
            double turns = p.get("K");
            double core = clamp(p.get("C"), 0, 1);
            double inner = clamp(p.get("I"), 0, 1);
            return (u, v, w, t) -> {
                double r2 = u * u + v * v;
                if (r2 > 1) {
                    return 0;
                }
                if (r2 <= core * core) {
                    return 1;
                }
                if (r2 < inner * inner) {
                    return 0;
                }
                double a = Math.atan2(v, u);
                for (int k = 0; k < blades; k++) {
                    if (Math.abs(angleDiff(a, TAU * k / blades + TAU * turns * w)) <= width) {
                        return 1;
                    }
                }
                return 0;
            };
        }, param("B", "blades", 3, "number of blades"), param("W", "width", 0.35, "width of the blades, 0 to 1"),
                param("K", "turns", 0.25, "turns per path radius"), param("C", "core", 0.22, "radius of the solid axis"),
                param("I", "inner", 0, "blades start from this radius"));
        def("stones", "3d", "Loose stones, in cells separated by void.", List.of("rocks", "cells", "shards"), p -> {
            double size = Math.max(0.1, p.get("S"));
            double gap = Math.max(0, p.get("G"));
            double keep = clamp(p.get("K"), 0, 100) / 100;
            double jitter = clamp(p.get("J"), 0, 1);
            return (u, v, w, t) -> {
                if (u * u + v * v > 1) {
                    return 0;
                }
                double[] cell = voronoi(u / size, v / size, w / size, jitter);
                return (cell[1] - cell[0]) * size >= gap && cell[2] < keep ? 1 : 0;
            };
        }, param("S", "size", 0.7, "size of the stones"), param("G", "gap", 0.12, "void between them"),
                param("K", "keep", 100, "share kept, in %"), param("J", "jitter", 1, "disorder of the cells"));
        def("bumps", "3d", "A surface with rounded bumps.", List.of("bu", "blobs", "knobs"), p -> {
            double size = Math.max(0.1, p.get("S"));
            double height = clamp(p.get("D"), 0, 0.9);
            return (u, v, w, t) -> {
                double r = Math.sqrt(u * u + v * v);
                if (r > 1) {
                    return 0;
                }
                double[] cell = voronoi2(Math.atan2(v, u) / size, w / size);
                double bump = Math.sqrt(Math.max(0, 1 - cell[0] * cell[0] * 1.6));
                return r <= 1 - height + height * bump ? 1 : 0;
            };
        }, param("S", "size", 0.45, "size of the bumps"), param("D", "depth", 0.3, "height of the bumps"));
        def("thorns", "3d", "A tube bristling with thorns.", List.of("spikes", "thorn"), p -> {
            double spacing = Math.max(0.1, p.get("S"));
            double length = clamp(p.get("L"), 0.05, 0.95);
            double base = Math.max(0.05, p.get("B"));
            return (u, v, w, t) -> {
                double r = Math.sqrt(u * u + v * v);
                if (r > 1) {
                    return 0;
                }
                double[] cell = voronoi2(Math.atan2(v, u) / spacing, w / spacing);
                double d = cell[0] * spacing;
                return r <= 1 - length + length * Math.max(0, 1 - d / base) ? 1 : 0;
            };
        }, param("S", "spacing", 0.8, "spacing of the thorns"), param("L", "length", 0.6, "length of the thorns"),
                param("B", "base", 0.3, "width at the base"));
        def("dna", "3d", "A double helix and its rungs.", List.of("doublehelix"), p -> {
            double turns = p.get("K");
            double thick = clamp(p.get("T"), 0.02, 1);
            double radius = clamp(p.get("R"), 0, 1);
            double rung = Math.max(0.1, p.get("P"));
            double bar = Math.max(0.01, p.get("H"));
            double slope = Math.sqrt(1 + Math.pow(TAU * turns * radius, 2));
            return sec((u, v, w, t) -> {
                double r = Math.sqrt(u * u + v * v);
                double a = Math.atan2(v, u);
                double dr = r - radius;
                for (int k = 0; k < 2; k++) {
                    double line = radius * angleDiff(a, TAU * turns * w + Math.PI * k) / slope;
                    if (dr * dr + line * line <= thick * thick) {
                        return 1;
                    }
                }
                double wr = Math.round(w / rung) * rung;
                if (Math.abs(w - wr) <= bar * 1.5) {
                    double phi = TAU * turns * wr;
                    double along = u * Math.cos(phi) + v * Math.sin(phi);
                    double across = -u * Math.sin(phi) + v * Math.cos(phi);
                    return Math.abs(along) <= radius && Math.abs(across) <= bar * 1.5 ? 1 : 0;
                }
                return 0;
            }, radius + thick);
        }, param("K", "turns", 0.2, "turns per radius"), param("T", "thickness", 0.2, "thickness of the strands"),
                param("R", "radius", 0.75, "radius of the helices"), param("P", "rungs", 0.7, "spacing of the rungs"),
                param("H", "bar", 0.08, "thickness of the rungs"));
        def("lattice", "3d", "A lattice of bars.", List.of("grid", "truss"), p -> {
            double spacing = Math.max(0.1, p.get("S"));
            double half = Math.max(0.01, p.get("W")) / 2;
            return (u, v, w, t) -> {
                if (u * u + v * v > 1) {
                    return 0;
                }
                int near = 0;
                if (Math.abs(frac(u / spacing + 0.5) - 0.5) * spacing <= half) {
                    near++;
                }
                if (Math.abs(frac(v / spacing + 0.5) - 0.5) * spacing <= half) {
                    near++;
                }
                if (Math.abs(frac(w / spacing + 0.5) - 0.5) * spacing <= half) {
                    near++;
                }
                return near >= 2 ? 1 : 0;
            };
        }, param("S", "spacing", 0.5, "spacing of the bars"), param("W", "width", 0.14, "thickness of the bars"));
        def("bamboo", "3d", "A bamboo: segments and their nodes.", List.of("ba", "segments"), p -> {
            double interval = Math.max(0.2, p.get("I"));
            double node = clamp(p.get("D"), 0, 0.5);
            return (u, v, w, t) -> {
                double c = mod(w, interval);
                double d = Math.min(c, interval - c);
                double rb = 1 - node + node * Math.exp(-d * d / 0.01) - (d > 0.08 && d < 0.2 ? node * 0.5 : 0);
                return Math.sqrt(u * u + v * v) <= rb ? 1 : 0;
            };
        }, param("I", "interval", 1.6, "length of the segments"), param("D", "depth", 0.12, "relief of the nodes"));
    }

    private Sections() {
    }

    // ----------------------------------------------------------------- parsing

    public static Map<String, Def> all() {
        return DEFS;
    }

    public static List<String> names() {
        List<String> out = new ArrayList<>(DEFS.keySet());
        out.add("noise");
        out.add("expr");
        out.add("clipboard");
        return out;
    }

    /** The bare name, without settings: {@code star} for {@code star(S:6)}; {@code null} if unknown. */
    public static String canonical(String raw) {
        String name = raw.toLowerCase(Locale.ROOT);
        int open = name.indexOf('(');
        if (open >= 0) {
            name = name.substring(0, open);
        }
        if (DEFS.containsKey(name)) {
            return name;
        }
        String alias = ALIASES.get(name);
        if (alias != null) {
            return alias;
        }
        return switch (name) {
            case "noise", "ns" -> "noise";
            case "expr", "expression", "ex", "=" -> "expr";
            case "clipboard", "structure", "clip", "schem" -> "clipboard";
            default -> null;
        };
    }

    /** A section from {@code name(setting:value,...)}. */
    public static Section parse(String raw) {
        String name = canonical(raw);
        Def def = name == null ? null : DEFS.get(name);
        if (def == null) {
            throw new IllegalArgumentException("Unknown shape: " + raw + ". Shapes: " + String.join(", ", names()));
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (Param param : def.params()) {
            values.put(param.key(), param.value());
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (raw.equalsIgnoreCase("triangle")) {
            values.put("S", 3.0);
        }
        int open = lower.indexOf('(');
        if (open >= 0) {
            if (!lower.endsWith(")")) {
                throw new IllegalArgumentException(def.id() + ": missing closing parenthesis.");
            }
            String body = raw.substring(open + 1, raw.length() - 1);
            for (String part : body.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                int colon = part.indexOf(':');
                if (colon <= 0) {
                    throw new IllegalArgumentException(def.id() + ": write setting:value, for example "
                            + example(def) + ".");
                }
                String key = part.substring(0, colon).trim();
                String value = part.substring(colon + 1).trim();
                Param param = def.params().stream().filter(candidate -> candidate.key().equalsIgnoreCase(key)
                        || candidate.name().equalsIgnoreCase(key)).findFirst().orElse(null);
                if (param == null) {
                    throw new IllegalArgumentException(def.id() + ": unknown setting '" + key + "'. Settings: "
                            + describe(def));
                }
                values.put(param.key(), number(value, def.id(), param.key()));
            }
        }
        return def.factory().build(values);
    }

    /** {@code S (sides) = 5: number of sides, ...} for the help. */
    public static String describe(Def def) {
        if (def.params().isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        for (Param p : def.params()) {
            parts.add(p.key() + " (" + p.name() + ") = " + trim(p.value()) + ": " + p.help());
        }
        return String.join("; ", parts);
    }

    public static String example(Def def) {
        if (def.params().isEmpty()) {
            return def.id();
        }
        List<String> parts = new ArrayList<>();
        for (Param p : def.params()) {
            parts.add(p.key() + ":" + trim(p.value()));
        }
        return def.id() + "(" + String.join(",", parts) + ")";
    }

    private static double number(String raw, String shape, String key) {
        String lower = raw.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "true", "yes", "both" -> {
                return lower.equals("both") ? 0 : 1;
            }
            case "false", "no" -> {
                return 0;
            }
            case "first" -> {
                return 1;
            }
            case "second" -> {
                return 2;
            }
            default -> {
            }
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(shape + ": " + key + " takes a number, not '" + raw + "'.");
        }
    }

    // ------------------------------------------------------------ advanced sections

    /**
     * The noise that carves a tube, like the ezEdits noise spline: at depth {@code depth}, the
     * noise may eat the whole radius (1) or only the skin (0.2). The value follows the depth:
     * the first block of the palette at the bottom, the last at the surface.
     *
     * <p>Without {@code custom}, the computation is the ezEdits one, so their commands give the same
     * shapes.</p>
     *
     * @param custom an expression of x, y, z (the section), n (the noise), d (the depth), r, t, and
     *               xx, yy, zz (the squares), replacing this computation; or {@code null}
     */
    public static Section noise(NoiseSpec spec, double depth, String custom) {
        double d = Math.max(0.01, depth);
        Expression expression = Expression.compile(custom != null ? custom : NOISE_DEFAULT,
                "x", "y", "z", "n", "d", "r", "t", "xx", "yy", "zz");
        ThreadLocal<Expression.Frame> frames = ThreadLocal.withInitial(expression::frame);
        return new Section() {
            @Override
            public double eval(double u, double v, double w, double t) {
                double r = Math.sqrt(u * u + v * v);
                double value = expression.run(frames.get().inputs(u, v, w, spec.sample(u, v, w), d, r, t,
                        u * u, v * v, w * w));
                return value > 0 ? Math.min(1, value) : 0;
            }

            @Override
            public double reach() {
                return 1.2;
            }

            @Override
            public boolean valued() {
                return true;
            }
        };
    }

    /** The ezEdits noise spline: the noise eats the tube down to the depth, the value follows the depth. */
    static final String NOISE_DEFAULT = "r=sqrt(x*x+y*y);t=(r-1)/d+1;f=r>1?1:(4*r*(r-1))^2;g=f*t+(1-f)*n;"
            + "p=max((r-1)/min(d,1)+1,.001);(g>t)*p";

    /**
     * An expression: x and y the section (-1 to 1), z along the path (in radii, or -1 to 1 with
     * {@code normalized}), and t, s, r, a, radius. Positive, it places a block; its value picks
     * it in the palette.
     */
    public static Section expression(String source, boolean normalized, double reach, double length) {
        Expression expression = Expression.compile(source, "x", "y", "z", "t", "s", "r", "a", "radius",
                "xx", "yy", "zz");
        ThreadLocal<Expression.Frame> frames = ThreadLocal.withInitial(expression::frame);
        return new Section() {
            @Override
            public double eval(double u, double v, double w, double t) {
                double z = normalized ? t * 2 - 1 : w;
                double r = Math.sqrt(u * u + v * v);
                double a = Math.atan2(v, u);
                double value = expression.run(frames.get().inputs(u, v, z, t, t * length, r, a < 0 ? a + TAU : a, 1,
                        u * u, v * v, z * z));
                return value > 0 ? value : 0;
            }

            @Override
            public double reach() {
                return reach;
            }

            @Override
            public boolean valued() {
                return true;
            }
        };
    }

    // ------------------------------------------------------------------- tools

    private static Param param(String key, String name, double value, String help) {
        return new Param(key, name, value, help);
    }

    private static void def(String id, String category, String help, List<String> aliases, Factory factory, Param... params) {
        DEFS.put(id, new Def(id, category, help, List.of(params), factory, aliases));
        for (String alias : aliases) {
            ALIASES.put(alias, id);
        }
    }

    private static Section sec(Section base, double reach) {
        return new Section() {
            @Override
            public double eval(double u, double v, double w, double t) {
                return base.eval(u, v, w, t);
            }

            @Override
            public double reach() {
                return reach;
            }
        };
    }

    private static Section polygon(int sides) {
        double half = Math.PI / sides;
        double apothem = Math.cos(half);
        return (u, v, w, t) -> {
            double r = Math.sqrt(u * u + v * v);
            double a = mod(Math.atan2(v, u) - Math.PI / 2, 2 * half) - half;
            return r <= apothem / Math.cos(a) ? 1 : 0;
        };
    }

    private static double[][] ring(int count, double radius) {
        double[][] out = new double[count][2];
        for (int k = 0; k < count; k++) {
            double a = Math.PI / 2 + TAU * k / count;
            out[k][0] = radius * Math.cos(a);
            out[k][1] = radius * Math.sin(a);
        }
        return out;
    }

    /** Distance of a point (angle, w) to the edge of its hexagonal cell, on the tube surface. */
    private static double hexEdge(double a, double w, double size) {
        double x = a / size;
        double y = w / size;
        double q = (Math.sqrt(3) / 3 * x - y / 3);
        double r = 2.0 / 3 * y;
        double s = -q - r;
        double rq = Math.round(q);
        double rr = Math.round(r);
        double rs = Math.round(s);
        double dq = Math.abs(rq - q);
        double dr = Math.abs(rr - r);
        double ds = Math.abs(rs - s);
        if (dq > dr && dq > ds) {
            rq = -rr - rs;
        } else if (dr > ds) {
            rr = -rq - rs;
        }
        double cx = Math.sqrt(3) * (rq + rr / 2);
        double cy = 1.5 * rr;
        double px = Math.abs(x - cx);
        double py = Math.abs(y - cy);
        double hex = Math.max(px * Math.sqrt(3) / 2 + py / 2, py);
        return (Math.sqrt(3) / 2 - hex * Math.sqrt(3) / 2) * size;
    }

    /** 3D cells: distance to the first and second center, and a value specific to the cell. */
    static double[] voronoi(double x, double y, double z, double jitter) {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        double f1 = Double.MAX_VALUE;
        double f2 = Double.MAX_VALUE;
        long owner = 0;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (int k = -1; k <= 1; k++) {
                    long h = hash(cx + i, cy + j, cz + k);
                    double dx = cx + i + 0.5 + (unit(h) - 0.5) * jitter - x;
                    double dy = cy + j + 0.5 + (unit(h >>> 21) - 0.5) * jitter - y;
                    double dz = cz + k + 0.5 + (unit(h >>> 42) - 0.5) * jitter - z;
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
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
        return new double[]{f1, f2, unit(owner * 0x9E3779B97F4A7C15L)};
    }

    static double[] voronoi2(double x, double y) {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        double f1 = Double.MAX_VALUE;
        double f2 = Double.MAX_VALUE;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                long h = hash(cx + i, cy + j, 17);
                double dx = cx + i + 0.5 + (unit(h) - 0.5) * 0.8 - x;
                double dy = cy + j + 0.5 + (unit(h >>> 21) - 0.5) * 0.8 - y;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d < f1) {
                    f2 = f1;
                    f1 = d;
                } else if (d < f2) {
                    f2 = d;
                }
            }
        }
        return new double[]{f1, f2};
    }

    private static long hash(int x, int y, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        return h ^ (h >>> 33);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double mod(double a, double b) {
        return a - b * Math.floor(a / b);
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    /** a - b brought into [-pi, pi]. */
    private static double angleDiff(double a, double b) {
        double d = mod(a - b + Math.PI, TAU) - Math.PI;
        return d;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
