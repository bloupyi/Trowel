package com.stackmc.trowel.ui;

import com.stackmc.trowel.Chat;
import com.stackmc.trowel.Session;
import com.stackmc.trowel.Trowel;
import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.axiom.AxiomBridge;
import com.stackmc.trowel.brush.BrushSettings;
import com.stackmc.trowel.brush.BrushType;
import com.stackmc.trowel.engine.Clipboard;
import com.stackmc.trowel.engine.Engine;
import com.stackmc.trowel.engine.Generators;
import com.stackmc.trowel.geom.Noise;
import com.stackmc.trowel.geom.Relief;
import com.stackmc.trowel.pattern.Palettes;
import com.stackmc.trowel.engine.RegionOps;
import com.stackmc.trowel.geom.Shapes;
import com.stackmc.trowel.geom.Transform;
import com.stackmc.trowel.pattern.Mask;
import com.stackmc.trowel.pattern.Masks;
import com.stackmc.trowel.pattern.Pattern;
import com.stackmc.trowel.pattern.Patterns;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The commands, in WorldEdit syntax: {@code //set stone}, {@code //copy}...
 *
 * <p>Each action can also be reached with {@code /trowel <action>}. Double slash commands are
 * registered on the fly, like WorldEdit does: a plugin descriptor cannot declare them.</p>
 */
public final class Commands {

    public static final String PERMISSION = "trowel.use";

    public static final String PATTERN_HELP = "Patterns: stone, oak_slab[type=top], 60%stone,40%andesite, a marker "
            + "(checkpoint), #hand, #hotbar, #aim, ##magma (ordered palettes: //palette list), #noise[palette][noise], "
            + "#cracks[palette][scale], #local[palette][noise], #expr[palette][expression], #gradient[palette][axis], "
            + "#vgradient, #rgradient, #random[palette], #stripes, #clipboard, #color[#ff8800]. Older forms: "
            + "#noise:8:a,b, #voronoi:6:a,b, #gradient:a,b, #palette:stone";
    public static final String MASK_HELP = "Masks: stone,dirt, !air, #existing, #solid, #surface, #exposed, #wall, "
            + "#ceiling, #floor, #fullblock, #lightsource, #attached, #marker, #marker:kill, #angle:0:40, #slope[0][40], "
            + "#y:60:80, #noise[noise][threshold], #cracks[8][50], #near[mask][3], #above[mask][2], #below, #prox, "
            + "#palette[palette], #color[red][30], #ygradient[60][90], #ambient[4], >grass_block, <stone, ~water@2, %30, "
            + "=expression (last). Combine with & or spaces.";
    public static final String NOISE_HELP = "Noises: fractal, smooth, ridged, billow, warp, terrace, marble, strata, cells, "
            + "distance, cracks, rings, waves, columns, drips, spiral, checker, hexagons, dither, white, turbulence, electric. "
            + "Tuned: perlin(f:0.1,ft:ridged,fo:4), cellular(cr:edge,cj:0.8), gabor(go:45), value(st:5)... "
            + "Reliefs: hills, mountains, plains, dunes, mesa, islands, craters, canyons, volcanoes, valleys, warped. "
            + "Expressions: //functions.";

    private static final String SELECTION = "Selection";
    private static final String REGION = "Selection: operations";
    private static final String SHAPES = "Shapes";
    private static final String LINES = "Lines and curves";
    private static final String NOISE = "Terrain and noise";
    private static final String NEAR = "Around me";
    private static final String CLIPBOARD = "Clipboard";
    private static final String HISTORY = "History";
    private static final String BRUSHES = "Brushes";
    private static final String SETTINGS = "Settings";

    private static final List<String> DIRECTIONS = List.of("me", "up", "down", "north", "south", "east", "west");
    private static final List<String> PATTERN_SPECIALS = List.of("#hand", "#hotbar", "#aim", "#noise[", "#local[",
            "#expr[", "#expri[", "#gradient[", "#vgradient[", "#rgradient[", "#random[", "#stripes[", "#clipboard",
            "#cracks[", "#cells[", "#marble[", "#ridged[", "#smoothcells[", "#voronoiedge[", "##", "#noise:8:",
            "#voronoi:6:", "#gradient:", "#palette:");
    private static final List<String> MASK_SPECIALS = List.of("#air", "#existing", "#solid", "#surface", "#exposed",
            "#marker", "#marker:", "#angle:0:40", "#y:", "#noise:fractal:8:55", "!", ">", "<", "~", "%50");

    private final Trowel trowel;
    private final Map<String, Action> actions = new LinkedHashMap<>();
    private final Map<String, String> aliases = new HashMap<>();
    private List<String> blockNames;

    private record Action(String name, String category, String usage, String description, Handler handler,
                          Completer completer) {
    }

    @FunctionalInterface
    interface Handler {
        void run(Player player, String[] args);
    }

    @FunctionalInterface
    interface Completer {
        List<String> complete(Player player, String[] args);
    }

    private final LibraryCommands library;

    public Commands(Trowel trowel) {
        this.library = new LibraryCommands(trowel, this);
        this.trowel = trowel;
        define();
    }

    // ------------------------------------------------------------ registration

    /** Registers {@code /trowel} and each {@code //action}. */
    public void register() {
        List<Command> commands = new ArrayList<>();
        commands.add(new Bridge("trowel", null, List.of()));
        for (Action action : actions.values()) {
            List<String> extra = aliases.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(action.name()))
                    .map(entry -> "/" + entry.getKey()).toList();
            commands.add(new Bridge("/" + action.name(), action.name(), extra));
        }
        trowel.plugin().getServer().getCommandMap().registerAll("trowel", commands);
    }

    private final class Bridge extends Command {

        private final String action;

        private Bridge(String name, String action, List<String> aliasList) {
            super(name);
            this.action = action;
            setAliases(aliasList);
            setPermission(PERMISSION);
            Action known = action == null ? null : actions.get(action);
            setDescription(known == null ? "Trowel building tools" : known.description());
            setUsage(known == null ? "/trowel <action>" : known.usage());
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Trowel only works in game.");
                return true;
            }
            if (action == null) {
                if (args.length == 0) {
                    help(player);
                } else {
                    dispatch(player, args[0], Arrays.copyOfRange(args, 1, args.length));
                }
            } else {
                dispatch(player, action, args);
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!(sender instanceof Player player)) {
                return List.of();
            }
            if (action == null) {
                if (args.length <= 1) {
                    String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                    return actions.keySet().stream().filter(name -> name.startsWith(prefix)).toList();
                }
                return complete(player, args[0], Arrays.copyOfRange(args, 1, args.length));
            }
            return complete(player, action, args);
        }
    }

    // ---------------------------------------------------------------- relaying

    public boolean has(String action) {
        return resolve(action) != null;
    }

    /** The actions starting like this, for a caller relaying completion. */
    public List<String> actions(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return actions.keySet().stream().filter(name -> name.startsWith(lower)).toList();
    }

    /** A whole line, as a dialog button sends it: {@code set stone}. */
    public void execute(Player player, String line) {
        String[] parts = line.trim().split("\\s+");
        dispatch(player, parts[0], Arrays.copyOfRange(parts, 1, parts.length));
    }

    public void dispatch(Player player, String name, String[] args) {
        Action action = resolve(name);
        if (action == null) {
            Chat.error(player, "Unknown action: " + name + ". //help for the list.");
            return;
        }
        if (!Set.of("help", "axiom", "palettes", "functions", "cancel", "schem", "pattern", "mask", "coedit")
                .contains(action.name())) {
            String denied = trowel.host().denyEdit(player);
            if (denied != null) {
                Chat.error(player, denied);
                return;
            }
        }
        try {
            action.handler().run(player, args);
        } catch (IllegalArgumentException e) {
            Chat.error(player, e.getMessage());
        }
    }

    public List<String> complete(Player player, String name, String[] args) {
        Action action = resolve(name);
        if (action == null || action.completer() == null || args.length == 0) {
            return List.of();
        }
        return action.completer().complete(player, args);
    }

    private Action resolve(String name) {
        if (name == null) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT);
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        return actions.get(aliases.getOrDefault(key, key));
    }

    void add(String names, String category, String usage, String description, Handler handler,
             Completer completer) {
        String[] all = names.split("\\|");
        actions.put(all[0], new Action(all[0], category, usage, description, handler, completer));
        for (int i = 1; i < all.length; i++) {
            aliases.put(all[i], all[0]);
        }
    }

    // ----------------------------------------------------------------- actions

    private void define() {
        add("wand", SELECTION, "//wand", "Gives the selection wand.", (p, a) -> {
            give(p, trowel.items().wand());
            Chat.info(p, "Wand given: left click and right click on two corners, from afar too.");
        }, null);
        add("pos1", SELECTION, "//pos1", "First corner at your feet.", (p, a) -> corner(p, feet(p), true), null);
        add("pos2", SELECTION, "//pos2", "Second corner at your feet.", (p, a) -> corner(p, feet(p), false), null);
        add("hpos1", SELECTION, "//hpos1", "First corner on the aimed block.", (p, a) -> corner(p, target(p), true), null);
        add("hpos2", SELECTION, "//hpos2", "Second corner on the aimed block.", (p, a) -> corner(p, target(p), false), null);
        add("sel|desel", SELECTION, "//sel", "Clears the selection.", (p, a) -> {
            trowel.session(p).clearSelection();
            Chat.info(p, "Selection cleared.");
        }, null);
        add("size", SELECTION, "//size", "Size of the selection.", (p, a) -> {
            Box box = selection(p);
            Chat.info(p, "Selection: ", box.size() + " (" + box.volume() + " blocks)");
            Chat.hint(p, "from " + box.minX() + " " + box.minY() + " " + box.minZ()
                    + " a " + box.maxX() + " " + box.maxY() + " " + box.maxZ());
            trowel.hud().showSelection(p);
        }, null);
        add("expand", SELECTION, "//expand <n> [direction] | //expand vert", "Pushes a face of the selection.",
                (p, a) -> reshape(p, a, "expand"), args("n:vert|1|5|10|20", "dir"));
        add("contract", SELECTION, "//contract <n> [direction]", "Pulls in the face opposite the direction.",
                (p, a) -> reshape(p, a, "contract"), args("n:5", "dir"));
        add("shift", SELECTION, "//shift <n> [direction]", "Shifts the selection without touching anything.",
                (p, a) -> reshape(p, a, "shift"), args("n:5", "dir"));
        add("outset", SELECTION, "//outset <n>", "Grows by n blocks on every face.",
                (p, a) -> reshape(p, a, "outset"), args("n:1"));
        add("inset", SELECTION, "//inset <n>", "Shrinks by n blocks on every face.",
                (p, a) -> reshape(p, a, "inset"), args("n:1"));
        add("count", SELECTION, "//count <mask>", "Counts the matching blocks.", this::count, args("mask"));
        add("distr", SELECTION, "//distr", "Block distribution of the selection.", this::distribution, null);

        add("set", REGION, "//set <pattern>", "Fills the selection.", (p, a) -> {
            Box box = region(p);
            run(p, "Fill", box, RegionOps.set(box, pattern(p, need(a, 0, "//set <pattern>"))));
        }, args("pattern"));
        add("replace|rep", REGION, "//replace [mask] <pattern>", "Replaces what the mask selects (everything but air by default).",
                (p, a) -> {
                    need(a, 0, "//replace [mask] <pattern>");
                    Box box = region(p);
                    Mask mask = mask(p, a.length == 1 ? "#existing" : a[0]);
                    Pattern pattern = pattern(p, a.length == 1 ? a[0] : a[1]);
                    run(p, "Replace", box, RegionOps.replace(box, mask, pattern));
                }, args("mask|pattern", "pattern"));
        add("walls", REGION, "//walls <pattern>", "The four walls of the selection.", (p, a) -> {
            Box box = region(p);
            run(p, "Walls", box, RegionOps.walls(box, pattern(p, need(a, 0, "//walls <pattern>"))));
        }, args("pattern"));
        add("outline|faces", REGION, "//outline <pattern>", "The six faces of the selection.", (p, a) -> {
            Box box = region(p);
            run(p, "Outline", box, RegionOps.outline(box, pattern(p, need(a, 0, "//outline <pattern>"))));
        }, args("pattern"));
        add("overlay", REGION, "//overlay <pattern> [thickness]", "Places on top of the relief.", (p, a) -> {
            Box box = region(p);
            Pattern pattern = pattern(p, need(a, 0, "//overlay <pattern> [thickness]"));
            int depth = a.length > 1 ? integer(a[1], 1, 16, "Thickness") : 1;
            run(p, "Overlay", box.expand(0, depth, 0), RegionOps.overlay(box, pattern, depth));
        }, args("pattern", "n:1"));
        add("center|middle", REGION, "//center <pattern>", "The center block or blocks.", (p, a) -> {
            Box box = region(p);
            run(p, "Center", box, RegionOps.center(box, pattern(p, need(a, 0, "//center <pattern>"))));
        }, args("pattern"));
        add("hollow", REGION, "//hollow [thickness] [pattern]", "Hollows solid volumes, keeping a shell.", (p, a) -> {
            Box box = region(p);
            int thickness = a.length > 0 ? integer(a[0], 1, 16, "Thickness") : 1;
            Pattern fill = pattern(p, a.length > 1 ? a[1] : "air");
            run(p, "Hollow", box, RegionOps.hollow(box, thickness, fill));
        }, args("n:1", "pattern"));
        add("naturalize", REGION, "//naturalize", "Grass on the surface, three layers of dirt, then stone.", (p, a) -> {
            Box box = region(p);
            run(p, "Naturalize", box, RegionOps.naturalize(box));
        }, null);
        add("smooth", REGION, "//smooth [passes] [mask]", "Smooths the relief of the selection.", (p, a) -> {
            Box box = region(p);
            int passes = a.length > 0 ? integer(a[0], 1, 20, "Passes") : 1;
            Mask mask = a.length > 1 ? mask(p, join(a, 1)) : null;
            run(p, "Smooth", box, RegionOps.smooth(box, passes, mask));
        }, args("n:2", "mask"));
        add("move", REGION, "//move <n> [direction]", "Moves the selection and its content, markers included.", (p, a) -> {
            Box box = region(p);
            int n = integer(need(a, 0, "//move <n> [direction]"), 1, 256, "Distance");
            int[] d = direction(p, a.length > 1 ? a[1] : null);
            Box moved = box.shift(d[0] * n, d[1] * n, d[2] * n);
            World world = p.getWorld();
            trowel.engine().submit(p, Engine.Job.of("Move", world, box.union(moved),
                    RegionOps.move(box, d[0] * n, d[1] * n, d[2] * n)).unprotected().then(count -> {
                trowel.session(p).select(world, moved);
                trowel.hud().showSelection(p);
            }));
        }, args("n:5", "dir"));
        add("stack", REGION, "//stack <count> [direction] [-a]", "Repeats the selection in a row. -a: without air.", (p, a) -> {
            Set<String> flags = flags(a);
            String[] rest = plain(a);
            Box box = region(p);
            int count = integer(need(rest, 0, "//stack <count> [direction] [-a]"), 1, 64, "Count");
            int[] d = direction(p, rest.length > 1 ? rest[1] : null);
            Box end = box.shift(d[0] * box.width() * count, d[1] * box.height() * count, d[2] * box.depth() * count);
            trowel.engine().submit(p, Engine.Job.of("Stack", p.getWorld(), box.union(end),
                    RegionOps.stack(box, count, d[0], d[1], d[2], flags.contains("a"))).unprotected());
        }, args("n:3", "dir", "flag:-a"));
        add("typereplace|trep", REGION, "//typereplace <from> <to>", "oak to spruce: changes wood type keeping the shapes.",
                (p, a) -> {
                    need(a, 1, "//typereplace <from> <to>");
                    Box box = region(p);
                    run(p, "Wood type change", box, RegionOps.typeReplace(box, a[0], a[1]));
                }, args("word:oak", "word:spruce"));
        add("fixconnect|fc", REGION, "//fixconnect", "Redoes the connections of fences, panes, bars and walls.",
                (p, a) -> {
                    Box box = region(p);
                    run(p, "Connections", box, RegionOps.fixConnections(box));
                }, null);

        add("line", LINES, "//line <pattern> [thickness]", "A line from the first to the second corner.",
                (p, a) -> curve(p, a, "line"), args("pattern", "n:1"));
        add("rope|curve", LINES, "//rope <pattern> [sag] [thickness]", "A rope hanging between both corners.",
                (p, a) -> curve(p, a, "rope"), args("pattern", "n:4", "n:1"));
        add("arch", LINES, "//arch <pattern> [height] [width] [thickness]", "An arch bridge between both corners.",
                (p, a) -> curve(p, a, "arch"), args("pattern", "n:6", "n:3", "n:1"));
        add("sphere", SHAPES, "//sphere <pattern> <radius>[,ry,rz]", "A ball centered at your feet.",
                (p, a) -> sphere(p, a, false), args("pattern", "n:3|5|8|5,3,5|8,4,8"));
        add("hsphere", SHAPES, "//hsphere <pattern> <radius>[,ry,rz]", "A hollow ball.",
                (p, a) -> sphere(p, a, true), args("pattern", "n:3|5|8|5,3,5|8,4,8"));
        add("cyl", SHAPES, "//cyl <pattern> <radius>[,rz] [height]", "A cylinder at your feet.",
                (p, a) -> cylinder(p, a, false), args("pattern", "n:3|5|8|6,3", "n:1|5|10|-5"));
        add("hcyl", SHAPES, "//hcyl <pattern> <radius>[,rz] [height]", "A hollow cylinder.",
                (p, a) -> cylinder(p, a, true), args("pattern", "n:3|5|8|6,3", "n:1|5|10|-5"));
        add("pyramid", SHAPES, "//pyramid <pattern> <size>", "A pyramid at your feet.",
                (p, a) -> pyramid(p, a, false), args("pattern", "n:5"));
        add("hpyramid", SHAPES, "//hpyramid <pattern> <size>", "A hollow pyramid.",
                (p, a) -> pyramid(p, a, true), args("pattern", "n:5"));
        add("cone", SHAPES, "//cone <pattern> <radius> <height>", "A cone at your feet, tip up.",
                (p, a) -> cone(p, a, false), args("pattern", "n:5", "n:10"));
        add("hcone", SHAPES, "//hcone <pattern> <radius> <height>", "A hollow cone.",
                (p, a) -> cone(p, a, true), args("pattern", "n:5", "n:10"));

        add("noise|noisegen", NOISE, "//noise <pattern|palette> <noise> <scale> [threshold %] [-a]",
                "Fills where the noise exceeds the threshold; a palette spreads over the noise value. -a: air only.",
                this::noise, args("pattern", "noisespec", "n:4|8|16|32", "n:40|55|65|75", "flag:-a"));
        add("carve", NOISE, "//carve <noise> <scale> [threshold %]",
                "Carves the selection where the noise exceeds the threshold: caves, holes, sponge.",
                this::carve, args("noisespec", "n:4|8|16|32", "n:50|60|70"));
        add("terrain|terragen", NOISE, "//terrain <relief> [scale] [strength %] [top|under|deep]",
                "Generates relief in the selection, like Arceon's Terragen.",
                this::terrain, args("relief", "n:16|32|64|128", "n:50|75|100", "layers"));
        add("roughen", NOISE, "//roughen <noise> [scale] [amplitude]", "Makes the relief of the selection less smooth.",
                this::roughen, args("noisespec", "n:4|6|10", "n:1|2|4"));
        add("scatter|flora", NOISE, "//scatter <pattern> [density %] [mask]",
                "Scatters on the surface of the selection: grass, flowers, pebbles.",
                this::scatter, args("pattern", "n:5|10|20|40", "mask"));
        add("snow", NOISE, "//snow [radius]", "Snow on the selection, or around you if you give a radius.",
                (p, a) -> weather(p, a, "snow"), args("n:10"));
        add("thaw", NOISE, "//thaw [radius]", "Melts snow and ice (selection, or radius).",
                (p, a) -> weather(p, a, "thaw"), args("n:10"));
        add("green", NOISE, "//green [radius]", "Dirt open to the sky becomes grass (selection, or radius).",
                (p, a) -> weather(p, a, "green"), args("n:10"));
        add("palettes", NOISE, "//palettes", "Ready-made palettes, to use as #palette:name.",
                (p, a) -> palettes(p), null);

        add("replacenear", NEAR, "//replacenear <radius> <mask> <pattern>", "Replaces around you.", (p, a) -> {
            int r = integer(need(a, 0, "//replacenear <radius> <mask> <pattern>"), 1, 64, "Radius");
            Mask mask = mask(p, need(a, 1, "//replacenear <radius> <mask> <pattern>"));
            Pattern pattern = pattern(p, need(a, 2, "//replacenear <radius> <mask> <pattern>"));
            Block f = feet(p);
            Box box = Box.around(f.getX(), f.getY(), f.getZ(), r);
            run(p, "Replace nearby", box, RegionOps.replace(box, mask, pattern));
        }, args("n:5", "mask", "pattern"));
        add("removenear", NEAR, "//removenear <mask> [radius]", "Removes around you what the mask selects.",
                (p, a) -> {
                    Mask mask = mask(p, need(a, 0, "//removenear <mask> [radius]"));
                    int r = a.length > 1 ? integer(a[1], 1, 64, "Radius") : 5;
                    Block f = feet(p);
                    Box box = Box.around(f.getX(), f.getY(), f.getZ(), r);
                    org.bukkit.block.data.BlockData air = trowel.air();
                    run(p, "Remove nearby", box, RegionOps.replace(box, mask, (x, y, z) -> air));
                }, args("mask", "n:5"));
        add("removeabove", NEAR, "//removeabove [radius] [height]", "Clears above your head.",
                (p, a) -> column(p, a, true), args("n:2", "n:16"));
        add("removebelow", NEAR, "//removebelow [radius] [depth]", "Clears below your feet.",
                (p, a) -> column(p, a, false), args("n:2", "n:16"));
        add("fill", NEAR, "//fill <pattern> <radius> [depth]",
                "Fills the hole you stand in, spreading and going down, never up.", (p, a) -> {
                    Pattern pattern = pattern(p, need(a, 0, "//fill <pattern> <radius> [depth]"));
                    int r = integer(need(a, 1, "//fill <pattern> <radius> [depth]"), 1, 64, "Radius");
                    int depth = a.length > 2 ? integer(a[2], 1, 128, "Depth") : r;
                    Block f = feet(p);
                    Box box = new Box(f.getX() - r, f.getY() - depth, f.getZ() - r, f.getX() + r, f.getY(), f.getZ() + r);
                    run(p, "Fill hole", box, Generators.fillHole(f.getX(), f.getY(), f.getZ(), r, depth, pattern));
                }, args("pattern", "n:5", "n:5"));

        add("copy", CLIPBOARD, "//copy", "Copies the selection, around your feet.", (p, a) -> copy(p, false), null);
        add("cut", CLIPBOARD, "//cut", "Copies then clears the selection.", (p, a) -> copy(p, true), null);
        add("paste", CLIPBOARD, "//paste [-a] [-o] [-s]",
                "Pastes at your feet. -a without air, -o at the original place, -s selects the paste.",
                this::paste, args("flag:-a|-o|-s", "flag:-a|-o|-s", "flag:-a|-o|-s"));
        add("rotate", CLIPBOARD, "//rotate <90|180|270>", "Rotates the clipboard, markers included.", (p, a) -> {
            Transform turn = Transform.rotation(integer(need(a, 0, "//rotate <90|180|270>"), -360, 360, "Angle"));
            if (turn == null) {
                throw new IllegalArgumentException("Only quarter turns are possible: 90, 180, 270.");
            }
            transformClipboard(p, turn, "Rotated by " + a[0] + " degrees.");
        }, args("word:90|180|270"));
        add("flip", CLIPBOARD, "//flip [direction]", "Flips the clipboard, towards where you look by default.", (p, a) -> {
            int[] d = direction(p, a.length > 0 ? a[0] : null);
            Transform flip = d[0] != 0 ? Transform.FLIP_X : d[2] != 0 ? Transform.FLIP_Z : Transform.FLIP_Y;
            transformClipboard(p, flip, "Flipped.");
        }, args("dir"));
        add("clearclipboard", CLIPBOARD, "//clearclipboard", "Clears the clipboard.", (p, a) -> {
            trowel.session(p).setClipboard(null);
            Chat.info(p, "Clipboard cleared.");
        }, null);

        add("undo", HISTORY, "//undo [n] | only <n> | sel [n] | brush",
                "Undoes the latest operations; only <n> the n-th alone, sel inside the selection, brush the last gesture of the brush in hand.",
                (p, a) -> library.undo(p, a), args("word:1|2|only|sel|brush"));
        add("redo", HISTORY, "//redo [n]", "Redoes what was undone.",
                (p, a) -> trowel.engine().redo(p, a.length > 0 ? integer(a[0], 1, 100, "Count") : 1), args("n:1"));
        add("cancel|stop", HISTORY, "//cancel", "Stops the running operation: computing at once, placing at the next tick.",
                (p, a) -> {
                    if (trowel.engine().cancel(p)) {
                        Chat.info(p, "Stop requested.");
                    } else {
                        Chat.error(p, "No operation running.");
                    }
                }, null);

        add("brush|br", BRUSHES, "//brush <type> [radius] [pattern]",
                "Gives a brush. //brush size|mask|pattern|type tunes the one in hand.", this::brush, this::completeBrush);
        add("brushes", BRUSHES, "//brushes", "Pick a brush from the list.",
                (p, a) -> trowel.dialogs().openKit(p, false), null);

        add("gmask", SETTINGS, "//gmask [mask]", "Mask applied to everything; without an argument, it is removed.", (p, a) -> {
            Session session = trowel.session(p);
            if (a.length == 0) {
                session.setGlobalMask(null);
                Chat.info(p, "Global mask removed.");
                return;
            }
            String raw = join(a, 0);
            mask(p, raw);
            session.setGlobalMask(raw);
            Chat.info(p, "Global mask: ", raw);
        }, args("mask"));
        add("markers", SETTINGS, "//markers [protect|edit]", "Protects markers from fills and brushes.",
                (p, a) -> {
                    Session session = trowel.session(p);
                    boolean edit = a.length == 0 ? !session.isEditMarkers() : a[0].equalsIgnoreCase("edit");
                    session.setEditMarkers(edit);
                    Chat.info(p, edit ? "Operations can now replace markers."
                            : "Markers are protected: only copy, paste and move touch them.");
                }, args("word:protect|edit"));
        add("noclip|nc", SETTINGS, "//noclip [on|off]",
                "Walk and fly through blocks in creative: spectator while a block is in the way, creative again once out.",
                (p, a) -> {
                    Session session = trowel.session(p);
                    session.setNoclip(a.length == 0 ? !session.isNoclip() : a[0].equalsIgnoreCase("on"));
                    Chat.info(p, session.isNoclip() ? "Noclip on: walk or fly into a block to pass through it."
                            : "Noclip off.");
                }, args("word:on|off"));
        add("axiom", SETTINGS, "//axiom", "State of the Axiom compatibility for you.", (p, a) -> axiomStatus(p), null);
        add("coedit|coop", SETTINGS, "//coedit [show|share] [on|off]",
                "Co-editing: see the selection and aimed block of the other builders in the same world (show), show yours (share).",
                (p, a) -> {
                    Session session = trowel.session(p);
                    String what = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
                    Boolean value = a.length > 1 ? Boolean.valueOf(a[1].equalsIgnoreCase("on")) : null;
                    if (what.equals("show")) {
                        session.setCoeditShow(value == null ? !session.isCoeditShow() : value);
                    } else if (what.equals("share")) {
                        session.setCoeditShare(value == null ? !session.isCoeditShare() : value);
                    }
                    Chat.info(p, "Co-editing: ", "see the others " + (session.isCoeditShow() ? "on" : "off")
                            + ", share yours " + (session.isCoeditShare() ? "on" : "off"));
                }, args("word:show|share", "word:on|off"));
        add("menu|tools", SETTINGS, "//menu", "Every operation, in a window (also on the G key).",
                (p, a) -> trowel.dialogs().openHub(p), null);
        add("help", SETTINGS, "//help", "This help.", (p, a) -> help(p), null);
        new AdvancedCommands(trowel, this).define();
        new SculptCommands(trowel, this).define();
        new ToolCommands(trowel, this).define();
        library.define();
    }

    // --------------------------------------------------------------- selection

    private void corner(Player player, Block block, boolean first) {
        Session session = trowel.session(player);
        if (first) {
            session.setPos1(block.getLocation());
        } else {
            session.setPos2(block.getLocation());
        }
        Box box = session.selection(player.getWorld());
        String where = block.getX() + " " + block.getY() + " " + block.getZ();
        Chat.info(player, (first ? "First" : "Second") + " corner: ",
                box == null ? where : where + "  (" + box.size() + ", " + box.volume() + " blocks)");
        trowel.hud().showSelection(player);
    }

    private void reshape(Player player, String[] args, String mode) {
        Box box = selection(player);
        World world = player.getWorld();
        Box result;
        if (mode.equals("expand") && args.length > 0 && args[0].equalsIgnoreCase("vert")) {
            Box bounds = trowel.host().bounds(world);
            result = box.withY(bounds == null ? world.getMinHeight() : bounds.minY(),
                    bounds == null ? world.getMaxHeight() - 1 : bounds.maxY());
        } else if (mode.equals("outset") || mode.equals("inset")) {
            int n = integer(need(args, 0, "//" + mode + " <n>"), 1, 256, "Count");
            Box inner = box.contract(n, n, n);
            result = mode.equals("outset") ? box.grow(n) : inner == null ? null : inner.contract(-n, -n, -n);
        } else {
            int n = integer(need(args, 0, "//" + mode + " <n> [direction]"), 1, 1024, "Count");
            int[] d = direction(player, args.length > 1 ? args[1] : null);
            result = switch (mode) {
                case "expand" -> box.expand(d[0] * n, d[1] * n, d[2] * n);
                case "contract" -> box.contract(d[0] * n, d[1] * n, d[2] * n);
                default -> box.shift(d[0] * n, d[1] * n, d[2] * n);
            };
        }
        if (result == null) {
            throw new IllegalArgumentException("The selection would vanish.");
        }
        trowel.session(player).select(world, result);
        Chat.info(player, "Selection: ", result.size() + " (" + result.volume() + " blocks)");
        trowel.hud().showSelection(player);
    }

    private void count(Player player, String[] args) {
        Box box = region(player);
        Mask mask = mask(player, args.length == 0 ? "#existing" : join(args, 0));
        trowel.engine().read(player, player.getWorld(), box, "Count",
                context -> RegionOps.count(context, box, mask),
                count -> Chat.info(player, "Matching: ", count + " block(s) out of " + box.volume()));
    }

    private void distribution(Player player, String[] args) {
        Box box = region(player);
        trowel.engine().read(player, player.getWorld(), box, "Distribution",
                context -> RegionOps.distribution(context, box), counts -> {
                    long total = counts.values().stream().mapToLong(Long::longValue).sum();
                    Chat.info(player, "Distribution over ", total + " blocks:");
                    counts.entrySet().stream().sorted(Map.Entry.<Material, Long>comparingByValue().reversed())
                            .limit(12).forEach(entry -> player.sendMessage(Component.text(String.format(Locale.ROOT,
                                    "  %5.1f %%  ", 100.0 * entry.getValue() / total), NamedTextColor.AQUA)
                                    .append(Component.text(entry.getKey().getKey().getKey()
                                            + " (" + entry.getValue() + ")", NamedTextColor.GRAY))));
                });
    }

    // ------------------------------------------------------------------ shapes

    private void curve(Player player, String[] args, String kind) {
        Session session = trowel.session(player);
        World world = player.getWorld();
        Location a = session.getPos1();
        Location b = session.getPos2();
        if (a == null || b == null || !world.equals(a.getWorld()) || !world.equals(b.getWorld())) {
            throw new IllegalArgumentException("Set both corners: the shape goes from the first to the second.");
        }
        Pattern pattern = pattern(player, need(args, 0, "//" + kind + " <pattern> ..."));
        double length = a.toVector().distance(b.toVector());
        Box ends = new Box(a.getBlockX(), a.getBlockY(), a.getBlockZ(), b.getBlockX(), b.getBlockY(), b.getBlockZ());
        switch (kind) {
            case "line" -> {
                double thickness = args.length > 1 ? integer(args[1], 1, 16, "Thickness") : 1;
                List<double[]> points = Shapes.curve(a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                        b.getBlockX(), b.getBlockY(), b.getBlockZ(), 0);
                Box bounds = ends.grow((int) thickness);
                run(player, "Line", bounds, RegionOps.shape(pattern, bounds, cell -> Shapes.thicken(points, thickness, cell)));
            }
            case "rope" -> {
                double sag = args.length > 1 ? integer(args[1], 0, 128, "Sag") : Math.max(1, length / 6);
                double thickness = args.length > 2 ? integer(args[2], 1, 16, "Thickness") : 1;
                List<double[]> points = Shapes.curve(a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                        b.getBlockX(), b.getBlockY(), b.getBlockZ(), -sag);
                Box bounds = ends.grow((int) thickness).expand(0, -(int) Math.ceil(sag) - 1, 0);
                run(player, "Rope", bounds, RegionOps.shape(pattern, bounds, cell -> Shapes.thicken(points, thickness, cell)));
            }
            default -> {
                double height = args.length > 1 ? integer(args[1], 1, 128, "Height") : Math.max(2, length / 3);
                int width = args.length > 2 ? integer(args[2], 1, 32, "Width") : 3;
                int thickness = args.length > 3 ? integer(args[3], 1, 16, "Thickness") : 1;
                double dx = b.getBlockX() - a.getBlockX();
                double dz = b.getBlockZ() - a.getBlockZ();
                double norm = Math.sqrt(dx * dx + dz * dz);
                double sideX = norm == 0 ? 1 : -dz / norm;
                double sideZ = norm == 0 ? 0 : dx / norm;
                List<double[]> points = Shapes.curve(a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                        b.getBlockX(), b.getBlockY(), b.getBlockZ(), height);
                Box bounds = ends.grow(width + thickness).expand(0, (int) Math.ceil(height) + 1, 0);
                run(player, "Arch", bounds, RegionOps.shape(pattern, bounds,
                        cell -> Shapes.band(points, sideX, sideZ, width, thickness, cell)));
            }
        }
    }

    private void sphere(Player player, String[] args, boolean hollow) {
        Pattern pattern = pattern(player, need(args, 0, "//sphere <pattern> <radius>"));
        double[] radii = radii(need(args, 1, "//sphere <pattern> <radius>"), 3);
        Block center = feet(player);
        int reach = (int) Math.ceil(Math.max(radii[0], Math.max(radii[1], radii[2]))) + 1;
        Box bounds = Box.around(center.getX(), center.getY(), center.getZ(), reach);
        run(player, hollow ? "Hollow sphere" : "Sphere", bounds, RegionOps.shape(pattern, bounds,
                cell -> Shapes.ellipsoid(center.getX(), center.getY(), center.getZ(), radii[0], radii[1], radii[2],
                        hollow, cell)));
    }

    private void cylinder(Player player, String[] args, boolean hollow) {
        Pattern pattern = pattern(player, need(args, 0, "//cyl <pattern> <radius> [height]"));
        double[] radii = radii(need(args, 1, "//cyl <pattern> <radius> [height]"), 2);
        int height = args.length > 2 ? integer(args[2], -256, 256, "Height") : 1;
        Block base = feet(player);
        int reach = (int) Math.ceil(Math.max(radii[0], radii[1])) + 1;
        Box bounds = Box.around(base.getX(), base.getY(), base.getZ(), reach).expand(0, height, 0);
        run(player, hollow ? "Hollow cylinder" : "Cylinder", bounds, RegionOps.shape(pattern, bounds,
                cell -> Shapes.cylinder(base.getX(), base.getY(), base.getZ(), radii[0], radii[1], height, hollow, cell)));
    }

    private void cone(Player player, String[] args, boolean hollow) {
        Pattern pattern = pattern(player, need(args, 0, "//cone <pattern> <radius> <height>"));
        double radius = radii(need(args, 1, "//cone <pattern> <radius> <height>"), 1)[0];
        int height = integer(need(args, 2, "//cone <pattern> <radius> <height>"), 1, 256, "Height");
        Block base = feet(player);
        Box bounds = Box.around(base.getX(), base.getY(), base.getZ(), (int) Math.ceil(radius) + 1).expand(0, height, 0);
        run(player, hollow ? "Hollow cone" : "Cone", bounds, RegionOps.shape(pattern, bounds,
                cell -> Shapes.cone(base.getX(), base.getY(), base.getZ(), radius, height, hollow, cell)));
    }

    private void noise(Player player, String[] args) {
        Set<String> flags = flags(args);
        String[] rest = plain(args);
        String usage = "//noise <pattern|palette> <noise> <scale> [threshold %] [-a]";
        Box box = region(player);
        String material = need(rest, 0, usage);
        double scale = integer(need(rest, 2, usage), 1, 256, "Scale");
        com.stackmc.trowel.geom.NoiseSpec spec = com.stackmc.trowel.geom.NoiseSpec.parse(need(rest, 1, usage), 1 / scale);
        double threshold = (rest.length > 3 ? integer(rest[3], 0, 100, "Threshold") : 55) / 100.0;
        Generators.Fill fill;
        if (material.startsWith("#") && !material.startsWith("##")) {
            Pattern pattern = pattern(player, material).within(box);
            fill = (x, y, z, v) -> pattern.at(x, y, z);
        } else {
            com.stackmc.trowel.pattern.Palette palette = palette(player, material);
            fill = (x, y, z, v) -> palette.size() == 1 ? palette.get(0) : palette.value(v);
        }
        run(player, "Noise", box, Generators.noiseFill(box, fill, spec, threshold, flags.contains("a")));
    }

    private void carve(Player player, String[] args) {
        String usage = "//carve <noise> <scale> [threshold %]";
        Box box = region(player);
        double scale = integer(need(args, 1, usage), 1, 256, "Scale");
        com.stackmc.trowel.geom.NoiseSpec spec = com.stackmc.trowel.geom.NoiseSpec.parse(need(args, 0, usage), 1 / scale);
        double threshold = (args.length > 2 ? integer(args[2], 0, 100, "Threshold") : 60) / 100.0;
        run(player, "Carve", box, Generators.noiseCarve(box, spec, threshold));
    }

    private void terrain(Player player, String[] args) {
        String usage = "//terrain <relief> [scale] [strength %] [top|under|deep]";
        Box box = region(player);
        Relief relief = Relief.parse(need(args, 0, usage));
        if (relief == null) {
            throw new IllegalArgumentException("Reliefs: " + Arrays.stream(Relief.values()).map(Relief::id)
                    .collect(Collectors.joining(", ")));
        }
        double scale = args.length > 1 ? integer(args[1], 2, 512, "Scale") : 32;
        int strength = args.length > 2 ? integer(args[2], 1, 100, "Strength") : 100;
        String[] layers = (args.length > 3 ? args[3] : "grass_block|dirt|stone").split("\\|");
        Pattern top = pattern(player, layers[0]);
        Pattern under = pattern(player, layers.length > 1 ? layers[1] : "dirt");
        Pattern deep = pattern(player, layers.length > 2 ? layers[2] : "stone");
        run(player, "Relief " + relief.label(), box, Generators.terrain(box, relief, scale, strength, top, under, deep));
    }

    private void roughen(Player player, String[] args) {
        String usage = "//roughen <noise> [scale] [amplitude]";
        Box box = region(player);
        double scale = args.length > 1 ? integer(args[1], 1, 256, "Scale") : 6;
        com.stackmc.trowel.geom.NoiseSpec spec = com.stackmc.trowel.geom.NoiseSpec.parse(need(args, 0, usage), 1 / scale);
        int amplitude = args.length > 2 ? integer(args[2], 1, 32, "Amplitude") : 2;
        run(player, "Roughen", box, Generators.roughen(box, spec, amplitude));
    }

    private void scatter(Player player, String[] args) {
        String usage = "//scatter <pattern> [density %] [mask]";
        Box box = region(player);
        Pattern pattern = pattern(player, need(args, 0, usage));
        int density = args.length > 1 ? integer(args[1], 1, 100, "Density") : 20;
        Mask on = args.length > 2 ? mask(player, join(args, 2)) : null;
        run(player, "Scatter", box.expand(0, 1, 0), Generators.scatter(box, pattern, density, on));
    }

    /** Snow, thaw and grass: on the selection, or within a radius around the player if they give one. */
    private void weather(Player player, String[] args, String kind) {
        Box box;
        if (args.length > 0) {
            int r = integer(args[0], 1, 64, "Radius");
            Block f = feet(player);
            box = Box.around(f.getX(), f.getY(), f.getZ(), r);
        } else {
            box = region(player);
        }
        switch (kind) {
            case "snow" -> run(player, "Snow", box.expand(0, 1, 0), Generators.snow(box));
            case "thaw" -> run(player, "Thaw", box, Generators.thaw(box));
            default -> run(player, "Grass", box, Generators.green(box));
        }
    }

    /** Clears a square column above the head or below the feet. */
    private void column(Player player, String[] args, boolean above) {
        int r = args.length > 0 ? integer(args[0], 0, 32, "Radius") : 2;
        int height = args.length > 1 ? integer(args[1], 1, 256, above ? "Height" : "Depth") : 16;
        Block f = feet(player);
        Box box = above
                ? new Box(f.getX() - r, f.getY() + 2, f.getZ() - r, f.getX() + r, f.getY() + 1 + height, f.getZ() + r)
                : new Box(f.getX() - r, f.getY() - height, f.getZ() - r, f.getX() + r, f.getY() - 1, f.getZ() + r);
        org.bukkit.block.data.BlockData air = trowel.air();
        run(player, above ? "Clear above" : "Clear below", box, RegionOps.set(box, (x, y, z) -> air));
    }

    private void axiomStatus(Player player) {
        AxiomBridge bridge = trowel.axiom();
        if (!trowel.settings().axiomEnabled()) {
            Chat.info(player, "Axiom compatibility disabled (config.yml, axiom.enabled).");
            return;
        }
        if (!bridge.present()) {
            Chat.info(player, "AxiomPaper is not installed on this server.");
            return;
        }
        if (!bridge.hooked()) {
            Chat.error(player, "AxiomPaper is present but Trowel could not hook into it"
                    + (bridge.failure() == null ? "." : ": " + bridge.failure()));
            return;
        }
        Chat.info(player, "Axiom for you: ", bridge.activeFor(player) ? "active here" : "paused");
        Chat.hint(player, bridge.usesAxiom(player) ? "Your client has Axiom." : "Your client does not have Axiom, or is not connected yet.");
        Chat.hint(player, "Active only where you are allowed to build, "
                + "and only inside the buildable area.");
    }

    private void palettes(Player player) {
        Chat.info(player, "Palettes (#palette:name):");
        Palettes.ALL.forEach((name, list) -> player.sendMessage(Chat.suggest("#palette:" + name, list)));
    }

    private static Noise.Kind noiseKind(String raw) {
        Noise.Kind kind = Noise.Kind.parse(raw);
        if (kind == null) {
            throw new IllegalArgumentException("Unknown noise: " + raw + ". Noises: " + noiseList());
        }
        return kind;
    }

    private void pyramid(Player player, String[] args, boolean hollow) {
        Pattern pattern = pattern(player, need(args, 0, "//pyramid <pattern> <size>"));
        int size = integer(need(args, 1, "//pyramid <pattern> <size>"), 1, 64, "Size");
        Block base = feet(player);
        Box bounds = Box.around(base.getX(), base.getY(), base.getZ(), size).expand(0, size, 0);
        run(player, hollow ? "Hollow pyramid" : "Pyramid", bounds, RegionOps.shape(pattern, bounds,
                cell -> Shapes.pyramid(base.getX(), base.getY(), base.getZ(), size, hollow, cell)));
    }

    // --------------------------------------------------------------- clipboard

    private void copy(Player player, boolean cut) {
        Box box = selection(player);
        if (box.volume() > trowel.settings().maxBlocks(player)) {
            throw new IllegalArgumentException("Selection too large: " + box.volume() + " blocks, the maximum is "
                    + trowel.settings().maxBlocks(player) + ".");
        }
        World world = player.getWorld();
        Block origin = feet(player);
        trowel.engine().read(player, world, box, cut ? "Cut" : "Copy",
                context -> Clipboard.copy(context, box, world.getUID(), origin.getX(), origin.getY(), origin.getZ()),
                clipboard -> {
                    trowel.session(player).setClipboard(clipboard);
                    String markers = clipboard.markerCount() > 0
                            ? ", " + clipboard.markerCount() + " marker(s) with settings" : "";
                    Chat.info(player, (cut ? "Cut" : "Copy") + ": ", clipboard.size() + markers);
                    trowel.hud().flash(player, world, box);
                    if (cut) {
                        org.bukkit.block.data.BlockData air = trowel.air();
                        trowel.engine().submit(player, Engine.Job.of("Cut", world, box,
                                RegionOps.set(box, (x, y, z) -> air)).unprotected());
                    }
                });
    }

    private void paste(Player player, String[] args) {
        Set<String> flags = flags(args);
        Clipboard clipboard = trowel.session(player).getClipboard();
        if (clipboard == null) {
            throw new IllegalArgumentException("Empty clipboard: //copy first.");
        }
        World world = player.getWorld();
        int x;
        int y;
        int z;
        if (flags.contains("o")) {
            if (!world.getUID().equals(clipboard.world())) {
                throw new IllegalArgumentException("-o: the copy comes from another world.");
            }
            x = clipboard.originX();
            y = clipboard.originY();
            z = clipboard.originZ();
        } else {
            Block feet = feet(player);
            x = feet.getX();
            y = feet.getY();
            z = feet.getZ();
        }
        boolean skipAir = flags.contains("a");
        Box bounds = clipboard.boundsAt(x, y, z);
        trowel.engine().submit(player, Engine.Job.of("Paste", world, bounds, context -> {
            com.stackmc.trowel.engine.ChangeSet changes = context.changes();
            clipboard.pasteInto(changes, context.markers(), x, y, z, skipAir);
            return changes;
        }).unprotected().then(count -> {
            if (flags.contains("s")) {
                trowel.session(player).select(world, bounds);
                trowel.hud().showSelection(player);
            }
        }));
    }

    private void transformClipboard(Player player, Transform transform, String done) {
        Session session = trowel.session(player);
        if (session.getClipboard() == null) {
            throw new IllegalArgumentException("Empty clipboard: //copy first.");
        }
        session.setClipboard(session.getClipboard().transformed(transform, trowel.host().markers(),
                trowel.transforms()));
        Chat.info(player, done + " Clipboard: ", session.getClipboard().size());
    }

    // ----------------------------------------------------------------- brushes

    private void brush(Player player, String[] args) {
        if (args.length == 0) {
            trowel.dialogs().openKit(player, false);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (Set.of("size", "mask", "pattern", "type", "settings", "profile", "noise").contains(sub)) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            BrushSettings current = trowel.items().brushOf(hand);
            if (current == null) {
                throw new IllegalArgumentException("Hold a brush. //brush <type> to get one.");
            }
            BrushSettings next = switch (sub) {
                case "size" -> current.withSize(integer(need(args, 1, "//brush size <n>"), 0,
                        trowel.settings().maxBrushSize(), "Radius"));
                case "mask" -> {
                    String raw = args.length > 1 ? join(args, 1) : "";
                    if (!raw.isEmpty() && !raw.equalsIgnoreCase("none")) {
                        mask(player, raw);
                        yield current.withMask(raw);
                    }
                    yield current.withMask("");
                }
                case "pattern" -> {
                    String raw = Patterns.expand(need(args, 1, "//brush pattern <pattern>"), player);
                    pattern(player, raw);
                    yield current.withPattern(raw);
                }
                case "profile" -> {
                    List<String> shapes = BrushSettings.profilesFor(current.type());
                    String shape = need(args, 1, "//brush profile <" + String.join("|", shapes) + ">")
                            .toLowerCase(Locale.ROOT);
                    if (!shapes.contains(shape)) {
                        throw new IllegalArgumentException("Shapes: " + String.join(", ", shapes));
                    }
                    yield current.withProfile(shape);
                }
                case "noise" -> {
                    Noise.Kind kind = Noise.Kind.parse(need(args, 1, "//brush noise <type>"));
                    if (kind == null) {
                        throw new IllegalArgumentException("Noises: " + noiseList());
                    }
                    yield current.withNoise(kind.id());
                }
                case "type" -> {
                    BrushType type = BrushType.parse(need(args, 1, "//brush type <type>"));
                    if (type == null) {
                        throw new IllegalArgumentException("Unknown type. " + typeList());
                    }
                    yield current.withType(type);
                }
                default -> {
                    trowel.dialogs().openBrush(player);
                    yield null;
                }
            };
            if (next != null) {
                player.getInventory().setItemInMainHand(trowel.items().brush(next.clamp(trowel.settings().maxBrushSize())));
                Chat.info(player, "Brush set.");
            }
            return;
        }

        BrushType type = BrushType.parse(sub);
        if (type == null) {
            throw new IllegalArgumentException("Unknown type. " + typeList());
        }
        int size = args.length > 1 ? integer(args[1], 0, trowel.settings().maxBrushSize(), "Radius") : 3;
        String raw = args.length > 2 ? Patterns.expand(join(args, 2), player)
                : type == BrushType.RAISE ? null : Patterns.hotbar(player);
        if (raw != null) {
            pattern(player, raw);
        }
        BrushSettings settings = BrushSettings.defaults(type, raw).withSize(size).clamp(trowel.settings().maxBrushSize());
        giveBrush(player, settings);
    }

    /** In the hand if it is free or already holds a brush, otherwise in the inventory. */
    public void giveBrush(Player player, BrushSettings settings) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemStack brush = trowel.items().brush(settings);
        if (hand.getType().isAir() || trowel.items().brushOf(hand) != null) {
            player.getInventory().setItemInMainHand(brush);
        } else {
            give(player, brush);
        }
        Chat.info(player, "Brush: ", settings.type().getDisplayName()
                + ". Right click to paint, left click to tune it.");
    }

    private String typeList() {
        return "Types: " + Arrays.stream(BrushType.values()).map(BrushType::id).collect(Collectors.joining(", "));
    }

    private List<String> completeBrush(Player player, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("size", "mask", "pattern", "type", "settings", "profile", "noise"));
            Arrays.stream(BrushType.values()).map(BrushType::id).forEach(options::add);
            return filter(options, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "mask" -> completeMask(args[args.length - 1]);
            case "pattern" -> completePattern(args[args.length - 1]);
            case "type" -> filter(Arrays.stream(BrushType.values()).map(BrushType::id).toList(), args[1]);
            case "profile" -> {
                BrushSettings held = trowel.items().brushOf(player.getInventory().getItemInMainHand());
                yield filter(BrushSettings.profilesFor(held == null ? BrushType.RAISE : held.type()), args[1]);
            }
            case "noise" -> filter(noiseIds(), args[1]);
            case "size" -> List.of("3");
            default -> args.length == 2 ? List.of("3") : completePattern(args[args.length - 1]);
        };
    }

    // -------------------------------------------------------------------- help

    public void help(Player player) {
        Chat.info(player, "Commands: click a line to type it.");
        String category = null;
        for (Action action : actions.values()) {
            if (!action.category().equals(category)) {
                category = action.category();
                player.sendMessage(Component.text(category, NamedTextColor.DARK_AQUA));
            }
            player.sendMessage(Chat.suggest(action.usage().split(" \\| ")[0], action.description()));
        }
        player.sendMessage(Component.text(PATTERN_HELP, NamedTextColor.GRAY));
        player.sendMessage(Component.text(MASK_HELP, NamedTextColor.GRAY));
        player.sendMessage(Component.text(NOISE_HELP, NamedTextColor.GRAY));
        player.sendMessage(Component.text("G (quick actions) or //menu: everything in a window.", NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------------- tools

    void run(Player player, String label, Box reads, Engine.Compute compute) {
        trowel.engine().submit(player, Engine.Job.of(label, player.getWorld(), reads, compute));
    }

    Box selection(Player player) {
        Box box = trowel.selection(player);
        if (box == null) {
            throw new IllegalArgumentException(trowel.session(player).selection(player.getWorld()) == null
                    ? "No selection: //wand, then left click and right click on two corners."
                    : "The selection is entirely outside the buildable area.");
        }
        return box;
    }

    /**
     * The selection of an operation: cropped to the area, and not so large that its computation,
     * which walks all of it, takes seconds and memory for nothing.
     */
    Box region(Player player) {
        Box box = selection(player);
        long max = (long) trowel.settings().maxBlocks(player) * 40L;
        if (box.volume() > max) {
            throw new IllegalArgumentException("Selection too large for an operation: " + box.volume()
                    + " blocks, the maximum is " + max + ". Shrink it (//contract, //inset).");
        }
        return box;
    }

    Pattern pattern(Player player, String raw) {
        String expanded = Patterns.expand(trowel.expandPattern(player.getUniqueId(), raw), player);
        Pattern pattern = Patterns.parse(expanded, patternContext(player));
        trowel.session(player).setLastPattern(raw);
        return pattern;
    }

    /** What a pattern may need: the clipboard, and the player position for gradients. */
    Patterns.Context patternContext(Player player) {
        Block f = feet(player);
        return new Patterns.Context(trowel.host().markers(), trowel.session(player).getClipboard(), f.getX(), f.getY(),
                f.getZ());
    }

    /** An ordered palette, with {@code #hand} and friends replaced. */
    com.stackmc.trowel.pattern.Palette palette(Player player, String raw) {
        return com.stackmc.trowel.pattern.Palette.parse(Patterns.expand(trowel.palettes().expand(player.getUniqueId(),
                raw), player), trowel.host().markers());
    }

    Mask mask(Player player, String raw) {
        Mask mask = Masks.parse(trowel.expandMask(player.getUniqueId(), raw), trowel.host().markers());
        trowel.session(player).setLastMask(raw);
        return mask;
    }

    static Block feet(Player player) {
        return player.getLocation().getBlock();
    }

    Block target(Player player) {
        RayTraceResult hit = player.rayTraceBlocks(trowel.settings().reach(), FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null) {
            throw new IllegalArgumentException("No block in reach.");
        }
        return hit.getHitBlock();
    }

    /** A named direction, or the look direction: up or down if clear, otherwise horizontal. */
    static int[] direction(Player player, String raw) {
        if (raw != null && !raw.equalsIgnoreCase("me") && !raw.equalsIgnoreCase("look")) {
            int[] named = Transform.vector(raw);
            if (named == null) {
                throw new IllegalArgumentException("Unknown direction: " + raw + ". " + String.join(", ", DIRECTIONS));
            }
            return named;
        }
        float pitch = player.getLocation().getPitch();
        if (pitch < -50) {
            return new int[]{0, 1, 0};
        }
        if (pitch > 50) {
            return new int[]{0, -1, 0};
        }
        BlockFace facing = player.getFacing();
        return new int[]{facing.getModX(), 0, facing.getModZ()};
    }

    private static double[] radii(String raw, int count) {
        String[] parts = raw.split(",");
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            String part = parts[Math.min(i, parts.length - 1)];
            try {
                out[i] = Math.max(0, Math.min(64, Double.parseDouble(part.trim())));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid radius: " + raw);
            }
        }
        if (parts.length == 2 && count == 3) {
            out[2] = out[0];
        }
        return out;
    }

    static int integer(String raw, int min, int max, String label) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                throw new IllegalArgumentException(label + ": between " + min + " and " + max + ".");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " invalid: " + raw);
        }
    }

    static String need(String[] args, int index, String usage) {
        if (args.length <= index) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
        return args[index];
    }

    static String join(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    static Set<String> flags(String[] args) {
        Set<String> flags = new HashSet<>();
        for (String arg : args) {
            if (arg.startsWith("-") && arg.length() > 1 && !Character.isDigit(arg.charAt(1))) {
                for (char c : arg.substring(1).toCharArray()) {
                    flags.add(String.valueOf(c).toLowerCase(Locale.ROOT));
                }
            }
        }
        return flags;
    }

    static String[] plain(String[] args) {
        return Arrays.stream(args).filter(arg -> !(arg.startsWith("-") && arg.length() > 1
                && !Character.isDigit(arg.charAt(1)))).toArray(String[]::new);
    }

    static void give(Player player, ItemStack stack) {
        player.getInventory().addItem(stack).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    // -------------------------------------------------------------- completion

    /** Completion by position: {@code pattern}, {@code mask}, {@code dir}, {@code n:5}, {@code word:a|b}, {@code flag:-a}. */
    Completer args(String... kinds) {
        return (player, args) -> {
            int index = args.length - 1;
            String last = args[index];
            if (index >= kinds.length) {
                return kinds.length > 0 && kinds[kinds.length - 1].equals("mask") ? completeMask(last) : List.of();
            }
            String kind = kinds[index];
            if (kind.equals("pattern") || kind.equals("mask")) {
                int at = last.lastIndexOf('@');
                if (at >= 0 && (at == 0 || !Character.isLetterOrDigit(last.charAt(at - 1)))) {
                    String head = last.substring(0, at + 1);
                    String tail = last.substring(at + 1).toLowerCase(Locale.ROOT);
                    return (kind.equals("pattern") ? trowel.patterns() : trowel.masks()).of(player.getUniqueId())
                            .keySet().stream().filter(name -> name.startsWith(tail)).map(name -> head + name).toList();
                }
                return kind.equals("pattern") ? completePattern(last) : completeMask(last);
            }
            if (kind.equals("dir")) {
                return filter(DIRECTIONS, last);
            }
            if (kind.equals("layers")) {
                return filter(List.of("grass_block|dirt|stone", "sand|sandstone|stone", "snow_block|packed_ice|stone",
                        "moss_block|dirt|deepslate", "#palette:badlands|terracotta|stone"), last);
            }
            if (kind.equals("noise")) {
                return filter(noiseIds(), last);
            }
            if (kind.equals("noisespec")) {
                return completeNoiseSpec(last);
            }
            if (kind.equals("relief")) {
                return filter(Arrays.stream(Relief.values()).map(Relief::id).toList(), last);
            }
            if (kind.startsWith("n:")) {
                return filter(List.of(kind.substring(2).split("\\|")), last);
            }
            if (kind.startsWith("word:")) {
                return filter(List.of(kind.substring(5).split("\\|")), last);
            }
            if (kind.startsWith("flag:")) {
                return filter(List.of(kind.substring(5).split("\\|")), last);
            }
            if (kind.equals("mask|pattern")) {
                List<String> both = new ArrayList<>(completeMask(last));
                completePattern(last).stream().filter(option -> !both.contains(option)).forEach(both::add);
                return both.size() > 80 ? both.subList(0, 80) : both;
            }
            return List.of();
        };
    }

    List<String> completePattern(String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> bracket = completeBracket(typed);
        if (bracket != null) {
            return bracket;
        }
        List<String> structured = completeNoise(lower, false);
        if (structured != null) {
            return structured;
        }
        int palette = lower.lastIndexOf("#palette:");
        if (palette >= 0) {
            String head = lower.substring(0, palette + "#palette:".length());
            String tail = lower.substring(head.length());
            return Palettes.names().stream().filter(name -> name.startsWith(tail)).map(name -> head + name).toList();
        }
        return completeBlocks(typed, PATTERN_SPECIALS, ",%:");
    }

    /**
     * Completion inside a bracket pattern: the palette (##names), then the noise (types and
     * settings), then the scale.
     *
     * @return {@code null} if what is typed is not a bracket pattern
     */
    List<String> completeBracket(String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        int presets = lower.lastIndexOf("##");
        if (presets >= 0 && lower.indexOf(']', presets) < 0 && lower.indexOf(',', presets) < 0
                && lower.indexOf('(', presets) < 0) {
            String head = typed.substring(0, presets + 2);
            String tail = lower.substring(presets + 2);
            return com.stackmc.trowel.pattern.Palette.presets().stream().filter(name -> name.startsWith(tail))
                    .map(name -> head + name).limit(60).toList();
        }
        if (!lower.startsWith("#") || lower.indexOf('[') < 0) {
            return null;
        }
        int depth = 0;
        int args = 0;
        int lastOpen = -1;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '[') {
                if (depth == 0) {
                    args++;
                    lastOpen = i;
                }
                depth++;
            } else if (c == ']') {
                depth--;
            }
        }
        String name = lower.substring(1, lower.indexOf('['));
        if (depth == 0) {
            return List.of(typed + "[");
        }
        String head = typed.substring(0, lastOpen + 1);
        String current = typed.substring(lastOpen + 1);
        boolean noiseArg = args == 2 && Set.of("noise", "np", "local", "lnoise", "eznp").contains(name);
        if (noiseArg) {
            return completeNoiseSpec(current).stream().map(option -> head + option).toList();
        }
        if (args == 1 && !name.equals("clipboard")) {
            List<String> out = new ArrayList<>();
            if (current.isEmpty() || current.startsWith("#")) {
                com.stackmc.trowel.pattern.Palette.presets().stream().map(preset -> "##" + preset)
                        .filter(option -> option.startsWith(current.toLowerCase(Locale.ROOT)))
                        .forEach(option -> out.add(head + option));
            }
            completeBlocks(current, List.of(), ",").stream().limit(30).map(option -> head + option).forEach(out::add);
            return out;
        }
        if (args == 2 && name.equals("gradient")) {
            return filter(List.of("y", "x", "z", "-y", "radial", "sphere", "t", "u", "v", "r", "a", "1,1,0"), current)
                    .stream().map(option -> head + option + "]").toList();
        }
        if (args == 2 && (name.equals("expr") || name.equals("expri"))) {
            return filter(List.of("y/10", "noise(x/8,y/8,z/8)", "t", "1-r", "(sin(x/4)+1)/2"), current).stream()
                    .map(option -> head + option + "]").toList();
        }
        return filter(List.of("4", "8", "12", "16", "32"), current).stream().map(option -> head + option + "]").toList();
    }

    /** A tuned noise being typed: the type, then its {@code name:} settings. */
    static List<String> completeNoiseSpec(String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        int open = lower.indexOf('(');
        if (open < 0) {
            List<String> out = new ArrayList<>();
            for (String type : com.stackmc.trowel.geom.NoiseSpec.types()) {
                if (type.startsWith(lower)) {
                    out.add(type);
                    out.add(type + "(");
                }
            }
            return out;
        }
        if (lower.endsWith(")")) {
            return List.of(typed);
        }
        int cut = Math.max(lower.lastIndexOf(','), open);
        String head = typed.substring(0, cut + 1);
        String tail = lower.substring(cut + 1);
        if (tail.contains(":")) {
            return List.of(typed + ",", typed + ")");
        }
        return com.stackmc.trowel.geom.NoiseSpec.PARAMETERS.keySet().stream().filter(key -> key.startsWith(tail))
                .map(key -> head + key + ":").toList();
    }

    private static List<String> noiseIds() {
        return Arrays.stream(Noise.Kind.values()).map(Noise.Kind::id).toList();
    }

    private static String noiseList() {
        return String.join(", ", noiseIds());
    }

    List<String> completeMask(String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        int term = Math.max(lower.lastIndexOf('&'), Math.max(lower.lastIndexOf('!'),
                Math.max(lower.lastIndexOf('>'), Math.max(lower.lastIndexOf('<'), lower.lastIndexOf('~')))));
        String head = term < 0 ? "" : lower.substring(0, term + 1);
        String current = lower.substring(term + 1);
        List<String> structured = completeNoise(current, true);
        if (structured != null) {
            return structured.stream().map(option -> head + option).toList();
        }
        if (current.startsWith("#marker:")) {
            String tail = current.substring("#marker:".length());
            return trowel.host().markers().ids().stream().filter(id -> id.startsWith(tail))
                    .map(id -> head + "#marker:" + id).toList();
        }
        if (current.startsWith("#angle:")) {
            return filter(List.of("#angle:0:20", "#angle:0:40", "#angle:20:50", "#angle:40:90"), current).stream()
                    .map(option -> head + option).toList();
        }
        if (current.startsWith("#y:")) {
            return filter(List.of("#y:0:64", "#y:60:80", "#y:64:128"), current).stream()
                    .map(option -> head + option).toList();
        }
        return completeBlocks(typed, MASK_SPECIALS, ",!<>~:&");
    }

    /**
     * Noise completion, step by step: the type, then the size, then (mask) the threshold.
     *
     * @return {@code null} if what is typed is not a noise
     */
    private static List<String> completeNoise(String typed, boolean mask) {
        String prefix = typed.startsWith("#noise:") ? "#noise:" : typed.startsWith("#voronoi:") ? "#voronoi:"
                : typed.startsWith("#cracks:") ? "#cracks:" : null;
        if (prefix == null) {
            return null;
        }
        String rest = typed.substring(prefix.length());
        String[] parts = rest.split(":", -1);
        List<String> options = new ArrayList<>();
        if (prefix.equals("#noise:") && parts.length == 1) {
            noiseIds().forEach(id -> options.add(prefix + id + ":"));
            if (!mask) {
                List.of("4:", "8:", "16:").forEach(size -> options.add(prefix + size));
            }
            return filter(options, typed);
        }
        boolean typedKind = prefix.equals("#noise:") && Noise.Kind.parse(parts[0]) != null;
        String base = prefix + (typedKind ? parts[0] + ":" : "");
        int sizeIndex = typedKind ? 1 : 0;
        if (parts.length == sizeIndex + 1) {
            List.of("4:", "8:", "12:", "16:", "32:").forEach(size -> options.add(base + size));
            return filter(options, typed);
        }
        if (mask && parts.length == sizeIndex + 2) {
            List.of("30", "50", "60", "75").forEach(threshold -> options.add(base + parts[sizeIndex] + ":" + threshold));
            return filter(options, typed);
        }
        return null;
    }

    List<String> completeBlocks(String typed, List<String> specials, String separators) {
        String lower = typed.toLowerCase(Locale.ROOT);
        int cut = -1;
        for (char separator : separators.toCharArray()) {
            cut = Math.max(cut, lower.lastIndexOf(separator));
        }
        String head = cut < 0 ? "" : lower.substring(0, cut + 1);
        String tail = lower.substring(cut + 1);
        List<String> out = new ArrayList<>();
        if (cut < 0) {
            specials.stream().filter(special -> special.startsWith(lower)).forEach(out::add);
        }
        trowel.host().markers().ids().stream().filter(id -> id.startsWith(tail)).map(id -> head + id).forEach(out::add);
        String state = stateOf(tail);
        if (state != null) {
            out.add(head + state);
        }
        blockNames().stream().filter(name -> name.startsWith(tail)).limit(40).map(name -> head + name).forEach(out::add);
        return out.size() > 60 ? out.subList(0, 60) : out;
    }

    /** The complete state of a block typed in full: {@code oak_stairs[facing=north,half=bottom,...]}. */
    private static String stateOf(String name) {
        if (name.isEmpty() || name.contains("[")) {
            return null;
        }
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isBlock() || !material.getKey().getKey().equals(name)) {
            return null;
        }
        String full = material.createBlockData().getAsString(false);
        int bracket = full.indexOf('[');
        return bracket < 0 ? null : name + full.substring(bracket);
    }

    private List<String> blockNames() {
        if (blockNames == null) {
            blockNames = Arrays.stream(Material.values())
                    .filter(material -> !material.isLegacy() && material.isBlock())
                    .map(material -> material.getKey().getKey())
                    .sorted().toList();
        }
        return blockNames;
    }

    static List<String> filter(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
