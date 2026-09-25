package com.stackmc.trowel.ui;

import com.stackmc.trowel.Chat;
import com.stackmc.trowel.Session;
import com.stackmc.trowel.Trowel;
import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.engine.BlockView;
import com.stackmc.trowel.engine.ChangeSet;
import com.stackmc.trowel.engine.Engine;
import com.stackmc.trowel.engine.Sculpt;
import com.stackmc.trowel.engine.Terrain;
import com.stackmc.trowel.engine.Textures;
import com.stackmc.trowel.geom.NoiseSpec;
import com.stackmc.trowel.pattern.Mask;
import com.stackmc.trowel.pattern.Palette;
import com.stackmc.trowel.pattern.Pattern;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sculpt, deform, texture, decorate: the ezEdits volume tools, with the selections and palettes
 * that go with them.
 */
final class SculptCommands {

    static final String SCULPT = "Sculpt and surface";
    static final String DEFORM = "Deformations";
    static final String TEXTURE = "Textures";
    static final String DECOR = "Decor";
    static final String SELECTION = "Selection: tools";
    static final String PALETTES = "Palettes";

    private final Trowel trowel;
    private final Commands c;

    SculptCommands(Trowel trowel, Commands commands) {
        this.trowel = trowel;
        this.c = commands;
    }

