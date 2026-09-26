package com.stackmc.trowel.ui;

import com.stackmc.trowel.Chat;
import com.stackmc.trowel.Session;
import com.stackmc.trowel.Trowel;
import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.engine.BlockView;
import com.stackmc.trowel.engine.ChangeSet;
import com.stackmc.trowel.engine.Engine;
import com.stackmc.trowel.engine.RegionOps;
import com.stackmc.trowel.engine.Terrain;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.pattern.Mask;
import com.stackmc.trowel.pattern.Pattern;
import com.stackmc.trowel.spline.Path;
import com.stackmc.trowel.spline.Radii;
import com.stackmc.trowel.spline.Sections;
import com.stackmc.trowel.spline.Sweep;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Arceon's tools and a few from VoxelSniper: roofs, roads, rivers, dotted lines along the
 * points; smear, revolve, resize, shade, snow; text, loft.
 */
final class ToolCommands {

    static final String TOOLS = "Arceon tools";

    private final Trowel trowel;
    private final Commands c;

    ToolCommands(Trowel trowel, Commands commands) {
        this.trowel = trowel;
        this.c = commands;
    }

    void define() {
        c.add("roof", TOOLS, "//roof <pattern> <width> [height] [-n] [-d] [-b] [-h] [-p]",
                "A gable roof along the points. -n irregular, -d goes down, -b hipped ends, -h hollow, -p straight.",
                (p, a) -> roofOrRoad(p, a, true), c.args("pattern", "n:5|7|9", "n:3|4|6", "flag:-n|-d|-b|-h|-p"));
        c.add("road", TOOLS, "//road <pattern> <width> [thickness] [-n] [-d] [-p]",
                "A road that follows the points and stays flat. -n irregular edges, -d goes down to the ground, -p straight.",
                (p, a) -> roofOrRoad(p, a, false), c.args("pattern", "n:3|5|7", "n:1|2", "flag:-n|-d|-p"));
        c.add("river", TOOLS, "//river <pattern> <start depth> <end depth> [slope] [-n]",
                "Carves a bed along the points and fills it (water, snow...) up to one block below the banks.",
                this::river, c.args("word:water|snow_block|ice", "n:2|3|4", "n:2|4|6", "n:1|2|3", "flag:-n"));
        c.add("dashes", TOOLS, "//dashes <pattern> <dash> <gap> [width] [-d]",
                "Dotted line along the points; -d places a dot in the middle of each gap.", this::dashes,
                c.args("pattern", "n:2|3|4", "n:1|2|3", "n:1|2|3", "flag:-d"));
        c.add("smear", TOOLS, "//smear <distance> [direction] [-r]",
                "Stretches the selection in a direction, like a trail; -r also overwrites what is not air.",
                this::smear, c.args("n:3|5|10", "dir", "flag:-r"));
        c.add("revolve", TOOLS, "//revolve <copies> [start angle] [end angle] [height] [-r]",
                "Turns copies of the selection around you: colonnades, spiral stairs, crowns.",
                this::revolve, c.args("n:4|8|12|16", "n:0", "n:360", "n:0|10|20", "flag:-r"));
        c.add("resize|scale", TOOLS, "//resize <scale> [-a]",
                "Enlarges or shrinks the content of the selection from its bottom corner; -a keeps air.",
                this::resize, c.args("n:0.5|1.5|2|3", "flag:-a"));
        c.add("colorreplace|cr", TOOLS, "//colorreplace <color> <color> [-b] [-g]",
                "Changes one color into another on wool, concrete, terracotta, glass... -b without banners, -g without glazed terracotta.",
                this::colorReplace, (p, a) -> a.length <= 2 ? Commands.filter(Arrays.stream(DyeColor.values())
                        .map(color -> color.name().toLowerCase(Locale.ROOT)).toList(), a[a.length - 1])
                        : Commands.filter(List.of("-b", "-g"), a[a.length - 1]));
        c.add("shadow", TOOLS, "//shadow <pattern>",
                "Paints the shadow the build casts on itself, seen from your position.",
                this::shadow, c.args("pattern"));
        c.add("smoothsnow|ss", TOOLS, "//smoothsnow [smoothness] [mask] [pattern] [thickness] [-o]",
                "Smooth layered snow that softens the relief; -o only on the mask.",
                this::smoothSnow, c.args("n:4|6|8", "mask", "word:snow|powder_snow", "word:0.4|1|2", "flag:-o"));
        c.add("ocean|flood", TOOLS, "//ocean <level> [pattern]", "Fills the air of the selection up to this level (water by default).",
                this::ocean, c.args("n:62|63|64", "word:water|lava"));
        c.add("spiralstairs|spiral", TOOLS, "//spiralstairs <pattern> <radius> <height> [turns] [core]",
                "A spiral staircase around your feet.", this::spiralStairs,
                c.args("pattern", "n:3|4|6", "n:8|16|32", "n:1|2|3", "n:0|1"));
        c.add("text", TOOLS, "//text <pattern> <size> <text>", "Writes text in blocks, facing you. //font for the font.",
                this::text, c.args("pattern", "n:10|16|24"));
        c.add("font", TOOLS, "//font [font]", "The //text font; without an argument, the list.", this::font, this::completeFont);
        c.add("loft", TOOLS, "//loft <frame|point|remove|clear|set <pattern>> [-o] [-c] [-d] [-p]",
                "A surface stretched between frames of points: hulls, vaults, curved roofs.", this::loft,
                c.args("word:frame|point|remove|clear|set", "pattern", "flag:-o|-c|-d|-p"));
    }

