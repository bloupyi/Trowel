package com.stackmc.trowel.ui;

import com.stackmc.trowel.Chat;
import com.stackmc.trowel.Session;
import com.stackmc.trowel.Trowel;
import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.engine.ChangeSet;
import com.stackmc.trowel.engine.Clipboard;
import com.stackmc.trowel.engine.Engine;
import com.stackmc.trowel.engine.Progress;
import com.stackmc.trowel.expr.Expression;
import com.stackmc.trowel.expr.Functions;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.geom.NoiseSpec;
import com.stackmc.trowel.pattern.Local;
import com.stackmc.trowel.pattern.Mask;
import com.stackmc.trowel.pattern.Palette;
import com.stackmc.trowel.pattern.Pattern;
import com.stackmc.trowel.spline.Path;
import com.stackmc.trowel.spline.Radii;
import com.stackmc.trowel.spline.Sections;
import com.stackmc.trowel.spline.Sweep;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Trowel's advanced tools: ezEdits style splines, math expressions, deformations.
 *
 * <p>Kept apart from the basic commands so each stays readable; they register the same way,
 * with the same help and completion.</p>
 */
final class AdvancedCommands {

    static final String SPLINES = "Splines";
    static final String EXPRESSIONS = "Expressions and generation";

    private static final Set<Character> VALUE_FLAGS = Set.of('p', 'q', 'r', 's', 't', 'n', 'e', 'm', 'i', 'x');
    private static final Set<Character> BOOL_FLAGS = Set.of('h', 'c', 'z', 'o', 'a');
    private static final java.util.regex.Pattern RADII = java.util.regex.Pattern.compile("^[0-9.]+(?::[0-9.]+)?(?:,[0-9.]+(?::[0-9.]+)?)*$");

    private final Trowel trowel;
    private final Commands c;

    AdvancedCommands(Trowel trowel, Commands commands) {
        this.trowel = trowel;
        this.c = commands;
    }

    void define() {
        c.add("spline|ezspline|ezsp|sp", SPLINES, "//spline [shape] <pattern> [radii] [-t twist] [-r roll] "
                        + "[-s stretch] [-e caps] [-n orientation] [-q quality] [-p tension:bias:continuity] [-h] [-c]",
                "A shape swept along the points (//point). //spline help: shapes and settings.",
                this::spline, this::completeSpline);
        c.add("point|points|pt", SPLINES, "//point [here|undo|clear|list|sel|reverse|insert <n>]",
                "Adds a spline point on the aimed block. Also: sneak + left click with the wand.",
                this::point, c.args("word:here|undo|clear|list|sel|reverse|insert"));
        c.add("catenary|selcatenary", SPLINES, "//catenary <points> [sag] [up|down]",
                "Replaces the points with a catenary between the first and the last (or both corners).",
                this::catenary, c.args("n:5|8|12", "n:2|4|8", "word:down|up"));
        c.add("generate|gen|g", EXPRESSIONS, "//generate [-h] [-r|-c|-o] <pattern|palette> <expression>",
                "Fills the selection where the expression is positive; its value picks the block of a palette.",
                this::generate, this::completeGenerate);
        c.add("deform", EXPRESSIONS, "//deform [-r|-c|-o] <expression>",
                "Deforms the selection: the expression changes x, y, z, and each block takes the one at the computed place.",
                this::deform, (p, a) -> a.length == 1 ? Commands.filter(List.of("-r", "-c", "-o", "y-=0.2*sin(x*5)",
                        "x+=0.1*y", "swap=x;x=z;z=swap"), a[0]) : List.of());
        c.add("functions|exprhelp", EXPRESSIONS, "//functions", "The functions and variables of expressions.",
                (p, a) -> functions(p), null);
    }

    // ------------------------------------------------------------------ spline