    void define() {
        c.add("smooth3d|ezsmooth|ezsm", SCULPT, "//smooth3d <radius> [passes] [bias -1..1]",
                "Smooths volumes in three dimensions; the bias inflates (positive) or thins (negative).", (p, a) -> {
                    Box box = c.region(p);
                    int radius = Commands.integer(Commands.need(a, 0, "//smooth3d <radius> [passes] [bias]"), 1, 6, "Radius");
                    int passes = a.length > 1 ? Commands.integer(a[1], 1, 10, "Passes") : 2;
                    double bias = a.length > 2 ? clamp(number(a[2], "Bias"), -1, 1) : 0;
                    c.run(p, "3D smoothing", box.grow(radius + 1), Sculpt.smooth(box, radius, passes, bias));
                }, c.args("n:1|2|3", "n:1|2|4", "word:0|0.2|-0.2"));
        c.add("inflate|ezinflate", SCULPT, "//inflate <radius>", "Inflates the volumes of the selection.", (p, a) -> {
            Box box = c.region(p);
            double r = clamp(number(Commands.need(a, 0, "//inflate <radius>"), "Radius"), 0.5, 8);
            c.run(p, "Inflate", box.grow((int) Math.ceil(r) + 1), Sculpt.inflate(box, r));
        }, c.args("n:1|2|3"));
        c.add("deflate|ezdeflate", SCULPT, "//deflate <radius>", "Thins the volumes of the selection.", (p, a) -> {
            Box box = c.region(p);
            double r = clamp(number(Commands.need(a, 0, "//deflate <radius>"), "Radius"), 0.5, 8);
            c.run(p, "Deflate", box.grow((int) Math.ceil(r) + 1), Sculpt.inflate(box, -r));
        }, c.args("n:1|2|3"));
        c.add("surface|ezsurface|ezsu", SCULPT, "//surface <fuzzify|rockify|voronoify|noisify> <radius> [size|noise] "
                        + "[octaves] [-c|-e]",
                "Deforms the skin of volumes: fuzz, rock, Voronoi facets, or a tuned noise. -c digs, -e bulges.",
                this::surface, (p, a) -> {
                    if (a.length == 1) {
                        return Commands.filter(List.of("fuzzify", "rockify", "voronoify", "noisify"), a[0]);
                    }
                    if (a.length == 2) {
                        return Commands.filter(List.of("1", "2", "3"), a[1]);
                    }
                    if (a.length == 3 && a[0].equalsIgnoreCase("noisify")) {
                        return Commands.completeNoiseSpec(a[2]);
                    }
                    return Commands.filter(List.of("8", "12", "-c", "-e"), a[a.length - 1]);
                });

        c.add("voronoialize|voronoi", DEFORM, "//voronoialize [size] [gap] [seed]",
                "Splits volumes into separate Voronoi cells: slabs, shattered rocks.", (p, a) -> {
                    Box box = c.region(p);
                    double size = a.length > 0 ? clamp(number(a[0], "Size"), 2, 64) : 12;
                    double gap = a.length > 1 ? clamp(number(a[1], "Gap"), 0, 8) : 1;
                    long seed = a.length > 2 ? (long) number(a[2], "Seed") : ThreadLocalRandom.current().nextInt();
                    c.run(p, "Voronoi", box, Sculpt.voronoialize(box, size, gap, seed));
                }, c.args("n:6|12|20", "n:0.5|1|2", "n:1"));
        c.add("hexagonalize|hexa", DEFORM, "//hexagonalize [size] [gap] [angle]",
                "Hexagonal columns at the average height of the relief: basalt columns.", (p, a) -> {
                    Box box = c.region(p);
                    double size = a.length > 0 ? clamp(number(a[0], "Size"), 2, 64) : 6;
                    double gap = a.length > 1 ? clamp(number(a[1], "Gap"), 0, 4) : 0.4;
                    double angle = a.length > 2 ? number(a[2], "Angle") : 0;
                    c.run(p, "Hexagons", box, Sculpt.hexagonalize(box, size, gap, angle));
                }, c.args("n:4|6|10", "n:0|0.4|1", "n:0|30"));
        c.add("voxelize|pixelize", DEFORM, "//voxelize <size[,y,z]> [gap] [disorder 0..1]",
                "Turns volumes into big cubes of their majority material.", (p, a) -> {
                    Box box = c.region(p);
                    String[] sizes = Commands.need(a, 0, "//voxelize <size[,y,z]> [gap] [disorder]").split(",");
                    int sx = Commands.integer(sizes[0], 2, 32, "Size");
                    int sy = sizes.length > 1 ? Commands.integer(sizes[1], 1, 32, "Size") : sx;
                    int sz = sizes.length > 2 ? Commands.integer(sizes[2], 1, 32, "Size") : sx;
                    int gap = a.length > 1 ? Commands.integer(a[1], 0, Math.min(sx, sz) - 1, "Gap") : 0;
                    double distortion = a.length > 2 ? clamp(number(a[2], "Disorder"), 0, 1) : 0;
                    c.run(p, "Voxels", box.grow(Math.max(sx, sz)), Sculpt.voxelize(box, sx, sy, sz, gap, distortion,
                            ThreadLocalRandom.current().nextInt()));
                }, c.args("n:3|4|3,2,3|6", "n:0|1", "n:0|0.3"));
        c.add("noisedeform|ndeform", DEFORM, "//noisedeform <noise> [strength] [-h|-v]",
                "Warps volumes with a noise; -h horizontally, -v vertically only.", (p, a) -> {
                    Set<String> flags = Commands.flags(a);
                    String[] rest = Commands.plain(a);
                    Box box = c.region(p);
                    NoiseSpec spec = NoiseSpec.parse(Commands.need(rest, 0, "//noisedeform <noise> [strength]"), 0.08);
                    double strength = rest.length > 1 ? clamp(number(rest[1], "Strength"), 0.5, 16) : 2;
                    int axes = flags.contains("h") ? 1 : flags.contains("v") ? 2 : 0;
                    c.run(p, "Deform", box.grow((int) Math.ceil(strength) + 1), Sculpt.noiseDeform(box, spec, strength, axes));
                }, (p, a) -> a.length == 1 ? Commands.completeNoiseSpec(a[0])
                        : Commands.filter(List.of("1", "2", "4", "-h", "-v"), a[a.length - 1]));
        c.add("twist", DEFORM, "//twist <degrees> [x|y|z]", "Twists the selection around its axis, from 0 to the given angle.",
                (p, a) -> rotate(p, a, true, false), c.args("n:45|90|180|360", "word:y|x|z"));
        c.add("rotatesel|rotateregion", DEFORM, "//rotatesel <degrees> [x|y|z]",
                "Rotates the content of the selection by any angle around its center.",
                (p, a) -> rotate(p, a, false, false), c.args("n:15|30|45", "word:y|x|z"));
        c.add("taper", DEFORM, "//taper <factor> [x|y|z]",
                "Tapers (below 1) or flares (above 1) the selection along an axis.",
                (p, a) -> rotate(p, a, false, true), c.args("n:0.3|0.5|1.5", "word:y|x|z"));

        c.add("texture|eztexture|ezt", TEXTURE, "//texture <type> <mask> <palette> [setting:value...]",
                "Repaints a build by light, hollows, slope, a noise... //texture help.",
                this::texture, this::completeTexture);

        c.add("vines|ezvines", DECOR, "//vines <mask> <pattern> [% chance] [min length] [max]",
                "Hangs vines under the blocks the mask selects.", this::vines,
                c.args("mask", "pattern", "n:10|25|50", "n:2", "n:6"));
        c.add("moss|ezmoss", DECOR, "//moss <pattern> [amount %] [smoothing]",
                "Organic moss patches on top of volumes.", this::moss,
                c.args("pattern", "n:30|50|70", "n:1|2|3"));
        c.add("heightmap", DECOR, "//heightmap <palette> <noise> [scale] [height %]",
                "A relief drawn from a noise, painted bottom to top by the palette.", this::heightmap,
                (p, a) -> a.length == 1 ? c.completePattern(a[0]) : a.length == 2 ? Commands.completeNoiseSpec(a[1])
                        : Commands.filter(List.of("16", "32", "64", "50", "100"), a[a.length - 1]));

        c.add("next", SELECTION, "//next [direction] [gap]", "Shifts the selection by its own size.", (p, a) -> {
            Box box = c.selection(p);
            int[] d = Commands.direction(p, a.length > 0 ? a[0] : null);
            int gap = a.length > 1 ? Commands.integer(a[1], -256, 256, "Gap") : 0;
            Box moved = box.shift(d[0] * (box.width() + gap), d[1] * (box.height() + gap), d[2] * (box.depth() + gap));
            trowel.session(p).select(p.getWorld(), moved);
            trowel.hud().showSelection(p);
            Chat.info(p, "Selection shifted: ", moved.size());
        }, c.args("dir", "n:0|1|2"));
        c.add("selhere|seltome", SELECTION, "//selhere [pos1|pos2|center]", "Brings the selection to your feet.", (p, a) -> {
            Box box = c.selection(p);
            Block f = Commands.feet(p);
            String anchor = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "pos1";
            Session session = trowel.session(p);
            int ax;
            int ay;
            int az;
            if (anchor.equals("center")) {
                ax = (box.minX() + box.maxX()) / 2;
                ay = box.minY();
                az = (box.minZ() + box.maxZ()) / 2;
            } else {
                Location corner = anchor.equals("pos2") ? session.getPos2() : session.getPos1();
                ax = corner.getBlockX();
                ay = corner.getBlockY();
                az = corner.getBlockZ();
            }
            Box moved = box.shift(f.getX() - ax, f.getY() - ay, f.getZ() - az);
            session.select(p.getWorld(), moved);
            trowel.hud().showSelection(p);
            Chat.info(p, "Selection moved: ", moved.size());
        }, c.args("word:pos1|pos2|center"));
        c.add("encapsulate|enc", SELECTION, "//enc <mask>", "Shrinks the selection around the blocks the mask selects.",
                (p, a) -> encapsulate(p, c.selection(p), Commands.join(a, 0)), c.args("mask"));
        c.add("selnear|encnear", SELECTION, "//selnear <radius> <mask>", "Selects, around you, what the mask selects.",
                (p, a) -> {
                    int r = Commands.integer(Commands.need(a, 0, "//selnear <radius> <mask>"), 1, 64, "Radius");
                    Block f = Commands.feet(p);
                    Box around = Box.around(f.getX(), f.getY(), f.getZ(), r);
                    Box bounds = trowel.host().bounds(p.getWorld());
                    if (bounds != null) {
                        around = around.intersect(bounds);
                        if (around == null) {
                            throw new IllegalArgumentException("Outside the buildable area.");
                        }
                    }
                    encapsulate(p, around, Commands.join(a, 1));
                }, c.args("n:8|16|32", "mask"));

        c.add("palette|ezpalette|ezp", PALETTES, "//palette <list|show|place|swap|save|delete|sort> ...",
                "Ordered palettes: view, place in a row, swap in the selection, save (##name).",
                this::palette, this::completePalette);
    }