    // -------------------------------------------------------- along the points

    private List<double[]> points(Player player) {
        Session session = trowel.session(player);
        List<double[]> out = new ArrayList<>();
        for (Location point : session.getPoints()) {
            if (player.getWorld().equals(point.getWorld())) {
                out.add(new double[]{point.getBlockX() + 0.5, point.getBlockY() + 0.5, point.getBlockZ() + 0.5});
            }
        }
        if (out.size() >= 2) {
            return out;
        }
        Location a = session.getPos1();
        Location b = session.getPos2();
        if (a != null && b != null && player.getWorld().equals(a.getWorld()) && player.getWorld().equals(b.getWorld())) {
            return List.of(new double[]{a.getBlockX() + 0.5, a.getBlockY() + 0.5, a.getBlockZ() + 0.5},
                    new double[]{b.getBlockX() + 0.5, b.getBlockY() + 0.5, b.getBlockZ() + 0.5});
        }
        throw new IllegalArgumentException("Place at least two points (//point), or both corners of a selection.");
    }

    private void roofOrRoad(Player player, String[] args, boolean roof) {
        Set<String> flags = Commands.flags(args);
        String[] rest = Commands.plain(args);
        String usage = roof ? "//roof <pattern> <width> [height]" : "//road <pattern> <width> [thickness]";
        Pattern pattern = c.pattern(player, Commands.need(rest, 0, usage));
        double width = number(Commands.need(rest, 1, usage), "Width");
        double half = Math.max(0.5, width / 2);
        double second = rest.length > 2 ? number(rest[2], roof ? "Height" : "Thickness") : roof ? half * 0.8 : 1;
        String spec;
        if (roof) {
            spec = "roof(H:" + second / half + ",D:" + (flags.contains("d") ? 3 : 0) + ",N:" + (flags.contains("n") ? 1 : 0) + ")";
        } else {
            spec = "road(T:" + (flags.contains("d") ? 8 : second / half) + ",N:" + (flags.contains("n") ? 1 : 0) + ")";
        }
        Sections.Section section = Sections.parse(spec);
        Sweep.End end = roof && flags.contains("b") ? Sweep.End.SPIKE : Sweep.End.FLAT;
        Sweep.Options options = new Sweep.Options(Radii.constant(half), 0, Double.NaN, 0, 1, end, Sweep.Quality.BALANCED,
                roof && flags.contains("h"), Path.Normal.HORIZONTAL, flags.contains("p") ? 1 : 0, 0, 0, false);
        List<double[]> points = points(player);
        Box reads = Sweep.bounds(points, options, section).expand(0, roof ? (int) Math.ceil(second) + 2 : 0, 0)
                .expand(0, -(flags.contains("d") ? (int) Math.ceil(half * 8) : 2), 0);
        Pattern within = pattern.within(reads);
        trowel.engine().submit(player, Engine.Job.of(roof ? "Roof" : "Road", player.getWorld(), reads,
                context -> Sweep.build(context, points, section, options, within::at, null)));
    }