    private void spline(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("shapes")) {
            splineHelp(player, args.length > 1 ? args[1] : null);
            return;
        }
        Map<Character, String> values = new HashMap<>();
        Set<Character> bools = new HashSet<>();
        List<String> positional = new ArrayList<>();
        String shapeToken = null;
        boolean threeD = false;
        String radiiText = null;
        String noiseText = null;
        String depthText = null;
        StringBuilder expression = null;
        String material = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (expression != null) {
                expression.append(' ').append(a);
                continue;
            }
            if (isFlag(a)) {
                char flag = Character.toLowerCase(a.charAt(1));
                if (VALUE_FLAGS.contains(flag)) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("-" + flag + " takes a value.");
                    }
                    values.put(flag, args[++i]);
                } else if (BOOL_FLAGS.contains(flag)) {
                    bools.add(flag);
                } else {
                    throw new IllegalArgumentException("Unknown flag: " + a + ". //spline help for the list.");
                }
                continue;
            }
            if (shapeToken == null && material == null && (a.equalsIgnoreCase("2d") || a.equalsIgnoreCase("3d"))) {
                threeD = a.equalsIgnoreCase("3d");
                continue;
            }
            if (shapeToken == null && material == null && Sections.canonical(a) != null) {
                shapeToken = a;
                continue;
            }
            String shape = shapeToken == null ? "circle" : Sections.canonical(shapeToken);
            if (material == null && !shape.equals("clipboard")) {
                material = a;
                continue;
            }
            if (radiiText == null && RADII.matcher(a).matches()) {
                radiiText = a;
                continue;
            }
            if (shape.equals("expr")) {
                expression = new StringBuilder(a);
                continue;
            }
            if (shape.equals("noise") && noiseText == null) {
                noiseText = a;
                continue;
            }
            if (shape.equals("noise") && depthText == null) {
                depthText = a;
                continue;
            }
            positional.add(a);
        }
        if (!positional.isEmpty()) {
            throw new IllegalArgumentException("Extra argument: " + positional.get(0) + ". //spline help for the syntax.");
        }
        String shape = shapeToken == null ? "circle" : Sections.canonical(shapeToken);
        if (threeD && shapeToken != null && shapeToken.toLowerCase(Locale.ROOT).startsWith("sc")
                && shape.equals("supercircle")) {
            shapeToken = "scales" + shapeToken.substring(2);
            shape = "scales";
        }
        if (material == null && !shape.equals("clipboard")) {
            throw new IllegalArgumentException("Usage: //spline [shape] <pattern> [radii]. //spline help for the shapes.");
        }

        World world = player.getWorld();
        List<double[]> points = splinePoints(player);
        double polyline = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            polyline += Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
        }

        Clipboard clipboard = trowel.session(player).getClipboard();
        double maxRadius = Math.max(8, trowel.settings().maxBrushSize());
        Radii radii;
        Sections.Section section;
        double clipHalfW = 0;
        double clipHalfH = 0;
        if (shape.equals("clipboard")) {
            if (clipboard == null) {
                throw new IllegalArgumentException("Empty clipboard: //copy first.");
            }
            Box b = clipboard.bounds();
            double natural = Math.max(b.width(), b.height()) / 2.0;
            radii = radiiText == null ? Radii.constant(natural) : Radii.parse(radiiText, maxRadius);
            clipHalfW = b.width() / 2.0 / natural;
            clipHalfH = b.height() / 2.0 / natural;
            double hw = clipHalfW;
            double hh = clipHalfH;
            section = new Sections.Section() {
                @Override
                public double eval(double u, double v, double w, double t) {
                    return Math.abs(u) <= hw && Math.abs(v) <= hh ? 1 : 0;
                }

                @Override
                public double reach() {
                    return Math.sqrt(hw * hw + hh * hh);
                }
            };
        } else {
            radii = Radii.parse(radiiText == null ? "3" : radiiText, maxRadius);
            if (shape.equals("noise")) {
                NoiseSpec spec = NoiseSpec.parse(noiseText == null ? "perlin(f:2,z:0.5)" : noiseText, 2);
                double depth = depthText == null ? 0.7 : number(depthText, "Depth");
                section = Sections.noise(spec, depth, values.get('i'));
            } else if (shape.equals("expr")) {
                if (expression == null) {
                    throw new IllegalArgumentException("Usage: //spline expr <palette> <radii> <expression>. "
                            + "Variables: x, y (the section, -1 to 1), z (along the path), t, s, r, a.");
                }
                double reach = values.containsKey('x') ? number(values.get('x'), "Reach") : 1.5;
                section = Sections.expression(expression.toString(), bools.contains('z'), reach, polyline);
            } else {
                section = Sections.parse(shapeToken == null ? "circle" : shapeToken);
            }
        }

        double[] kb = {0, 0, 0};
        if (values.containsKey('p')) {
            String[] parts = values.get('p').split(":");
            for (int i = 0; i < Math.min(3, parts.length); i++) {
                kb[i] = Math.max(-1, Math.min(1, number(parts[i], "Curve parameter")));
            }
        }
        double roll = 0;
        double rollEnd = Double.NaN;
        if (values.containsKey('r')) {
            String[] parts = values.get('r').split(",");
            roll = number(parts[0], "Roll");
            if (parts.length > 1) {
                rollEnd = number(parts[1], "End roll");
            }
        }
        double twist = values.containsKey('t') ? number(values.get('t'), "Twist")
                : shapeToken != null && shapeToken.toLowerCase(Locale.ROOT).startsWith("rope") ? 90 : 0;
        double stretch = values.containsKey('s') ? Math.max(0.01, number(values.get('s'), "Stretch")) : 1;
        Sweep.End end = values.containsKey('e') ? Sweep.End.parse(values.get('e')) : Sweep.End.FLAT;
        Sweep.Quality quality = values.containsKey('q') ? Sweep.Quality.parse(values.get('q')) : Sweep.Quality.BALANCED;
        Path.Normal normal = values.containsKey('n') ? Path.Normal.parse(values.get('n')) : Path.Normal.CONSISTENT;
        boolean closed = bools.contains('c');
        if (closed && points.size() < 3) {
            throw new IllegalArgumentException("-c: a loop needs at least three points.");
        }
        Sweep.Options options = new Sweep.Options(radii, roll, rollEnd, twist, stretch, end, quality, bools.contains('h'),
                normal, kb[0], kb[1], kb[2], closed);
        Mask mask = values.containsKey('m') ? c.mask(player, values.get('m')) : null;
        Box reads = Sweep.bounds(points, options, section);

        Sweep.Fill fill;
        if (shape.equals("clipboard")) {
            fill = clipboardFill(clipboard, clipHalfW, clipHalfH, bools.contains('z'), bools.contains('a'));
        } else if (section.valued() && !(material.startsWith("#") && !material.startsWith("##"))) {
            Palette palette = c.palette(player, material);
            boolean integer = bools.contains('o');
            fill = (x, y, z, l) -> integer ? palette.index(l.value) : palette.value(l.value);
        } else {
            Pattern pattern = c.pattern(player, material).within(reads);
            fill = pattern::at;
        }
        String label = "Spline " + (shapeToken == null ? "circle" : shape);
        Sections.Section built = section;
        trowel.engine().submit(player, Engine.Job.of(label, world, reads,
                context -> Sweep.build(context, points, built, options, fill, mask)));
    }

    /** The clipboard along the path: x across, y up, z along, repeated (or stretched with -z). */
    private static Sweep.Fill clipboardFill(Clipboard clipboard, double halfW, double halfH, boolean stretch, boolean air) {
        Box b = clipboard.bounds();
        int width = b.width();
        int height = b.height();
        int depth = b.depth();
        double natural = width / (2 * halfW);
        return (x, y, z, l) -> {
            int cx = b.minX() + (int) Math.min(width - 1, Math.max(0, Math.floor((l.u / halfW + 1) / 2 * width)));
            int cy = b.minY() + (int) Math.min(height - 1, Math.max(0, Math.floor((l.v / halfH + 1) / 2 * height)));
            double scale = Math.max(1e-6, l.radius / natural);
            int cz = stretch ? b.minZ() + (int) Math.min(depth - 1, Math.floor(l.t * depth))
                    : b.minZ() + Math.floorMod((int) Math.floor(l.s / scale), depth);
            BlockData data = clipboard.at(cx, cy, cz);
            if (data == null || (!air && data.getMaterial().isAir())) {
                return null;
            }
            return data;
        };
    }

    /** The spline points in this world; failing that, a straight line between both corners. */
    private List<double[]> splinePoints(Player player) {
        Session session = trowel.session(player);
        List<double[]> points = new ArrayList<>();
        for (Location point : session.getPoints()) {
            if (player.getWorld().equals(point.getWorld())) {
                points.add(new double[]{point.getBlockX() + 0.5, point.getBlockY() + 0.5, point.getBlockZ() + 0.5});
            }
        }
        if (points.size() >= 2) {
            return points;
        }
        Location a = session.getPos1();
        Location b = session.getPos2();
        if (a != null && b != null && player.getWorld().equals(a.getWorld()) && player.getWorld().equals(b.getWorld())) {
            return List.of(new double[]{a.getBlockX() + 0.5, a.getBlockY() + 0.5, a.getBlockZ() + 0.5},
                    new double[]{b.getBlockX() + 0.5, b.getBlockY() + 0.5, b.getBlockZ() + 0.5});
        }
        throw new IllegalArgumentException("Place at least two points (//point on aimed blocks, or sneak + left "
                + "click with the wand), or both corners of a selection for a straight line.");
    }

    private void splineHelp(Player player, String shape) {
        if (shape != null) {
            String name = Sections.canonical(shape);
            Sections.Def def = name == null ? null : Sections.all().get(name);
            if (def == null) {
                throw new IllegalArgumentException("Unknown shape: " + shape + ".");
            }
            Chat.info(player, def.id() + ": ", def.help());
            Chat.hint(player, "Settings: " + Sections.describe(def));
            player.sendMessage(Chat.suggest("//spline " + Sections.example(def) + " stone 4", "example"));
            return;
        }
        Chat.info(player, "//spline [shape] <pattern> [radii] [flags]: click a shape for its settings.");
        for (String category : List.of("2d", "3d")) {
            Component line = Component.text("  " + category.toUpperCase(Locale.ROOT) + ": ", NamedTextColor.DARK_AQUA);
            for (Sections.Def def : Sections.all().values()) {
                if (def.category().equals(category)) {
                    line = line.append(Component.text(def.id() + " ", NamedTextColor.GOLD)
                            .clickEvent(ClickEvent.runCommand("/trowel spline help " + def.id()))
                            .hoverEvent(HoverEvent.showText(Component.text(def.help() + "\n" + Sections.example(def),
                                    NamedTextColor.GRAY))));
                }
            }
            player.sendMessage(line);
        }
        player.sendMessage(Component.text("  Advanced: ", NamedTextColor.DARK_AQUA)
                .append(Component.text("noise <palette> [radii] [noise] [depth] [-i expression], ", NamedTextColor.GOLD))
                .append(Component.text("expr <palette> [radii] <expression> [-z] [-o] [-x reach], ", NamedTextColor.GOLD))
                .append(Component.text("clipboard [radii] [-z] [-a]", NamedTextColor.GOLD)));
        Chat.hint(player, "Radii: 5, or 1,12 (grows), 1,12,1, 1,0.2:12,1 (keyframes).");
        Chat.hint(player, "-t twist (degrees per diameter), -r roll or start,end, -s stretch, -e flat|soft|spike|round|cube, "
                + "-n consistent|horizontal|upright, -q fast|balanced|high|exact, -p tension:bias:continuity, "
                + "-h hollow, -c loop, -m mask.");
        Chat.hint(player, "The pattern can follow the shape: #local[##bark][perlin(y:0.1)], #gradient[##magma][t], "
                + "#expr[##ice][1-r].");
    }

    private List<String> completeSpline(Player player, String[] args) {
        int index = args.length - 1;
        String last = args[index];
        if (index > 0) {
            String previous = args[index - 1].toLowerCase(Locale.ROOT);
            switch (previous) {
                case "-e" -> {
                    return Commands.filter(List.of("flat", "soft", "spike", "round", "cube"), last);
                }
                case "-n" -> {
                    return Commands.filter(List.of("consistent", "horizontal", "upright"), last);
                }
                case "-q" -> {
                    return Commands.filter(List.of("fast", "balanced", "high", "exact"), last);
                }
                case "-t" -> {
                    return Commands.filter(List.of("45", "90", "180", "360", "720"), last);
                }
                case "-r" -> {
                    return Commands.filter(List.of("0", "45", "90", "0,180", "0,360"), last);
                }
                case "-s" -> {
                    return Commands.filter(List.of("0.5", "1", "2", "4"), last);
                }
                case "-p" -> {
                    return Commands.filter(List.of("0:0:0", "0.5:0:0", "-0.5:0:0", "0:0.5:0", "0:0:-1"), last);
                }
                case "-m" -> {
                    return c.completeMask(last);
                }
                case "-x" -> {
                    return Commands.filter(List.of("1", "1.5", "2"), last);
                }
                case "-i" -> {
                    return Commands.filter(List.of("r=sqrt(x*x+y*y);(r<1&&n>0.5)*max(n,0.01)"), last);
                }
                default -> {
                }
            }
        }
        if (last.startsWith("-")) {
            return Commands.filter(List.of("-t", "-r", "-s", "-e", "-n", "-q", "-p", "-h", "-c", "-m", "-z", "-o", "-a",
                    "-i", "-x"), last);
        }
        String shape = null;
        int firstFree = 0;
        for (int i = 0; i < index; i++) {
            if (args[i].startsWith("-")) {
                continue;
            }
            if (args[i].equalsIgnoreCase("2d") || args[i].equalsIgnoreCase("3d")) {
                firstFree = i + 1;
                continue;
            }
            if (shape == null && i == firstFree && Sections.canonical(args[i]) != null) {
                shape = Sections.canonical(args[i]);
            }
            break;
        }
        int positional = 0;
        for (int i = 0; i < index; i++) {
            if (!args[i].startsWith("-") && !args[i].equalsIgnoreCase("2d") && !args[i].equalsIgnoreCase("3d")) {
                positional++;
            }
        }
        if (positional == 0) {
            List<String> out = new ArrayList<>();
            String lower = last.toLowerCase(Locale.ROOT);
            int open = lower.indexOf('(');
            if (open > 0 && Sections.canonical(lower.substring(0, open)) != null) {
                Sections.Def def = Sections.all().get(Sections.canonical(lower.substring(0, open)));
                if (def != null) {
                    out.add(Sections.example(def));
                }
                return out;
            }
            List<String> names = new ArrayList<>(Sections.names());
            names.add("help");
            names.add("2d");
            names.add("3d");
            Commands.filter(names, last).forEach(out::add);
            if (!lower.isEmpty()) {
                c.completePattern(last).stream().limit(30).forEach(out::add);
            }
            return out;
        }
        boolean hasShape = shape != null;
        int materialIndex = hasShape ? 1 : 0;
        int afterMaterial = positional - materialIndex;
        if ("clipboard".equals(shape)) {
            return Commands.filter(List.of("3", "5", "8"), last);
        }
        if (afterMaterial == 0) {
            return c.completePattern(last);
        }
        if (afterMaterial == 1) {
            return Commands.filter(List.of("2", "3", "5", "8", "2,6", "1,8,1", "6,2", "1,0.3:8,2"), last);
        }
        if ("noise".equals(shape) && afterMaterial == 2) {
            return Commands.completeNoiseSpec(last);
        }
        if ("noise".equals(shape) && afterMaterial == 3) {
            return Commands.filter(List.of("0.3", "0.5", "0.7", "1"), last);
        }
        if ("expr".equals(shape) && afterMaterial == 2) {
            return Commands.filter(List.of("x*x+y*y<1", "abs(x)+abs(y)<1-0.3*sin(z*3)", "1-r", "r<1&&fract(z)<0.5"), last);
        }
        return List.of();
    }

    private static boolean isFlag(String a) {
        return a.length() == 2 && a.charAt(0) == '-' && Character.isLetter(a.charAt(1));
    }

    // ------------------------------------------------------------------ points

    private void point(Player player, String[] args) {
        Session session = trowel.session(player);
        World world = player.getWorld();
        session.getPoints().removeIf(point -> !world.equals(point.getWorld()));
        List<Location> points = session.getPoints();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "clear" -> {
                points.clear();
                Chat.info(player, "Spline points cleared.");
            }
            case "undo", "del", "remove" -> {
                if (points.isEmpty()) {
                    throw new IllegalArgumentException("No point to remove.");
                }
                Location removed = points.remove(points.size() - 1);
                Chat.info(player, "Point removed: ", removed.getBlockX() + " " + removed.getBlockY() + " " + removed.getBlockZ()
                        + " (" + points.size() + " left)");
            }
            case "list" -> {
                if (points.isEmpty()) {
                    Chat.info(player, "No point: //point on an aimed block.");
                    return;
                }
                Chat.info(player, points.size() + " point(s):");
                for (int i = 0; i < points.size(); i++) {
                    Location p = points.get(i);
                    Chat.hint(player, (i + 1) + ". " + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ());
                }
            }
            case "sel", "selection" -> {
                Location a = session.getPos1();
                Location b = session.getPos2();
                if (a == null || b == null || !world.equals(a.getWorld()) || !world.equals(b.getWorld())) {
                    throw new IllegalArgumentException("Set both corners first.");
                }
                points.clear();
                points.add(a.clone());
                points.add(b.clone());
                Chat.info(player, "Both corners become the spline points.");
            }
            case "reverse", "invert" -> {
                Collections.reverse(points);
                Chat.info(player, "Points reversed.");
            }
            case "insert" -> {
                int at = Commands.integer(Commands.need(args, 1, "//point insert <place>"), 1, points.size() + 1, "Place");
                Block block = c.target(player);
                points.add(at - 1, block.getLocation());
                Chat.info(player, "Point inserted at " + at + ": ", block.getX() + " " + block.getY() + " " + block.getZ());
            }
            default -> {
                Block block = sub.equals("here") ? Commands.feet(player) : c.target(player);
                points.add(block.getLocation());
                Chat.info(player, "Point " + points.size() + ": ", block.getX() + " " + block.getY() + " " + block.getZ());
            }
        }
        trowel.hud().showSelection(player);
    }

    private void catenary(Player player, String[] args) {
        Session session = trowel.session(player);
        World world = player.getWorld();
        int count = Commands.integer(Commands.need(args, 0, "//catenary <points> [sag] [up|down]"), 3, 64, "Points");
        List<double[]> ends;
        try {
            List<double[]> current = splinePoints(player);
            ends = List.of(current.get(0), current.get(current.size() - 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Place two points or two corners: the catenary goes from one to the other.");
        }
        double[] a = ends.get(0);
        double[] b = ends.get(1);
        double span = Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[2] - b[2]) * (a[2] - b[2]));
        double sag = args.length > 1 ? number(args[1], "Sag") : Math.max(1, span / 6);
        boolean up = args.length > 2 && args[2].equalsIgnoreCase("up");
        double shape = 1.5;
        double norm = Math.cosh(shape) - 1;
        List<Location> points = session.getPoints();
        points.clear();
        for (int i = 0; i < count; i++) {
            double t = (double) i / (count - 1);
            double drop = sag * (Math.cosh(shape) - Math.cosh(shape * (2 * t - 1))) / norm;
            double y = a[1] + (b[1] - a[1]) * t + (up ? drop : -drop);
            points.add(new Location(world, Math.floor(a[0] + (b[0] - a[0]) * t), Math.floor(y),
                    Math.floor(a[2] + (b[2] - a[2]) * t)));
        }
        Chat.info(player, "Catenary: ", count + " points, sagging " + trim(sag) + " blocks. //spline to place it.");
        trowel.hud().showSelection(player);
    }

    // ------------------------------------------------------------- expressions

    /** How an expression sees the selection: from -1 to 1, in world blocks, centered, or from the player. */
    private record Frame(double cx, double cy, double cz, double sx, double sy, double sz) {
        static Frame of(Set<String> flags, Box box, Block feet) {
            if (flags.contains("r")) {
                return new Frame(0, 0, 0, 1, 1, 1);
            }
            if (flags.contains("o")) {
                return new Frame(feet.getX(), feet.getY(), feet.getZ(), 1, 1, 1);
            }
            double cx = (box.minX() + box.maxX()) / 2.0;
            double cy = (box.minY() + box.maxY()) / 2.0;
            double cz = (box.minZ() + box.maxZ()) / 2.0;
            if (flags.contains("c")) {
                return new Frame(cx, cy, cz, 1, 1, 1);
            }
            return new Frame(cx, cy, cz, Math.max(0.5, (box.maxX() - box.minX()) / 2.0),
                    Math.max(0.5, (box.maxY() - box.minY()) / 2.0), Math.max(0.5, (box.maxZ() - box.minZ()) / 2.0));
        }

        double x(int bx) {
            return (bx - cx) / sx;
        }

        double y(int by) {
            return (by - cy) / sy;
        }

        double z(int bz) {
            return (bz - cz) / sz;
        }
    }

    private void generate(Player player, String[] args) {
        String usage = "//generate [-h] [-r|-c|-o] <pattern|palette> <expression>";
        Set<String> flags = new HashSet<>();
        int i = 0;
        while (i < args.length && isFlag(args[i]) && "hrco".indexOf(Character.toLowerCase(args[i].charAt(1))) >= 0) {
            flags.add(String.valueOf(Character.toLowerCase(args[i].charAt(1))));
            i++;
        }
        String material = Commands.need(args, i, usage);
        String source = Commands.join(args, i + 1);
        if (source.isBlank()) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
        Box box = c.region(player);
        Frame frame = Frame.of(flags, box, Commands.feet(player));
        Expression expression = Expression.compile(source, "x", "y", "z", "wx", "wy", "wz");
        boolean special = material.startsWith("#") && !material.startsWith("##");
        Pattern pattern = special ? c.pattern(player, material).within(box) : null;
        Palette palette = special ? null : c.palette(player, material);
        boolean hollow = flags.contains("h");
        c.run(player, "Generate", box, context -> {
            Progress progress = context.progress();
            Expression.Frame f = expression.frame();
            f.world = query(context.view());
            Long2ObjectOpenHashMap<BlockData> inside = new Long2ObjectOpenHashMap<>();
            ChangeSet changes = context.changes();
            Local local = new Local();
            progress.phase("computing", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                progress.tick(1);
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        f.inputs(frame.x(x), frame.y(y), frame.z(z), x, y, z);
                        f.blockX = x;
                        f.blockY = y;
                        f.blockZ = z;
                        double v = expression.run(f);
                        if (!(v > 0)) {
                            continue;
                        }
                        BlockData data;
                        if (pattern != null) {
                            local.value = v;
                            data = pattern.at(x, y, z, local);
                        } else {
                            data = palette.size() == 1 ? palette.get(0) : palette.value(v);
                        }
                        if (data == null) {
                            continue;
                        }
                        if (hollow) {
                            inside.put(Keys.pack(x, y, z), data);
                        } else {
                            changes.set(x, y, z, data);
                        }
                    }
                }
            }
            if (hollow) {
                for (Long2ObjectOpenHashMap.Entry<BlockData> entry : inside.long2ObjectEntrySet()) {
                    long key = entry.getLongKey();
                    if (!inside.containsKey(Keys.offset(key, 1, 0, 0)) || !inside.containsKey(Keys.offset(key, -1, 0, 0))
                            || !inside.containsKey(Keys.offset(key, 0, 1, 0)) || !inside.containsKey(Keys.offset(key, 0, -1, 0))
                            || !inside.containsKey(Keys.offset(key, 0, 0, 1)) || !inside.containsKey(Keys.offset(key, 0, 0, -1))) {
                        changes.set(key, entry.getValue());
                    }
                }
            }
            return changes;
        });
    }

    private void deform(Player player, String[] args) {
        String usage = "//deform [-r|-c|-o] <expression>";
        Set<String> flags = new HashSet<>();
        int i = 0;
        while (i < args.length && isFlag(args[i]) && "rco".indexOf(Character.toLowerCase(args[i].charAt(1))) >= 0) {
            flags.add(String.valueOf(Character.toLowerCase(args[i].charAt(1))));
            i++;
        }
        String source = Commands.join(args, i);
        if (source.isBlank()) {
            throw new IllegalArgumentException("Usage: " + usage + "; for example //deform y-=0.2*sin(x*5)");
        }
        Box box = c.region(player);
        Frame frame = Frame.of(flags, box, Commands.feet(player));
        Expression expression = Expression.compile(source, "x", "y", "z");
        Box reads = box.grow(16);
        c.run(player, "Deform", reads, context -> {
            Progress progress = context.progress();
            Expression.Frame f = expression.frame();
            f.world = query(context.view());
            ChangeSet changes = context.changes();
            progress.phase("computing", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                progress.tick(1);
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        f.inputs(frame.x(x), frame.y(y), frame.z(z));
                        f.blockX = x;
                        f.blockY = y;
                        f.blockZ = z;
                        expression.run(f);
                        int sx = (int) Math.round(f.get(0) * frame.sx() + frame.cx());
                        int sy = (int) Math.round(f.get(1) * frame.sy() + frame.cy());
                        int sz = (int) Math.round(f.get(2) * frame.sz() + frame.cz());
                        if (sx == x && sy == y && sz == z) {
                            continue;
                        }
                        changes.set(x, y, z, context.view().get(sx, sy, sz));
                    }
                }
            }
            return changes;
        });
    }

    private static Expression.WorldQuery query(com.stackmc.trowel.engine.BlockView view) {
        return new Expression.WorldQuery() {
            @Override
            public boolean solid(int x, int y, int z) {
                return view.solid(x, y, z);
            }

            @Override
            public boolean air(int x, int y, int z) {
                return view.air(x, y, z);
            }
        };
    }

    private List<String> completeGenerate(Player player, String[] args) {
        String last = args[args.length - 1];
        int material = 0;
        while (material < args.length - 1 && isFlag(args[material])) {
            material++;
        }
        if (args.length - 1 < material || (args.length - 1 == material && last.startsWith("-"))) {
            return Commands.filter(List.of("-h", "-r", "-c", "-o"), last);
        }
        if (args.length - 1 == material) {
            return c.completePattern(last);
        }
        return Commands.filter(List.of("x*x+y*y+z*z<1", "y<noise(x*2,0,z*2)*2-1", "abs(x)+abs(y)+abs(z)<1",
                "sdtorus(x,y,z,0.6,0.25)<0", "(x*x+y*y+z*z<1)*(y+1)/2", "cracks(x*3,y*3,z*3)>0.3&&x*x+y*y+z*z<1"), last);
    }

    private void functions(Player player) {
        Chat.info(player, "Expressions: x, y, z and the command's variables; true means positive.");
        Chat.hint(player, "Operators: + - * / % ^, == != < <= > >=, && || !, ?:, = += -= *= /=, ++ --");
        Chat.hint(player, "Statements: a;b  if (c) {..} else {..}  while (c) {..}  for (i = 0, 9) {..}  "
                + "for (i = 0; i < 9; i++) {..}  break  continue  return");
        Chat.hint(player, "Constants: pi, e, tau, phi, true, false");
        Component line = Component.text("  ", NamedTextColor.GRAY);
        int count = 0;
        for (Map.Entry<String, String> entry : Functions.usages().entrySet()) {
            line = line.append(Component.text(entry.getKey() + " ", NamedTextColor.GOLD)
                    .hoverEvent(HoverEvent.showText(Component.text(entry.getValue(), NamedTextColor.GRAY))));
            if (++count % 12 == 0) {
                player.sendMessage(line);
                line = Component.text("  ", NamedTextColor.GRAY);
            }
        }
        player.sendMessage(line);
        Chat.hint(player, "//generate: x, y, z from -1 to 1 in the selection (-r world, -c center, -o from you), "
                + "wx, wy, wz the block. //spline expr: x, y the section, z along, t, s, r, a.");
    }

    // ------------------------------------------------------------------- tools

    private static double number(String raw, String label) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + ": number expected, not '" + raw + "'.");
        }
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