    // -------------------------------------------------------------- sculpting

    private void surface(Player player, String[] args) {
        Set<String> flags = Commands.flags(args);
        String[] rest = Commands.plain(args);
        String usage = "//surface <fuzzify|rockify|voronoify|noisify> <radius> [size|noise] [octaves] [-c|-e]";
        Sculpt.Surface kind;
        try {
            kind = Sculpt.Surface.valueOf(Commands.need(rest, 0, usage).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
        double radius = clamp(number(Commands.need(rest, 1, usage), "Radius"), 0.5, 8);
        Box box = c.region(player);
        NoiseSpec noise = null;
        double size = 10;
        int octaves = 1;
        if (kind == Sculpt.Surface.NOISIFY) {
            noise = NoiseSpec.parse(rest.length > 2 ? rest[2] : "perlin(fo:3)", 0.1);
        } else if (rest.length > 2) {
            size = clamp(number(rest[2], "Size"), 1, 64);
        } else if (kind == Sculpt.Surface.VORONOIFY) {
            size = 12;
        }
        if (rest.length > 3) {
            octaves = Commands.integer(rest[3], 1, 6, "Octaves");
        }
        int mode = flags.contains("c") ? 1 : flags.contains("e") ? 2 : 0;
        c.run(player, "Surface " + kind.name().toLowerCase(Locale.ROOT), box.grow((int) Math.ceil(radius) + 2),
                Sculpt.surface(box, kind, radius, size, octaves, noise, mode));
    }

    private void rotate(Player player, String[] args, boolean twist, boolean taper) {
        String usage = twist ? "//twist <degrees> [axis]" : taper ? "//taper <factor> [axis]" : "//rotatesel <degrees> [axis]";
        Box box = c.region(player);
        double value = number(Commands.need(args, 0, usage), taper ? "Factor" : "Angle");
        char axis = args.length > 1 ? Character.toLowerCase(args[1].charAt(0)) : 'y';
        if ("xyz".indexOf(axis) < 0) {
            throw new IllegalArgumentException("Axis: x, y or z.");
        }
        if (taper) {
            value = clamp(value, 0.05, 4);
        }
        String label = twist ? "Twist" : taper ? "Taper" : "Rotation";
        c.run(player, label, box, Sculpt.rotate(box, axis, taper ? 0 : value, twist, taper ? value : 1));
    }

    // ---------------------------------------------------------------- textures

    private void texture(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            Chat.info(player, "//texture <type> <mask> <palette> [setting:value...]: the start of the palette goes to "
                    + "light and bumps, the end to hollows and shade (-##palette reverses).");
            for (Textures.Kind kind : Textures.Kind.values()) {
                player.sendMessage(Chat.suggest("//texture " + kind.id() + " #existing ##grayscale", kind.help()));
            }
            Chat.hint(player, "Settings: radius:3 brightness:0.1 contrast:0.5 dir:0.3,-1,0.2 interval:0,180 shadows:0.3 "
                    + "axis:y relative:true amount:20 noise:perlin(f:0.1) range:40 dither:0.6");
            return;
        }
        String usage = "//texture <type> <mask> <palette> [setting:value...]";
        Textures.Kind kind = Textures.Kind.parse(args[0]);
        Mask mask = c.mask(player, Commands.need(args, 1, usage));
        Palette palette = c.palette(player, Commands.need(args, 2, usage));
        Map<String, String> named = new HashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            int colon = args[i].indexOf(':');
            String key = colon > 0 ? args[i].substring(0, colon).toLowerCase(Locale.ROOT) : null;
            if (key != null && key.chars().allMatch(Character::isLetter) && !key.equals("perlin")) {
                named.put(key, args[i].substring(colon + 1));
            } else {
                positional.add(args[i]);
            }
        }
        double radius = value(named, positional, 0, "radius", "r", kind == Textures.Kind.SUN || kind == Textures.Kind.LIGHT ? 2 : 3);
        double brightness = value(named, positional, 1, "brightness", "b", 0);
        double contrast = value(named, positional, 2, "contrast", "c", 0);
        double[] vector;
        Block eye = player.getEyeLocation().getBlock();
        if (kind == Textures.Kind.LIGHT) {
            vector = named.containsKey("pos") ? vector(named.get("pos")) : new double[]{eye.getX() + 0.5, eye.getY() + 0.5,
                    eye.getZ() + 0.5};
        } else {
            vector = named.containsKey("dir") ? vector(named.get("dir")) : named.containsKey("d")
                    ? vector(named.get("d")) : new double[]{0.35, -1, 0.25};
        }
        double[] interval = kind == Textures.Kind.LIGHT ? new double[]{0, 90} : new double[]{0, 180};
        String intervalText = named.getOrDefault("interval", named.get("i"));
        if (intervalText != null) {
            String[] parts = intervalText.split(",");
            interval = new double[]{number(parts[0], "Interval"), number(parts.length > 1 ? parts[1] : "180", "Interval")};
        }
        double shadows = named.containsKey("shadows") ? number(named.get("shadows"), "Shadows")
                : named.containsKey("o") ? number(named.get("o"), "Shadows") : 0;
        char axis = named.containsKey("axis") ? Character.toLowerCase(named.get("axis").charAt(0))
                : positional.stream().filter(p -> p.length() == 1 && "xyz".contains(p)).map(p -> p.charAt(0)).findFirst().orElse('y');
        boolean relative = "true".equalsIgnoreCase(named.get("relative")) || Commands.flags(args).contains("r");
        double amount = value(named, positional, kind == Textures.Kind.SHIFT || kind == Textures.Kind.CELLS
                || kind == Textures.Kind.DEPTH ? 0 : 99, "amount", "n", kind == Textures.Kind.SHIFT ? 1
                : kind == Textures.Kind.DEPTH ? 4 : 24);
        NoiseSpec noise = kind == Textures.Kind.NOISE ? NoiseSpec.parse(named.getOrDefault("noise",
                positional.isEmpty() ? "perlin(f:0.08,fo:3)" : positional.get(0)), 0.08) : null;
        double range = named.containsKey("range") ? number(named.get("range"), "Range") : 0;
        double dither = named.containsKey("dither") ? number(named.get("dither"), "Dither") : 0.6;
        Box box = c.region(player);
        Textures.Settings settings = new Textures.Settings(kind, radius, brightness, contrast, vector, interval, shadows,
                axis, relative, amount, noise, range, dither);
        c.run(player, "Texture " + kind.id(), box.grow((int) Math.ceil(radius) + 2), Textures.texture(box, mask, palette, settings));
    }