    private void river(Player player, String[] args) {
        Set<String> flags = Commands.flags(args);
        String[] rest = Commands.plain(args);
        String usage = "//river <pattern> <start depth> <end depth> [slope]";
        Pattern pattern = c.pattern(player, Commands.need(rest, 0, usage));
        double start = Math.max(1, number(Commands.need(rest, 1, usage), "Depth"));
        double end = Math.max(1, number(Commands.need(rest, 2, usage), "Depth"));
        double decline = rest.length > 3 ? Math.max(0.3, number(rest[3], "Slope")) : 1;
        boolean noisy = flags.contains("n");
        Radii radii = Radii.parse((start * decline * 1.5) + "," + (end * decline * 1.5), 128);
        double k = decline * 1.5;
        Sections.Section channel = new Sections.Section() {
            @Override
            public double eval(double u, double v, double w, double t) {
                double edge = noisy ? 1 - 0.25 * (0.5 + 0.5 * com.stackmc.trowel.geom.Noise.gradient(w * 2.1, 4.2, 1.7)) : 1;
                double x = u / edge;
                if (Math.abs(x) > 1) {
                    return 0;
                }
                double bed = -(1 - x * x) / k;
                return v >= bed && v <= 3 / k ? 1 : 0;
            }

            @Override
            public double reach() {
                return 1.5;
            }
        };
        Sweep.Options options = new Sweep.Options(radii, 0, Double.NaN, 0, 1, Sweep.End.ROUND, Sweep.Quality.BALANCED,
                false, Path.Normal.HORIZONTAL, 0, 0, 0, false);
        List<double[]> points = points(player);
        Box reads = Sweep.bounds(points, options, channel);
        BlockData air = trowel.air();
        trowel.engine().submit(player, Engine.Job.of("River", player.getWorld(), reads,
                context -> Sweep.build(context, points, channel, options,
                        (x, y, z, l) -> l.v * l.radius <= -1 ? pattern.at(x, y, z) : air, null)));
    }

    private void dashes(Player player, String[] args) {
        Set<String> flags = Commands.flags(args);
        String[] rest = Commands.plain(args);
        String usage = "//dashes <pattern> <dash> <gap> [width]";
        Pattern pattern = c.pattern(player, Commands.need(rest, 0, usage));
        double dash = Math.max(0.5, number(Commands.need(rest, 1, usage), "Dash"));
        double gap = Math.max(0.5, number(Commands.need(rest, 2, usage), "Gap"));
        double width = rest.length > 3 ? Math.max(1, number(rest[3], "Width")) : 1;
        boolean dots = flags.contains("d");
        Sweep.Options options = new Sweep.Options(Radii.constant(Math.max(0.5, width / 2)), 0, Double.NaN, 0, 1, Sweep.End.FLAT,
                Sweep.Quality.BALANCED, false, Path.Normal.HORIZONTAL, 0, 0, 0, false);
        Sections.Section section = Sections.parse("circle");
        List<double[]> points = points(player);
        Box reads = Sweep.bounds(points, options, section);
        double period = dash + gap;
        trowel.engine().submit(player, Engine.Job.of("Dashes", player.getWorld(), reads,
                context -> Sweep.build(context, points, section, options, (x, y, z, l) -> {
                    double at = l.s - Math.floor(l.s / period) * period;
                    if (at < dash) {
                        return pattern.at(x, y, z);
                    }
                    return dots && Math.abs(at - dash - gap / 2) < 0.5 ? pattern.at(x, y, z) : null;
                }, null)));
    }

    // ----------------------------------------------------------------- volumes

    private void smear(Player player, String[] args) {
        Set<String> flags = Commands.flags(args);
        String[] rest = Commands.plain(args);
        int n = Commands.integer(Commands.need(rest, 0, "//smear <distance> [direction] [-r]"), -128, 128, "Distance");
        int[] d = Commands.direction(player, rest.length > 1 ? rest[1] : null);
        if (n < 0) {
            n = -n;
            d = new int[]{-d[0], -d[1], -d[2]};
        }
        Box box = c.region(player);
        int[] dir = d;
        int count = n;
        boolean replace = flags.contains("r");
        Box reads = box.union(box.shift(dir[0] * count, dir[1] * count, dir[2] * count));
        c.run(player, "Smear", reads, context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.view();
            context.progress().phase("smear", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        BlockData data = view.get(x, y, z);
                        if (data.getMaterial().isAir()) {
                            continue;
                        }
                        for (int i = 1; i <= count; i++) {
                            int tx = x + dir[0] * i;
                            int ty = y + dir[1] * i;
                            int tz = z + dir[2] * i;
                            if (replace || view.air(tx, ty, tz)) {
                                changes.set(tx, ty, tz, data);
                            }
                        }
                    }
                }
            }
            return changes;
        });
    }

    private void revolve(Player player, String[] args) {
        Set<String> flags = Commands.flags(args);
        String[] rest = Commands.plain(args);
        String usage = "//revolve <copies> [start angle] [end angle] [height]";
        int copies = Commands.integer(Commands.need(rest, 0, usage), 1, 360, "Copies");
        double start = rest.length > 1 ? number(rest[1], "Angle") : 0;
        double end = rest.length > 2 ? number(rest[2], "Angle") : 360;
        double rise = rest.length > 3 ? number(rest[3], "Height") : 0;
        if (flags.contains("r")) {
            start = -start;
            end = -end;
        }
        Box box = c.region(player);
        Block center = Commands.feet(player);
        double cx = center.getX() + 0.5;
        double cz = center.getZ() + 0.5;
        double reach = 0;
        for (int i = 0; i < 4; i++) {
            double px = (i & 1) == 0 ? box.minX() : box.maxX() + 1;
            double pz = (i & 2) == 0 ? box.minZ() : box.maxZ() + 1;
            reach = Math.max(reach, Math.hypot(px - cx, pz - cz));
        }
        int r = (int) Math.ceil(reach) + 1;
        Box reads = new Box(center.getX() - r, box.minY() + (int) Math.min(0, Math.floor(rise)), center.getZ() - r,
                center.getX() + r, box.maxY() + (int) Math.max(0, Math.ceil(rise)), center.getZ() + r);
        boolean full = Math.abs(Math.abs(end - start) - 360) < 1e-6;
        double step = copies <= 1 ? 0 : (end - start) / (full ? copies : copies - 1);
        double lift = copies <= 1 ? 0 : rise / (copies - 1);
        double first = start;
        c.run(player, "Revolve", reads, context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.view();
            context.progress().phase("copies", copies);
            for (int k = 0; k < copies; k++) {
                context.progress().tick(1);
                double a = Math.toRadians(first + step * k);
                double cos = Math.cos(a);
                double sin = Math.sin(a);
                int dy = (int) Math.round(lift * k);
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    for (int y = box.minY(); y <= box.maxY(); y++) {
                        for (int z = box.minZ(); z <= box.maxZ(); z++) {
                            BlockData data = view.get(x, y, z);
                            if (data.getMaterial().isAir()) {
                                continue;
                            }
                            double px = x + 0.5 - cx;
                            double pz = z + 0.5 - cz;
                            int tx = (int) Math.floor(cx + px * cos - pz * sin);
                            int tz = (int) Math.floor(cz + px * sin + pz * cos);
                            changes.set(tx, y + dy, tz, data);
                        }
                    }
                }
            }
            return changes;
        });
    }

    private void resize(Player player, String[] args) {
        Set<String> flags = Commands.flags(args);
        String[] rest = Commands.plain(args);
        double scale = number(Commands.need(rest, 0, "//resize <scale> [-a]"), "Scale");
        if (scale <= 0.05 || scale > 8) {
            throw new IllegalArgumentException("Scale: between 0.05 and 8.");
        }
        Box box = c.region(player);
        Box scaled = new Box(box.minX(), box.minY(), box.minZ(),
                box.minX() + Math.max(0, (int) Math.round(box.width() * scale) - 1),
                box.minY() + Math.max(0, (int) Math.round(box.height() * scale) - 1),
                box.minZ() + Math.max(0, (int) Math.round(box.depth() * scale) - 1));
        boolean air = flags.contains("a");
        World world = player.getWorld();
        Box reads = box.union(scaled);
        trowel.engine().submit(player, Engine.Job.of("Resize", world, reads, context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.view();
            context.progress().phase("scale", reads.width());
            for (int x = reads.minX(); x <= reads.maxX(); x++) {
                context.progress().tick(1);
                for (int y = reads.minY(); y <= reads.maxY(); y++) {
                    for (int z = reads.minZ(); z <= reads.maxZ(); z++) {
                        BlockData out;
                        if (scaled.contains(x, y, z)) {
                            int sx = box.minX() + (int) Math.floor((x - box.minX() + 0.5) / scale);
                            int sy = box.minY() + (int) Math.floor((y - box.minY() + 0.5) / scale);
                            int sz = box.minZ() + (int) Math.floor((z - box.minZ() + 0.5) / scale);
                            out = box.contains(sx, sy, sz) ? view.get(sx, sy, sz) : context.air();
                            if (!air && out.getMaterial().isAir() && !box.contains(x, y, z)) {
                                continue;
                            }
                        } else {
                            out = context.air();
                        }
                        if (!out.equals(view.get(x, y, z))) {
                            changes.set(x, y, z, out);
                        }
                    }
                }
            }
            return changes;
        }).then(count -> {
            trowel.session(player).select(world, scaled);
            trowel.hud().showSelection(player);
        }));
    }

    private void colorReplace(Player player, String[] args) {
        Set<String> flags = Commands.flags(args);
        String[] rest = Commands.plain(args);
        String usage = "//colorreplace <color> <color> [-b] [-g]";
        String from = color(Commands.need(rest, 0, usage));
        String to = color(Commands.need(rest, 1, usage));
        Box box = c.region(player);
        Engine.Compute swap = RegionOps.typeReplace(box, from, to);
        boolean noBanners = flags.contains("b");
        boolean noGlazed = flags.contains("g");
        c.run(player, "Colors", box, context -> {
            ChangeSet all = swap.run(context);
            ChangeSet kept = context.changes();
            for (var entry : all.blocks().long2ObjectEntrySet()) {
                String id = entry.getValue().getMaterial().getKey().getKey();
                if ((noBanners && id.contains("banner")) || (noGlazed && id.contains("glazed"))) {
                    continue;
                }
                long key = entry.getLongKey();
                kept.set(Keys.x(key), Keys.y(key), Keys.z(key), entry.getValue());
            }
            return kept;
        });
    }

    private static String color(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        for (DyeColor color : DyeColor.values()) {
            if (color.name().toLowerCase(Locale.ROOT).equals(lower)) {
                return lower;
            }
        }
        throw new IllegalArgumentException("Unknown color: " + raw + ". Colors: " + String.join(", ",
                Arrays.stream(DyeColor.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toList()));
    }

    private void shadow(Player player, String[] args) {
        Pattern pattern = c.pattern(player, Commands.need(args, 0, "//shadow <pattern>"));
        Box box = c.region(player);
        Location eye = player.getEyeLocation();
        double ex = eye.getX();
        double ey = eye.getY();
        double ez = eye.getZ();
        c.run(player, "Shadow", box.grow(8), context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.terrain();
            context.progress().phase("shadow", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        if (!view.solid(x, y, z) || !view.exposed(x, y, z)) {
                            continue;
                        }
                        double dx = ex - (x + 0.5);
                        double dy = ey - (y + 0.5);
                        double dz = ez - (z + 0.5);
                        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (len < 1) {
                            continue;
                        }
                        dx /= len;
                        dy /= len;
                        dz /= len;
                        boolean blocked = false;
                        for (double t = 1.2; t < len - 0.5; t += 0.4) {
                            int bx = (int) Math.floor(x + 0.5 + dx * t);
                            int by = (int) Math.floor(y + 0.5 + dy * t);
                            int bz = (int) Math.floor(z + 0.5 + dz * t);
                            if ((bx != x || by != y || bz != z) && view.solid(bx, by, bz)) {
                                blocked = true;
                                break;
                            }
                        }
                        if (blocked) {
                            changes.set(x, y, z, pattern.at(x, y, z));
                        }
                    }
                }
            }
            return changes;
        });
    }

    private void smoothSnow(Player player, String[] args) {
        Set<String> flags = Commands.flags(args);
        String[] rest = Commands.plain(args);
        int smoothness = rest.length > 0 ? Commands.integer(rest[0], 1, 16, "Smoothness") : 6;
        Mask mask = rest.length > 1 ? c.mask(player, rest[1]) : null;
        String material = rest.length > 2 ? rest[2] : "snow";
        double added = rest.length > 3 ? number(rest[3], "Thickness") : 0.4;
        boolean layered = material.equalsIgnoreCase("snow");
        Pattern pattern = layered ? null : c.pattern(player, material);
        boolean overlay = flags.contains("o");
        Box box = c.region(player);
        c.run(player, "Smooth snow", box.expand(0, 4, 0), context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.terrain();
            int w = box.width();
            int d = box.depth();
            double[] heights = new double[w * d];
            int[] tops = new int[w * d];
            boolean[] valid = new boolean[w * d];
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    int wx = box.minX() + x;
                    int wz = box.minZ() + z;
                    int top = Terrain.surface(view, wx, wz, box.maxY(), box.minY());
                    tops[x + w * z] = top;
                    valid[x + w * z] = top != Terrain.NONE;
                    heights[x + w * z] = top == Terrain.NONE ? 0 : top + 1;
                }
            }
            double[] smooth = new double[w * d];
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    double sum = 0;
                    double weight = 0;
                    for (int dx = -smoothness; dx <= smoothness; dx++) {
                        for (int dz = -smoothness; dz <= smoothness; dz++) {
                            int nx = x + dx;
                            int nz = z + dz;
                            if (nx < 0 || nz < 0 || nx >= w || nz >= d || !valid[nx + w * nz]) {
                                continue;
                            }
                            double g = Math.exp(-(dx * dx + dz * dz) / (2.0 * smoothness * smoothness / 4));
                            sum += heights[nx + w * nz] * g;
                            weight += g;
                        }
                    }
                    smooth[x + w * z] = weight == 0 ? heights[x + w * z] : sum / weight;
                }
            }
            BlockData block = Material.SNOW_BLOCK.createBlockData();
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    int i = x + w * z;
                    if (!valid[i]) {
                        continue;
                    }
                    int wx = box.minX() + x;
                    int wz = box.minZ() + z;
                    int top = tops[i];
                    if (mask != null && !mask.test(wx, top, wz, context.view())) {
                        continue;
                    }
                    double thickness = Math.max(smooth[i], heights[i]) + added - heights[i];
                    if (overlay) {
                        thickness = Math.min(thickness, 1);
                    }
                    int layers = (int) Math.round(thickness * 8);
                    for (int k = 0; layers > 0 && top + 1 + k <= box.maxY() + 4; k++) {
                        int y = top + 1 + k;
                        if (!view.air(wx, y, wz)) {
                            break;
                        }
                        if (!layered) {
                            changes.set(wx, y, wz, pattern.at(wx, y, wz));
                            layers -= 8;
                        } else if (layers >= 8) {
                            changes.set(wx, y, wz, block);
                            layers -= 8;
                        } else {
                            org.bukkit.block.data.type.Snow snow = (org.bukkit.block.data.type.Snow) Material.SNOW.createBlockData();
                            snow.setLayers(Math.max(snow.getMinimumLayers(), Math.min(snow.getMaximumLayers(), layers)));
                            changes.set(wx, y, wz, snow);
                            layers = 0;
                        }
                    }
                }
            }
            return changes;
        });
    }

    private void ocean(Player player, String[] args) {
        int level = Commands.integer(Commands.need(args, 0, "//ocean <level> [pattern]"), -2048, 2048, "Level");
        Pattern pattern = c.pattern(player, args.length > 1 ? args[1] : "water");
        Box box = c.region(player);
        c.run(player, "Ocean", box, context -> {
            ChangeSet changes = context.changes();
            context.progress().phase("filling", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int y = box.minY(); y <= Math.min(box.maxY(), level); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        if (context.view().air(x, y, z)) {
                            changes.set(x, y, z, pattern.at(x, y, z));
                        }
                    }
                }
            }
            return changes;
        });
    }

    private void spiralStairs(Player player, String[] args) {
        String usage = "//spiralstairs <pattern> <radius> <height> [turns] [core]";
        Pattern pattern = c.pattern(player, Commands.need(args, 0, usage));
        int radius = Commands.integer(Commands.need(args, 1, usage), 1, 32, "Radius");
        int height = Commands.integer(Commands.need(args, 2, usage), 2, 256, "Height");
        double turns = args.length > 3 ? number(args[3], "Turns") : Math.max(1, height / 12.0);
        int core = args.length > 4 ? Commands.integer(args[4], 0, radius - 1, "Core") : 0;
        Block base = Commands.feet(player);
        Box reads = Box.around(base.getX(), base.getY(), base.getZ(), radius + 1).expand(0, height, 0);
        double perStep = Math.PI * 2 * turns / height;
        double wedge = Math.max(perStep * 1.6, Math.PI / 8);
        c.run(player, "Spiral stairs", reads, context -> {
            ChangeSet changes = context.changes();
            for (int k = 0; k < height; k++) {
                double angle = perStep * k;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        double r = Math.sqrt(dx * dx + dz * dz);
                        int x = base.getX() + dx;
                        int y = base.getY() + k;
                        int z = base.getZ() + dz;
                        if (r <= core + 0.5 && core > 0) {
                            changes.set(x, y, z, pattern.at(x, y, z));
                            continue;
                        }
                        if (r > radius + 0.5 || r < core + 0.5) {
                            continue;
                        }
                        double a = Math.atan2(dz, dx) - angle;
                        a = a - Math.PI * 2 * Math.floor(a / (Math.PI * 2));
                        if (a <= wedge) {
                            changes.set(x, y, z, pattern.at(x, y, z));
                        }
                    }
                }
            }
            return changes;
        });
    }

    // -------------------------------------------------------------------- text

    private void text(Player player, String[] args) {
        String usage = "//text <pattern> <size> <text>";
        Pattern pattern = c.pattern(player, Commands.need(args, 0, usage));
        int size = Commands.integer(Commands.need(args, 1, usage), 4, 128, "Size");
        String text = Commands.join(args, 2);
        if (text.isBlank()) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
        String fontName = trowel.session(player).getFont();
        Block base = c.target(player).getRelative(BlockFace.UP);
        BlockFace facing = player.getFacing();
        int rx = -facing.getModZ();
        int rz = facing.getModX();
        int length = (int) Math.ceil(size * 0.9 * text.length()) + size;
        Box reads = new Box(base.getX(), base.getY(), base.getZ(), base.getX() + rx * length, base.getY() + size * 2,
                base.getZ() + rz * length).grow(1);
        c.run(player, "Text", reads, context -> {
            System.setProperty("java.awt.headless", System.getProperty("java.awt.headless", "true"));
            Font font = new Font(fontName, Font.PLAIN, size);
            FontRenderContext frc = new FontRenderContext(null, true, true);
            Rectangle2D bounds = font.getStringBounds(text, frc);
            int w = (int) Math.ceil(bounds.getWidth()) + 2;
            int h = (int) Math.ceil(bounds.getHeight()) + 2;
            if ((long) w * h > 4_000_000L) {
                throw new IllegalArgumentException("Text too large.");
            }
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setFont(font);
            g.drawString(text, 1, (float) -bounds.getY() + 1);
            g.dispose();
            ChangeSet changes = context.changes();
            for (int px = 0; px < w; px++) {
                for (int py = 0; py < h; py++) {
                    if ((image.getRaster().getSample(px, py, 0) & 0xFF) < 128) {
                        continue;
                    }
                    int x = base.getX() + rx * px;
                    int y = base.getY() + (h - 1 - py);
                    int z = base.getZ() + rz * px;
                    changes.set(x, y, z, pattern.at(x, y, z));
                }
            }
            return changes;
        });
    }

    private void font(Player player, String[] args) {
        if (args.length == 0) {
            Chat.info(player, "Current font: ", trowel.session(player).getFont());
            Chat.hint(player, "Always there: SansSerif, Serif, Monospaced, Dialog, DialogInput. And the server's: "
                    + String.join(", ", fonts().stream().limit(30).toList()));
            return;
        }
        String name = Commands.join(args, 0);
        trowel.session(player).setFont(name);
        Chat.info(player, "//text font: ", name);
    }

    private List<String> completeFont(Player player, String[] args) {
        List<String> names = new ArrayList<>(List.of("SansSerif", "Serif", "Monospaced", "Dialog", "DialogInput"));
        names.addAll(fonts());
        return Commands.filter(names, args[args.length - 1]).stream().limit(40).toList();
    }

    private static List<String> fonts() {
        try {
            System.setProperty("java.awt.headless", System.getProperty("java.awt.headless", "true"));
            return List.of(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        } catch (RuntimeException | Error e) {
            return List.of();
        }
    }

    // -------------------------------------------------------------------- loft

    private void loft(Player player, String[] args) {
        Session session = trowel.session(player);
        World world = player.getWorld();
        // An empty frame is the one //loft frame just opened: it waits for its points.
        session.getFrames().removeIf(frame -> !frame.isEmpty() && !world.equals(frame.get(0).getWorld()));
        List<List<Location>> frames = session.getFrames();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "frame", "f" -> {
                if (frames.isEmpty() || !frames.get(frames.size() - 1).isEmpty()) {
                    frames.add(new ArrayList<>());
                }
                Chat.info(player, "Frame " + frames.size() + " opened: //loft point on each of its points, in order.");
            }
            case "point", "p" -> {
                if (frames.isEmpty()) {
                    frames.add(new ArrayList<>());
                }
                Block block = c.target(player);
                frames.get(frames.size() - 1).add(block.getLocation());
                Chat.info(player, "Frame " + frames.size() + ", point " + frames.get(frames.size() - 1).size() + ": ",
                        block.getX() + " " + block.getY() + " " + block.getZ());
            }
            case "remove", "r" -> {
                if (frames.isEmpty()) {
                    throw new IllegalArgumentException("Nothing to remove.");
                }
                List<Location> last = frames.get(frames.size() - 1);
                if (!last.isEmpty()) {
                    last.remove(last.size() - 1);
                }
                if (last.isEmpty()) {
                    frames.remove(frames.size() - 1);
                }
                Chat.info(player, "Last loft point removed.");
            }
            case "clear", "c" -> {
                frames.clear();
                Chat.info(player, "Loft cleared.");
            }
            case "set", "s" -> loftSet(player, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                Chat.info(player, "Loft: " + frames.size() + " frame(s). //loft frame opens a frame, //loft point adds "
                        + "the aimed block to it, //loft set <pattern> stretches the surface. -o outlines, -c closes, -d goes down to the ground, -p faceted.");
            }
        }
        trowel.hud().showSelection(player);
    }

    private void loftSet(Player player, String[] args) {
        Set<String> flags = Commands.flags(args);
        String[] rest = Commands.plain(args);
        Pattern pattern = c.pattern(player, Commands.need(rest, 0, "//loft set <pattern> [-o] [-c] [-d] [-p]"));
        List<List<Location>> frames = trowel.session(player).getFrames();
        List<List<double[]>> curves = new ArrayList<>();
        for (List<Location> frame : frames) {
            List<double[]> pts = new ArrayList<>();
            for (Location l : frame) {
                pts.add(new double[]{l.getBlockX() + 0.5, l.getBlockY() + 0.5, l.getBlockZ() + 0.5});
            }
            if (pts.size() >= 2) {
                curves.add(pts);
            }
        }
        if (curves.size() < 2 && !flags.contains("o")) {
            throw new IllegalArgumentException("It takes at least two frames of at least two points.");
        }
        boolean outline = flags.contains("o");
        boolean closed = flags.contains("c");
        boolean down = flags.contains("d");
        boolean faceted = flags.contains("p");
        Box reads = null;
        for (List<double[]> curve : curves) {
            for (double[] p : curve) {
                Box cell = Box.around((int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2]), 4);
                reads = reads == null ? cell : reads.union(cell);
            }
        }
        if (reads == null) {
            throw new IllegalArgumentException("No frame.");
        }
        if (down) {
            reads = reads.expand(0, -64, 0);
        }
        Box box = reads;
        c.run(player, "Loft", reads, context -> {
            int samples = 0;
            List<double[][]> rings = new ArrayList<>();
            for (List<double[]> curve : curves) {
                double perimeter = 0;
                for (int i = 0; i < curve.size(); i++) {
                    double[] a = curve.get(i);
                    double[] b = curve.get((i + 1) % curve.size());
                    perimeter += Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
                }
                samples = Math.max(samples, (int) Math.ceil(perimeter * 3));
            }
            samples = Math.max(8, Math.min(4000, samples));
            for (List<double[]> curve : curves) {
                rings.add(ring(curve, samples, faceted));
            }
            LongOpenHashSet cells = new LongOpenHashSet();
            for (double[][] ring : rings) {
                for (double[] p : ring) {
                    cells.add(Keys.pack((int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])));
                }
            }
            if (!outline && rings.size() >= 2) {
                context.progress().phase("surface", samples);
                for (int m = 0; m < samples; m++) {
                    context.progress().tick(1);
                    List<double[]> rail = new ArrayList<>();
                    for (double[][] ring : rings) {
                        rail.add(ring[m]);
                    }
                    if (closed) {
                        rail.add(rings.get(0)[m]);
                    }
                    List<double[]> dense = faceted ? linear(rail) : com.stackmc.trowel.geom.Shapes.spline(rail);
                    for (int i = 0; i < dense.size(); i++) {
                        double[] a = dense.get(i);
                        double[] b = i + 1 < dense.size() ? dense.get(i + 1) : a;
                        double len = Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
                        int steps = Math.max(1, (int) Math.ceil(len * 3));
                        for (int s = 0; s < steps; s++) {
                            double f = (double) s / steps;
                            cells.add(Keys.pack((int) Math.floor(a[0] + (b[0] - a[0]) * f), (int) Math.floor(a[1] + (b[1] - a[1]) * f),
                                    (int) Math.floor(a[2] + (b[2] - a[2]) * f)));
                        }
                    }
                }
            }
            ChangeSet changes = context.changes();
            BlockView view = context.terrain();
            for (long key : cells) {
                int x = Keys.x(key);
                int y = Keys.y(key);
                int z = Keys.z(key);
                changes.set(x, y, z, pattern.at(x, y, z));
                if (down) {
                    for (int dy = y - 1; dy >= box.minY() && !view.solid(x, dy, z) && !cells.contains(Keys.pack(x, dy, z)); dy--) {
                        changes.set(x, dy, z, pattern.at(x, dy, z));
                    }
                }
            }
            return changes;
        });
    }

    /** A closed frame, resampled into {@code samples} regular points. */
    private static double[][] ring(List<double[]> points, int samples, boolean faceted) {
        List<double[]> closed = new ArrayList<>(points);
        closed.add(points.get(0));
        List<double[]> dense = faceted ? linear(closed) : closedSpline(points);
        double[] cumulative = new double[dense.size()];
        for (int i = 1; i < dense.size(); i++) {
            double[] a = dense.get(i - 1);
            double[] b = dense.get(i);
            cumulative[i] = cumulative[i - 1] + Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
        }
        double total = cumulative[dense.size() - 1];
        double[][] out = new double[samples][];
        int j = 0;
        for (int i = 0; i < samples; i++) {
            double s = total * i / samples;
            while (j < dense.size() - 2 && cumulative[j + 1] < s) {
                j++;
            }
            double span = cumulative[j + 1] - cumulative[j];
            double f = span <= 0 ? 0 : (s - cumulative[j]) / span;
            double[] a = dense.get(j);
            double[] b = dense.get(j + 1);
            out[i] = new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
        }
        return out;
    }

    private static List<double[]> closedSpline(List<double[]> points) {
        if (points.size() < 3) {
            List<double[]> closed = new ArrayList<>(points);
            closed.add(points.get(0));
            return linear(closed);
        }
        Path path = Path.build(points, 0, 0, 0, true, Path.Normal.CONSISTENT, 0.25);
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            out.add(path.point(i));
        }
        return out;
    }

    private static List<double[]> linear(List<double[]> points) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < points.size() - 1; i++) {
            double[] a = points.get(i);
            double[] b = points.get(i + 1);
            int steps = Math.max(1, (int) Math.ceil(Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2)
                    + Math.pow(a[2] - b[2], 2)) * 4));
            for (int s = 0; s < steps; s++) {
                double f = (double) s / steps;
                out.add(new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f});
            }
        }
        out.add(points.get(points.size() - 1));
        return out;
    }

    private static double number(String raw, String label) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + ": number expected, not '" + raw + "'.");
        }
    }
}