    private static double value(Map<String, String> named, List<String> positional, int index, String name, String alias,
                                double fallback) {
        String raw = named.containsKey(name) ? named.get(name) : named.get(alias);
        if (raw == null && index < positional.size()) {
            raw = positional.get(index);
        }
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private List<String> completeTexture(Player player, String[] args) {
        String last = args[args.length - 1];
        if (args.length == 1) {
            List<String> kinds = new ArrayList<>(Arrays.stream(Textures.Kind.values()).map(Textures.Kind::id).toList());
            kinds.add("help");
            return Commands.filter(kinds, last);
        }
        if (args.length == 2) {
            return c.completeMask(last);
        }
        if (args.length == 3) {
            return c.completePattern(last);
        }
        return Commands.filter(List.of("radius:", "brightness:", "contrast:", "dir:0.3,-1,0.2", "interval:0,180",
                "shadows:0.3", "axis:y", "relative:true", "amount:", "noise:perlin(f:0.1)", "range:", "dither:0"), last);
    }

    // ------------------------------------------------------------------ decor

    private void vines(Player player, String[] args) {
        String usage = "//vines <mask> <pattern> [% chance] [min length] [max]";
        Mask mask = c.mask(player, Commands.need(args, 0, usage));
        Pattern pattern = c.pattern(player, Commands.need(args, 1, usage));
        int chance = args.length > 2 ? Commands.integer(args[2], 1, 100, "Chance") : 10;
        int min = args.length > 3 ? Commands.integer(args[3], 1, 64, "Length") : 2;
        int max = args.length > 4 ? Commands.integer(args[4], min, 64, "Length") : Math.max(min, 5);
        Box box = c.region(player);
        c.run(player, "Vines", box.expand(0, -max, 0), context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.view();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            context.progress().phase("vines", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        if (view.air(x, y, z) || !view.air(x, y - 1, z) || !mask.test(x, y, z, view)
                                || random.nextInt(100) >= chance) {
                            continue;
                        }
                        int length = min + random.nextInt(max - min + 1);
                        for (int i = 1; i <= length && view.air(x, y - i, z); i++) {
                            changes.set(x, y - i, z, pattern.at(x, y - i, z));
                        }
                    }
                }
            }
            return changes;
        });
    }

    private void moss(Player player, String[] args) {
        String usage = "//moss <pattern> [amount %] [smoothing]";
        Pattern pattern = c.pattern(player, Commands.need(args, 0, usage));
        double amount = (args.length > 1 ? Commands.integer(args[1], 1, 100, "Amount") : 50) / 100.0;
        int smoothing = args.length > 2 ? Commands.integer(args[2], 0, 6, "Smoothing") : 2;
        Box box = c.region(player);
        NoiseSpec noise = NoiseSpec.parse("perlin(fo:3,s:-1)", 0.12);
        c.run(player, "Moss", box, context -> {
            ChangeSet changes = context.changes();
            BlockView view = context.terrain();
            int w = box.width();
            int d = box.depth();
            int[] top = new int[w * d];
            boolean[] cover = new boolean[w * d];
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    int h = Terrain.surface(view, box.minX() + x, box.minZ() + z, box.maxY(), box.minY());
                    top[x + w * z] = h;
                    if (h != Terrain.NONE) {
                        double v = noise.sample(box.minX() + x, h, box.minZ() + z) * 0.85 + random.nextDouble() * 0.15;
                        cover[x + w * z] = v > 1 - amount;
                    }
                }
            }
            for (int pass = 0; pass < smoothing; pass++) {
                boolean[] next = cover.clone();
                for (int x = 0; x < w; x++) {
                    for (int z = 0; z < d; z++) {
                        int count = 0;
                        int total = 0;
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                int nx = x + dx;
                                int nz = z + dz;
                                if (nx < 0 || nz < 0 || nx >= w || nz >= d || top[nx + w * nz] == Terrain.NONE) {
                                    continue;
                                }
                                total++;
                                if (cover[nx + w * nz]) {
                                    count++;
                                }
                            }
                        }
                        next[x + w * z] = total > 0 && count * 2 > total;
                    }
                }
                cover = next;
            }
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    int h = top[x + w * z];
                    if (h != Terrain.NONE && cover[x + w * z]) {
                        changes.set(box.minX() + x, h, box.minZ() + z, pattern.at(box.minX() + x, h, box.minZ() + z));
                    }
                }
            }
            return changes;
        });
    }

    private void heightmap(Player player, String[] args) {
        String usage = "//heightmap <palette> <noise> [scale] [height %]";
        Palette palette = c.palette(player, Commands.need(args, 0, usage));
        double scale = args.length > 2 ? clamp(number(args[2], "Scale"), 1, 512) : 32;
        NoiseSpec noise = NoiseSpec.parse(Commands.need(args, 1, usage), 1 / scale);
        double strength = (args.length > 3 ? Commands.integer(args[3], 1, 100, "Height") : 100) / 100.0;
        Box box = c.region(player);
        c.run(player, "Noise relief", box, context -> {
            ChangeSet changes = context.changes();
            int height = box.height();
            context.progress().phase("relief", box.width());
            for (int x = box.minX(); x <= box.maxX(); x++) {
                context.progress().tick(1);
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    int top = box.minY() + (int) Math.round(noise.sample(x, 0, z) * strength * (height - 1));
                    for (int y = box.minY(); y <= box.maxY(); y++) {
                        BlockData data = y > top ? context.air()
                                : palette.pick((y - box.minY() + 0.5) / Math.max(1, top - box.minY() + 1));
                        changes.set(x, y, z, data);
                    }
                }
            }
            return changes;
        });
    }

    // --------------------------------------------------------------- selection

    private void encapsulate(Player player, Box area, String rawMask) {
        if (rawMask.isBlank()) {
            throw new IllegalArgumentException("Give a mask: //enc stone, //enc #existing...");
        }
        Mask mask = c.mask(player, rawMask);
        World world = player.getWorld();
        trowel.engine().read(player, world, area, "Encapsulate", context -> {
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                    Integer.MIN_VALUE};
            context.progress().phase("searching", area.width());
            for (int x = area.minX(); x <= area.maxX(); x++) {
                context.progress().tick(1);
                for (int y = area.minY(); y <= area.maxY(); y++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        if (mask.test(x, y, z, context.view())) {
                            b[0] = Math.min(b[0], x);
                            b[1] = Math.min(b[1], y);
                            b[2] = Math.min(b[2], z);
                            b[3] = Math.max(b[3], x);
                            b[4] = Math.max(b[4], y);
                            b[5] = Math.max(b[5], z);
                        }
                    }
                }
            }
            return b[0] == Integer.MAX_VALUE ? null : new Box(b[0], b[1], b[2], b[3], b[4], b[5]);
        }, found -> {
            if (found == null) {
                Chat.error(player, "Nothing matches the mask here.");
                return;
            }
            trowel.session(player).select(world, found);
            trowel.hud().showSelection(player);
            Chat.info(player, "Selection shrunk: ", found.size() + " (" + found.volume() + " blocks)");
        });
    }

    // ---------------------------------------------------------------- palettes

    private void palette(Player player, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                Chat.info(player, "Ready-made palettes (click: view). Write them ##name; -##name reversed.");
                Component line = Component.text("  ", NamedTextColor.GRAY);
                int count = 0;
                for (Map.Entry<String, String> entry : Palette.PRESETS.entrySet()) {
                    line = line.append(Component.text("##" + entry.getKey() + " ", NamedTextColor.GOLD)
                            .clickEvent(ClickEvent.runCommand("/trowel palette show ##" + entry.getKey()))
                            .hoverEvent(HoverEvent.showText(Component.text(entry.getValue().replace(",", "\n"),
                                    NamedTextColor.GRAY))));
                    if (++count % 8 == 0) {
                        player.sendMessage(line);
                        line = Component.text("  ", NamedTextColor.GRAY);
                    }
                }
                player.sendMessage(line);
                Map<String, String> mine = trowel.palettes().of(player.getUniqueId());
                if (!mine.isEmpty()) {
                    Chat.info(player, "Yours: ", "##" + String.join(", ##", mine.keySet()));
                }
            }
            case "show", "print" -> {
                Palette palette = c.palette(player, Commands.need(args, 1, "//palette show <palette>"));
                Chat.info(player, palette.size() + " block(s):");
                List<String> names = palette.blocks().stream().map(data -> data.getAsString(true).replace("minecraft:", ""))
                        .toList();
                player.sendMessage(Chat.suggest(String.join(",", names), "click: type"));
            }
            case "sort" -> {
                Palette palette = c.palette(player, Commands.need(args, 1, "//palette sort <palette>"));
                List<BlockData> sorted = new ArrayList<>(palette.blocks());
                sorted.sort((a, b) -> Double.compare(luminance(a), luminance(b)));
                List<String> names = sorted.stream().map(data -> data.getAsString(true).replace("minecraft:", "")).toList();
                Chat.info(player, "From darkest to lightest:");
                player.sendMessage(Chat.suggest(String.join(",", names), "click: type"));
            }
            case "place" -> {
                Palette palette = c.palette(player, Commands.need(args, 1, "//palette place <palette> [direction]"));
                int[] d = Commands.direction(player, args.length > 2 ? args[2] : null);
                Block start = c.target(player).getRelative(0, 1, 0);
                int n = palette.size();
                Box box = new Box(start.getX(), start.getY(), start.getZ(), start.getX() + d[0] * (n - 1),
                        start.getY() + d[1] * (n - 1), start.getZ() + d[2] * (n - 1));
                c.run(player, "Palette", box, context -> {
                    ChangeSet changes = context.changes();
                    for (int i = 0; i < n; i++) {
                        changes.set(start.getX() + d[0] * i, start.getY() + d[1] * i, start.getZ() + d[2] * i, palette.get(i));
                    }
                    return changes;
                });
            }
            case "swap" -> {
                String usage = "//palette swap <from> <to> [-a]";
                Palette from = c.palette(player, Commands.need(args, 1, usage));
                Palette to = c.palette(player, Commands.need(args, 2, usage));
                boolean skipAir = Commands.flags(args).contains("a");
                Map<org.bukkit.Material, Integer> index = new HashMap<>();
                for (int i = from.size() - 1; i >= 0; i--) {
                    index.put(from.get(i).getMaterial(), i);
                }
                Box box = c.region(player);
                int fn = from.size();
                c.run(player, "Palette swap", box, context -> {
                    ChangeSet changes = context.changes();
                    context.progress().phase("swapping", box.width());
                    for (int x = box.minX(); x <= box.maxX(); x++) {
                        context.progress().tick(1);
                        for (int y = box.minY(); y <= box.maxY(); y++) {
                            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                                org.bukkit.Material type = context.view().type(x, y, z);
                                Integer i = index.get(type);
                                if (i == null || (skipAir && type.isAir())) {
                                    continue;
                                }
                                changes.set(x, y, z, to.pick((i + 0.5) / fn));
                            }
                        }
                    }
                    return changes;
                });
            }
            case "edit", "editor" -> {
                String name = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "mypalette";
                if (!name.matches("[a-z0-9_]{1,24}")) {
                    throw new IllegalArgumentException("Name: letters, digits and _, 24 at most.");
                }
                trowel.paletteEditor().open(player, name);
            }
            case "save" -> {
                String name = Commands.need(args, 1, "//palette save <name> <palette>").toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z0-9_]{1,24}")) {
                    throw new IllegalArgumentException("Name: letters, digits and _, 24 at most.");
                }
                String raw = Commands.join(args, 2);
                if (raw.isBlank()) {
                    throw new IllegalArgumentException("Usage: //palette save <name> <palette>");
                }
                Palette palette = c.palette(player, raw);
                trowel.palettes().put(player.getUniqueId(), name, String.join(",", palette.blocks().stream()
                        .map(data -> data.getAsString(true).replace("minecraft:", "")).toList()));
                Chat.info(player, "Palette saved: ", "##" + name + " (" + palette.size() + " blocks)");
            }
            case "delete", "remove" -> {
                String name = Commands.need(args, 1, "//palette delete <name>");
                if (!trowel.palettes().remove(player.getUniqueId(), name)) {
                    throw new IllegalArgumentException("You have no palette ##" + name + ".");
                }
                Chat.info(player, "Palette deleted: ", "##" + name);
            }
            default -> throw new IllegalArgumentException("//palette list, show <palette>, sort <palette>, place <palette> "
                    + "[direction], swap <from> <to>, save <name> <palette>, delete <name>");
        }
    }

    private static double luminance(BlockData data) {
        org.bukkit.Color color = data.getMapColor();
        return 0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue();
    }

    private List<String> completePalette(Player player, String[] args) {
        String last = args[args.length - 1];
        if (args.length == 1) {
            return Commands.filter(List.of("list", "show", "sort", "place", "swap", "edit", "save", "delete"), last);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("delete")) {
            return Commands.filter(new ArrayList<>(trowel.palettes().of(player.getUniqueId()).keySet()), last);
        }
        if (sub.equals("save") && args.length == 2) {
            return List.of("name");
        }
        if (sub.equals("place") && args.length == 3) {
            return Commands.filter(List.of("me", "up", "north", "south", "east", "west"), last);
        }
        return c.completePattern(last);
    }

    // ------------------------------------------------------------------- tools

    private static double[] vector(String raw) {
        String[] parts = raw.replace("(", "").replace(")", "").split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Vector expected: x,y,z.");
        }
        return new double[]{number(parts[0], "Vector"), number(parts[1], "Vector"), number(parts[2], "Vector")};
    }

    private static double number(String raw, String label) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + ": number expected, not '" + raw + "'.");
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
