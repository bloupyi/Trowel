package com.stackmc.trowel.ui;

import com.stackmc.trowel.Chat;
import com.stackmc.trowel.Session;
import com.stackmc.trowel.Trowel;
import com.stackmc.trowel.TrowelBootstrap;
import com.stackmc.trowel.TrowelItems;
import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.brush.BrushSettings;
import com.stackmc.trowel.brush.BrushType;
import com.stackmc.trowel.engine.Clipboard;
import com.stackmc.trowel.geom.Noise;
import com.stackmc.trowel.geom.Relief;
import com.stackmc.trowel.pattern.Masks;
import com.stackmc.trowel.pattern.Palettes;
import com.stackmc.trowel.pattern.Patterns;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Trowel's windows, for those who prefer clicking to typing: a home screen (G key) and one
 * window per category, with its fields, sliders and buttons.
 *
 * <p>Each button runs the command it stands for: same checks, same size limits, same buildable
 * area. Setting windows (selection, history) reopen after the action to chain actions;
 * operations close to show the result.</p>
 */
public final class Dialogs implements Listener {

    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(15)).build();

    private static final List<String[]> DIRECTIONS = List.of(
            new String[]{"me", "Where I look"}, new String[]{"up", "Up"}, new String[]{"down", "Down"},
            new String[]{"north", "North"}, new String[]{"south", "South"}, new String[]{"east", "East"},
            new String[]{"west", "West"}, new String[]{"northeast", "North-east"},
            new String[]{"northwest", "North-west"}, new String[]{"southeast", "South-east"},
            new String[]{"southwest", "South-west"});

    private final Trowel trowel;

    public Dialogs(Trowel trowel) {
        this.trowel = trowel;
    }

    // ----------------------------------------------------------- quick actions

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        Key id = event.getIdentifier();
        if (!"trowel".equals(id.namespace()) || !id.value().startsWith(TrowelBootstrap.QUICK_PREFIX)) {
            return;
        }
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) {
            return;
        }
        Player player = connection.getPlayer();
        String action = id.value().substring(TrowelBootstrap.QUICK_PREFIX.length());
        onMain(() -> quick(player, action));
    }

    public void quick(Player player, String action) {
        if (!player.isOnline() || denied(player)) {
            return;
        }
        if (action.startsWith(TrowelBootstrap.CATEGORY_PREFIX)) {
            openCategory(player, action.substring(TrowelBootstrap.CATEGORY_PREFIX.length()));
            return;
        }
        switch (action) {
            case "brush" -> {
                if (trowel.items().brushOf(player.getInventory().getItemInMainHand()) != null) {
                    openBrush(player);
                } else {
                    openKit(player, false);
                }
            }
            case "hub", "tools" -> openHub(player);
            default -> trowel.commands().execute(player, action);
        }
    }

    // -------------------------------------------------------------------- home

    public void openHub(Player player) {
        if (denied(player)) {
            return;
        }
        Screen screen = new Screen(player, "Trowel", () -> openHub(player)).columns(2).parent(null);
        screen.lines(context(player));
        for (TrowelBootstrap.Entry category : TrowelBootstrap.CATEGORIES) {
            screen.act(category.label(), category.tooltip(), (in, who) -> openCategory(who, category.id()));
        }
        for (TrowelBootstrap.Entry shortcut : TrowelBootstrap.SHORTCUTS) {
            screen.act(shortcut.label(), shortcut.tooltip(), (in, who) -> quick(who, shortcut.id()));
        }
        screen.show();
    }

    public void openCategory(Player player, String id) {
        if (denied(player)) {
            return;
        }
        switch (id) {
            case "selection" -> selection(player);
            case "fill" -> fill(player);
            case "shapes" -> shapes(player);
            case "lines" -> lines(player);
            case "splines" -> splines(player);
            case "expressions" -> expressions(player);
            case "sculpt" -> sculpt(player);
            case "textures" -> textures(player);
            case "decor" -> decor(player);
            case "palettes" -> palettes(player);
            case "tools" -> arceon(player);
            case "terrain" -> terrain(player);
            case "terrain.relief" -> relief(player);
            case "terrain.noise" -> noise(player);
            case "terrain.shape" -> retouch(player);
            case "terrain.flora" -> flora(player);
            case "library" -> library(player);
            case "clipboard" -> clipboard(player);
            case "move" -> move(player);
            case "near" -> near(player);
            case "brushes" -> openKit(player, false);
            case "history" -> history(player);
            case "settings" -> settings(player);
            case "help" -> help(player);
            default -> openHub(player);
        }
    }

    /** The former name of the home screen, kept for host plugins. */
    public void openTools(Player player) {
        openHub(player);
    }

    private boolean denied(Player player) {
        String denied = trowel.host().denyEdit(player);
        if (denied != null) {
            Chat.error(player, denied);
            return true;
        }
        return false;
    }

    /** What the player has going on: selection, clipboard, points, limits. */
    private List<String> context(Player player) {
        Session session = trowel.session(player);
        Box selection = trowel.selection(player);
        Clipboard clipboard = session.getClipboard();
        BrushSettings brush = trowel.items().brushOf(player.getInventory().getItemInMainHand());
        List<String> lines = new ArrayList<>();
        lines.add("Selection: " + (selection == null ? "none" : selection.size() + " (" + selection.volume() + " blocks)"));
        lines.add("Clipboard: " + (clipboard == null ? "empty" : clipboard.size()
                + (clipboard.markerCount() > 0 ? ", " + clipboard.markerCount() + " marker(s)" : "")));
        lines.add("Spline points: " + session.getPoints().size());
        if (brush != null) {
            lines.add("In hand: brush " + brush.type().getDisplayName());
        }
        lines.add("Global mask: " + (session.getGlobalMask() == null ? "none" : session.getGlobalMask())
                + "   Markers: " + (session.isEditMarkers() ? "editable" : "protected"));
        lines.add("Limit: " + trowel.settings().maxBlocks() + " blocks per operation, inside the buildable area.");
        return lines;
    }

    // -------------------------------------------------------------- categories

    private void selection(Player player) {
        Session session = trowel.session(player);
        Box box = trowel.selection(player);
        Screen screen = new Screen(player, "Selection", () -> selection(player)).columns(3);
        if (box == null) {
            screen.line("No selection. Wand: left click and right click, from afar too.");
        } else {
            screen.line("Selection: " + box.size() + " (" + box.volume() + " blocks)");
            screen.line("From " + box.minX() + " " + box.minY() + " " + box.minZ() + " to "
                    + box.maxX() + " " + box.maxY() + " " + box.maxZ());
        }
        screen.line("Spline points: " + session.getPoints().size()
                + " (wand: sneak + left click)");
        screen.number("n", "Number of blocks", 1, 256, 5)
                .choice("dir", "Direction", DIRECTIONS, "me")
                .text("mask", "Mask to count", "#existing");
        screen.stay("Wand", "//wand", in -> "wand")
                .stay("Corner 1: my feet", "//pos1", in -> "pos1")
                .stay("Corner 2: my feet", "//pos2", in -> "pos2")
                .stay("Corner 1: aimed block", "//hpos1", in -> "hpos1")
                .stay("Corner 2: aimed block", "//hpos2", in -> "hpos2")
                .stay("Full height", "//expand vert", in -> "expand vert")
                .stay("Expand", "//expand: pushes the face of the chosen direction", in -> "expand " + in.number("n", 5) + " " + in.text("dir", "me"))
                .stay("Contract", "//contract: pulls in the opposite face", in -> "contract " + in.number("n", 5) + " " + in.text("dir", "me"))
                .stay("Shift", "//shift: moves the selection alone", in -> "shift " + in.number("n", 5) + " " + in.text("dir", "me"))
                .stay("Grow everywhere", "//outset", in -> "outset " + in.number("n", 5))
                .stay("Shrink everywhere", "//inset", in -> "inset " + in.number("n", 5))
                .stay("Clear the selection", "//sel", in -> "sel")
                .stay("Point: aimed block", "//point", in -> "point")
                .stay("Point: my feet", "//point here", in -> "point here")
                .stay("Clear the points", "//point clear", in -> "point clear")
                .stay("Next selection", "//next: shifts by its own size", in -> "next " + in.text("dir", "me"))
                .stay("Selection at my feet", "//selhere", in -> "selhere")
                .run("Shrink to the mask", "//enc: the smallest selection containing the mask",
                        in -> "enc " + in.mask("#existing"))
                .run("Count", "//count: the mask above", in -> "count " + in.mask("#existing"))
                .run("Distribution", "//distr: the blocks of the selection", in -> "distr");
        screen.show();
    }

    private void fill(Player player) {
        Screen screen = new Screen(player, "Fill and replace", () -> fill(player)).columns(3);
        screen.lines(selectionLine(player));
        screen.pattern(defaultPattern(player))
                .text("mask", "Mask (replace: what gets replaced)", "#existing")
                .number("n", "Thickness", 1, 16, 1)
                .text("from", "Wood type: from", "oak")
                .text("to", "Wood type: to", "spruce");
        screen.run("Fill", "//set", in -> "set " + in.pattern())
                .run("Replace", "//replace: the mask becomes the pattern", in -> "replace " + in.mask("#existing") + " " + in.pattern())
                .run("Walls", "//walls", in -> "walls " + in.pattern())
                .run("Outline", "//outline: the six faces", in -> "outline " + in.pattern())
                .run("Center", "//center", in -> "center " + in.pattern())
                .run("Overlay", "//overlay: thickness layers on top", in -> "overlay " + in.pattern() + " " + in.number("n", 1))
                .run("Hollow", "//hollow: keeps a shell of the thickness", in -> "hollow " + in.number("n", 1))
                .run("Naturalize", "//naturalize: grass, dirt, stone", in -> "naturalize")
                .run("Green", "//green: dirt open to the sky becomes grass", in -> "green")
                .run("Connect", "//fixconnect: fences, panes, walls", in -> "fixconnect")
                .run("Change wood type", "//typereplace from to", in -> "typereplace " + in.word("from", "oak") + " " + in.word("to", "spruce"));
        screen.show();
    }

    private void shapes(Player player) {
        Screen screen = new Screen(player, "Shapes", () -> shapes(player)).columns(3);
        screen.line("Shapes are placed at your feet.");
        screen.pattern(defaultPattern(player))
                .number("r", "Radius", 1, 64, 5)
                .number("h", "Height (cylinder, cone, ellipsoid)", 1, 128, 8)
                .toggle("hollow", "Hollow", false);
        screen.run("Sphere", "//sphere", in -> (in.bool("hollow") ? "hsphere " : "sphere ") + in.pattern() + " " + in.number("r", 5))
                .run("Ellipsoid", "//sphere radius,height,radius", in -> (in.bool("hollow") ? "hsphere " : "sphere ") + in.pattern() + " "
                        + in.number("r", 5) + "," + in.number("h", 8) + "," + in.number("r", 5))
                .run("Cylinder", "//cyl", in -> (in.bool("hollow") ? "hcyl " : "cyl ") + in.pattern() + " " + in.number("r", 5) + " " + in.number("h", 8))
                .run("Pyramid", "//pyramid", in -> (in.bool("hollow") ? "hpyramid " : "pyramid ") + in.pattern() + " " + in.number("r", 5))
                .run("Cone", "//cone", in -> (in.bool("hollow") ? "hcone " : "cone ") + in.pattern() + " " + in.number("r", 5) + " " + in.number("h", 8));
        screen.show();
    }

    private void lines(Player player) {
        Session session = trowel.session(player);
        Screen screen = new Screen(player, "Lines and curves", () -> lines(player)).columns(3);
        screen.line("Line, rope and arch go from corner 1 to corner 2. The spline goes through every point ("
                + session.getPoints().size() + " placed).");
        screen.pattern(defaultPattern(player))
                .number("t", "Thickness", 1, 16, 1)
                .number("h", "Rope sag, arch height", 1, 64, 6)
                .number("w", "Arch width", 1, 32, 3);
        screen.run("Line", "//line", in -> "line " + in.pattern() + " " + in.number("t", 1))
                .run("Rope", "//rope: hangs between the corners", in -> "rope " + in.pattern() + " " + in.number("h", 6) + " " + in.number("t", 1))
                .run("Arch", "//arch: bridge between the corners", in -> "arch " + in.pattern() + " " + in.number("h", 6) + " " + in.number("w", 3) + " " + in.number("t", 1))
                .act("Splines...", "Every shape along the points", (in, who) -> splines(who))
                .stay("Point: aimed block", "//point", in -> "point")
                .stay("Point: my feet", "//point here", in -> "point here")
                .stay("Clear the points", "//point clear", in -> "point clear");
        screen.show();
    }

    // ----------------------------------------------------------------- splines

    private void splines(Player player) {
        Session session = trowel.session(player);
        Screen screen = new Screen(player, "Splines", () -> splines(player)).columns(3);
        screen.line("The shape follows the points (" + session.getPoints().size() + " placed; failing that, both corners). "
                + "Wand: sneak + left click to place a point.");
        List<String[]> shapes = new ArrayList<>();
        for (com.stackmc.trowel.spline.Sections.Def def : com.stackmc.trowel.spline.Sections.all().values()) {
            shapes.add(new String[]{def.id(), def.category().toUpperCase(java.util.Locale.ROOT) + " " + def.id() + ": " + def.help()});
        }
        shapes.add(new String[]{"noise", "Noise carving a tube (palette)"});
        shapes.add(new String[]{"expr", "Expression (palette)"});
        shapes.add(new String[]{"clipboard", "The clipboard along the path"});
        screen.choice("shape", "Shape", shapes, "circle")
                .text("params", "Shape settings, e.g. S:6,D:0.4 (Shape help)", "")
                .pattern(defaultPattern(player), "Pattern or palette (#local[##bark][perlin(y:0.1)] follows the shape)")
                .text("radii", "Radii: 3, or 1,8 (grows), 2,8,2, 1,0.2:8,1", "3")
                .number("twist", "Twist (degrees per diameter)", -1440, 1440, 0)
                .text("roll", "Roll: 0, or start,end", "0")
                .text("stretch", "Stretch along the path", "1")
                .choice("end", "Caps", List.of(new String[]{"flat", "Flat"}, new String[]{"soft", "Soft"},
                        new String[]{"round", "Round"}, new String[]{"spike", "Pointed"}, new String[]{"cube", "Square"}), "flat")
                .choice("normal", "Orientation", List.of(new String[]{"consistent", "Follows the bends"},
                        new String[]{"horizontal", "Stays flat"}, new String[]{"upright", "Always upright"}), "consistent")
                .choice("quality", "Quality", List.of(new String[]{"fast", "Fast"}, new String[]{"balanced", "Balanced"},
                        new String[]{"high", "High"}, new String[]{"exact", "Exact"}), "balanced")
                .text("kb", "Curve tension:bias:continuity (-1 to 1)", "0:0:0")
                .toggle("hollow", "Hollow", false)
                .toggle("closed", "Closed loop", false)
                .text("noise", "Noise (noise shape)", "perlin(f:2,z:0.5)")
                .text("depth", "Noise depth (noise shape)", "0.7")
                .text("expr", "Expression (expr shape): x, y the section, z along, t, r, a", "x*x+y*y<1")
                .text("smask", "Mask: what the spline may replace (empty: everything)", "");
        screen.run("Place the spline", "//spline with these settings", this::splineCommand)
                .stay("Point: aimed block", "//point", in -> "point")
                .stay("Point: my feet", "//point here", in -> "point here")
                .stay("Remove the last", "//point undo", in -> "point undo")
                .stay("Clear the points", "//point clear", in -> "point clear")
                .stay("Points = both corners", "//point sel", in -> "point sel")
                .stay("Catenary (10 points)", "//catenary 10: hangs between the first and the last", in -> "catenary 10")
                .stay("Arch (10 points)", "//catenary 10 <sag> up", in -> "catenary 10 6 up")
                .run("Shape help", "//spline help", in -> "spline help");
        screen.show();
    }

    private String splineCommand(Values in) {
        String shape = in.text("shape", "circle");
        String params = in.word("params", "");
        StringBuilder out = new StringBuilder("spline ").append(shape);
        if (!params.isEmpty() && !shape.equals("noise") && !shape.equals("expr") && !shape.equals("clipboard")) {
            out.append('(').append(params.replace("(", "").replace(")", "")).append(')');
        }
        if (!shape.equals("clipboard")) {
            out.append(' ').append(in.pattern());
        }
        out.append(' ').append(in.word("radii", "3"));
        if (shape.equals("noise")) {
            out.append(' ').append(in.word("noise", "perlin(f:2,z:0.5)")).append(' ').append(in.word("depth", "0.7"));
        }
        int twist = in.number("twist", 0);
        if (twist != 0) {
            out.append(" -t ").append(twist);
        }
        String roll = in.word("roll", "0");
        if (!roll.equals("0")) {
            out.append(" -r ").append(roll);
        }
        String stretch = in.word("stretch", "1");
        if (!stretch.equals("1")) {
            out.append(" -s ").append(stretch);
        }
        String end = in.text("end", "flat");
        if (!end.equals("flat")) {
            out.append(" -e ").append(end);
        }
        String normal = in.text("normal", "consistent");
        if (!normal.equals("consistent")) {
            out.append(" -n ").append(normal);
        }
        String quality = in.text("quality", "balanced");
        if (!quality.equals("balanced")) {
            out.append(" -q ").append(quality);
        }
        String kb = in.word("kb", "0:0:0");
        if (!kb.equals("0:0:0")) {
            out.append(" -p ").append(kb);
        }
        if (in.bool("hollow")) {
            out.append(" -h");
        }
        if (in.bool("closed")) {
            out.append(" -c");
        }
        String mask = in.maskOf("smask", "");
        if (!mask.isEmpty()) {
            out.append(" -m ").append(mask);
        }
        if (shape.equals("expr")) {
            out.append(' ').append(in.text("expr", "x*x+y*y<1"));
        }
        return out.toString();
    }

    private void expressions(Player player) {
        Screen screen = new Screen(player, "Expressions", () -> expressions(player)).columns(3);
        screen.lines(selectionLine(player));
        screen.line("Generate: x, y, z go from -1 to 1 in the selection; when positive a block is placed, and its value "
                + "(0 to 1) picks the block of a palette.");
        screen.pattern(defaultPattern(player), "Pattern or ordered palette (##magma, stone,andesite...)")
                .text("expr", "Expression to generate", "x*x+y*y+z*z<1")
                .choice("mode", "Coordinates", List.of(new String[]{"norm", "From -1 to 1 in the selection"},
                        new String[]{"-c", "In blocks, from the center"}, new String[]{"-o", "In blocks, from me"},
                        new String[]{"-r", "World coordinates"}), "norm")
                .toggle("hollow", "Hollow", false)
                .text("deform", "Deformation: changes x, y, z", "y-=0.2*sin(x*5)");
        screen.run("Generate", "//generate", in -> "generate " + (in.bool("hollow") ? "-h " : "") + mode(in)
                        + in.pattern() + " " + in.text("expr", "x*x+y*y+z*z<1"))
                .run("Deform", "//deform", in -> "deform " + mode(in) + in.text("deform", "y-=0.2*sin(x*5)"))
                .run("Functions", "//functions: everything an expression knows", in -> "functions")
                .act("Example: torus", "A ring", (in, who) -> trowel.commands().execute(who,
                        "generate " + in.pattern() + " sdtorus(x,y,z,0.6,0.25)<0"))
                .act("Example: cracked rock", "A ball of cells", (in, who) -> trowel.commands().execute(who,
                        "generate " + in.pattern() + " x*x+y*y+z*z<1-0.3*noise(x*3,y*3,z*3)&&cracks(x*4,y*4,z*4)>0.15"))
                .act("Example: gradient", "The palette from bottom to top", (in, who) -> trowel.commands().execute(who,
                        "generate " + in.pattern() + " (y+1)/2"));
        screen.show();
    }

    private void sculpt(Player player) {
        Screen screen = new Screen(player, "Sculpt", () -> sculpt(player)).columns(3);
        screen.lines(selectionLine(player));
        screen.number("r", "Radius, depth", 1, 8, 2)
                .number("passes", "Smoothing passes", 1, 10, 2)
                .text("bias", "Smoothing bias (-1 thins, 1 inflates)", "0")
                .choice("surface", "Surface", List.of(new String[]{"rockify", "Rock"}, new String[]{"fuzzify", "Fuzz"},
                        new String[]{"voronoify", "Facets"}, new String[]{"noisify", "Noise below"}), "rockify")
                .text("size", "Size of rocks, cells, hexagons, voxels", "10")
                .text("noise", "Noise (noisify surface, warp)", "perlin(fo:3)")
                .choice("smode", "The surface may", List.of(new String[]{"both", "Dig and bulge"},
                        new String[]{"-c", "Dig only"}, new String[]{"-e", "Bulge only"}), "both")
                .text("gap", "Gap (Voronoi, hexagons, voxels)", "1")
                .text("angle", "Angle or factor (twist, rotation, taper)", "90")
                .choice("axis", "Axis", List.of(new String[]{"y", "Vertical"}, new String[]{"x", "East-west"},
                        new String[]{"z", "North-south"}), "y");
        screen.run("Smooth in 3D", "//smooth3d", in -> "smooth3d " + in.number("r", 2) + " " + in.number("passes", 2) + " "
                        + in.word("bias", "0"))
                .run("Inflate", "//inflate", in -> "inflate " + in.number("r", 2))
                .run("Deflate", "//deflate", in -> "deflate " + in.number("r", 2))
                .run("Surface", "//surface", in -> "surface " + in.text("surface", "rockify") + " " + in.number("r", 2) + " "
                        + (in.text("surface", "").equals("noisify") ? in.word("noise", "perlin(fo:3)") : in.word("size", "10"))
                        + (in.text("smode", "both").startsWith("-") ? " " + in.text("smode", "both") : ""))
                .run("Voronoi", "//voronoialize: split cells", in -> "voronoialize " + in.word("size", "10") + " "
                        + in.word("gap", "1"))
                .run("Hexagons", "//hexagonalize: basalt columns", in -> "hexagonalize " + in.word("size", "10") + " "
                        + in.word("gap", "1"))
                .run("Voxels", "//voxelize: big cubes", in -> "voxelize " + Math.max(2, (int) parse(in.word("size", "4"), 4))
                        + " " + (int) parse(in.word("gap", "0"), 0))
                .run("Noise warp", "//noisedeform", in -> "noisedeform " + in.word("noise", "perlin(fo:3)") + " "
                        + in.number("r", 2))
                .run("Twist", "//twist", in -> "twist " + in.word("angle", "90") + " " + in.text("axis", "y"))
                .run("Rotate", "//rotatesel", in -> "rotatesel " + in.word("angle", "90") + " " + in.text("axis", "y"))
                .run("Taper", "//taper: factor at the end of the axis", in -> "taper " + in.word("angle", "0.5") + " " + in.text("axis", "y"));
        screen.show();
    }

    private void textures(Player player) {
        Screen screen = new Screen(player, "Textures", () -> textures(player)).columns(3);
        screen.lines(selectionLine(player));
        screen.line("The start of the palette goes to light and bumps, the end to hollows and shade.");
        List<String[]> kinds = new ArrayList<>();
        for (com.stackmc.trowel.engine.Textures.Kind kind : com.stackmc.trowel.engine.Textures.Kind.values()) {
            kinds.add(new String[]{kind.id(), kind.help()});
        }
        List<String[]> presets = new ArrayList<>();
        presets.add(new String[]{"none", "The one written next to it"});
        for (String preset : com.stackmc.trowel.pattern.Palette.presets()) {
            presets.add(new String[]{"-##" + preset, "##" + preset + " (light first)"});
            presets.add(new String[]{"##" + preset, "##" + preset});
        }
        screen.choice("type", "Texture", kinds, "ambient")
                .text("mask", "Mask: the blocks to repaint", "#existing")
                .text("pal", "Written palette", "##grayscale")
                .choice("preset", "Or a ready-made palette", presets, "none")
                .number("r", "Analysis radius", 1, 8, 3)
                .text("brightness", "Brightness (-1 to 1)", "0")
                .text("contrast", "Contrast", "0")
                .text("dir", "Sun: direction x,y,z", "0.35,-1,0.25")
                .text("interval", "Covered angles, in degrees", "0,180")
                .text("shadows", "Cast shadows (0 to 1)", "0")
                .choice("axis", "Axis (gradient)", List.of(new String[]{"y", "Height"}, new String[]{"x", "East-west"},
                        new String[]{"z", "North-south"}), "y")
                .toggle("relative", "Gradient per column", false)
                .number("amount", "Cells, shift, depth", -64, 400, 24)
                .text("noise", "Noise (noise texture)", "perlin(f:0.08,fo:3)")
                .text("dither", "Dither (0: sharp)", "0.6");
        screen.run("Texture", "//texture", in -> {
            String preset = in.text("preset", "none");
            return "texture " + in.text("type", "ambient") + " " + in.mask("#existing") + " "
                    + (preset.equals("none") ? in.word("pal", "##grayscale") : preset)
                    + " radius:" + in.number("r", 3) + " brightness:" + in.word("brightness", "0")
                    + " contrast:" + in.word("contrast", "0") + " dir:" + in.word("dir", "0.35,-1,0.25")
                    + " interval:" + in.word("interval", "0,180") + " shadows:" + in.word("shadows", "0")
                    + " axis:" + in.text("axis", "y") + " relative:" + in.bool("relative")
                    + " amount:" + in.number("amount", 24) + " noise:" + in.word("noise", "perlin(f:0.08,fo:3)")
                    + " dither:" + in.word("dither", "0.6");
        }).run("Help", "//texture help", in -> "texture help");
        screen.show();
    }

    private void decor(Player player) {
        Screen screen = new Screen(player, "Decor", () -> decor(player)).columns(3);
        screen.lines(selectionLine(player));
        screen.pattern(defaultPattern(player), "Pattern (vines, moss) or palette (relief)")
                .text("mask", "Vines: under which blocks", "#existing")
                .number("chance", "Chance, amount (%)", 1, 100, 20)
                .number("min", "Min vine length", 1, 64, 2)
                .number("max", "Max vine length", 1, 64, 6)
                .number("smooth", "Moss smoothing", 0, 6, 2)
                .text("noise", "Relief noise", "perlin(fo:4)")
                .number("scale", "Relief scale", 2, 256, 32);
        screen.run("Vines", "//vines", in -> "vines " + in.mask("#existing") + " " + in.pattern() + " " + in.number("chance", 20)
                        + " " + in.number("min", 2) + " " + Math.max(in.number("min", 2), in.number("max", 6)))
                .run("Moss", "//moss", in -> "moss " + in.pattern() + " " + in.number("chance", 20) + " " + in.number("smooth", 2))
                .run("Noise relief", "//heightmap", in -> "heightmap " + in.pattern() + " " + in.word("noise", "perlin(fo:4)")
                        + " " + in.number("scale", 32) + " " + in.number("chance", 100));
        screen.show();
    }

    private void palettes(Player player) {
        Screen screen = new Screen(player, "Palettes", () -> palettes(player)).columns(3);
        screen.line("A palette is an ordered list: ##name, -##name reversed, ##name(2:6) an excerpt, block*3 repeated.");
        screen.text("pal", "Palette", "##magma")
                .text("to", "To (swap)", "##ice")
                .text("name", "Name to save it", "mypalette")
                .choice("dir", "Row direction", DIRECTIONS, "me");
        screen.run("All palettes", "//palette list", in -> "palette list")
                .run("View", "//palette show", in -> "palette show " + in.word("pal", "##magma"))
                .run("Sort by lightness", "//palette sort", in -> "palette sort " + in.word("pal", "##magma"))
                .run("Place in a row", "//palette place: on the aimed block", in -> "palette place " + in.word("pal", "##magma")
                        + " " + in.text("dir", "me"))
                .run("Swap in the selection", "//palette swap: each block becomes its rank in the other",
                        in -> "palette swap " + in.word("pal", "##magma") + " " + in.word("to", "##ice"))
                .run("Save", "//palette save: ##name afterwards", in -> "palette save " + in.word("name", "mypalette") + " "
                        + in.word("pal", "##magma"))
                .act("Editor (drag and drop)", "//palette edit: compose the ##name palette with your blocks",
                        (in, who) -> trowel.paletteEditor().open(who, in.word("name", "mypalette").toLowerCase(java.util.Locale.ROOT)));
        screen.show();
    }

    private void arceon(Player player) {
        Session session = trowel.session(player);
        Screen screen = new Screen(player, "Arceon tools", () -> arceon(player)).columns(3);
        screen.line("Roof, road, river and dashes follow the points (" + session.getPoints().size()
                + " placed); the others act on the selection, or in front of you.");
        screen.pattern(defaultPattern(player))
                .number("w", "Width (roof, road), radius (spiral stairs)", 1, 64, 7)
                .number("h", "Height (roof, spiral stairs), thickness (road)", 1, 128, 4)
                .toggle("noise", "Irregular (-n)", false)
                .toggle("down", "Go down to the ground (-d)", false)
                .toggle("bevel", "Roof: hipped ends (-b)", false)
                .toggle("straight", "Straight path (-p)", false)
                .number("d1", "River: start depth", 1, 32, 2)
                .number("d2", "River: end depth", 1, 32, 4)
                .number("dash", "Dashes: dash, gap", 1, 32, 3)
                .number("n", "Distance, copies, level", -256, 512, 5)
                .choice("dir", "Direction", DIRECTIONS, "me")
                .text("angles", "Revolve: start,end,height", "0,360,0")
                .text("scale", "Scale (resize)", "2")
                .text("colors", "Colors: from to", "red blue")
                .number("smooth", "Snow: smoothness", 1, 16, 6)
                .text("text", "Text", "Trowel")
                .choice("font", "Font", List.of(new String[]{"SansSerif", "Sans serif"}, new String[]{"Serif", "Serif"},
                        new String[]{"Monospaced", "Monospaced"}, new String[]{"Dialog", "Dialog"}), "SansSerif");
        screen.run("Roof", "//roof", in -> "roof " + in.pattern() + " " + in.number("w", 7) + " " + in.number("h", 4)
                        + flagsOf(in, "noise", "n", "down", "d", "bevel", "b", "straight", "p"))
                .run("Road", "//road", in -> "road " + in.pattern() + " " + in.number("w", 7) + " " + in.number("h", 1)
                        + flagsOf(in, "noise", "n", "down", "d", "straight", "p"))
                .run("River", "//river: the pattern fills the bed (water...)", in -> "river " + in.pattern() + " "
                        + in.number("d1", 2) + " " + in.number("d2", 4) + flagsOf(in, "noise", "n"))
                .run("Dashes", "//dashes", in -> "dashes " + in.pattern() + " " + in.number("dash", 3) + " "
                        + in.number("dash", 3) + " " + Math.max(1, in.number("w", 1) / 4))
                .run("Smear", "//smear: stretches the selection", in -> "smear " + in.number("n", 5) + " " + in.text("dir", "me"))
                .run("Revolve", "//revolve: copies around you", in -> {
                    String[] parts = in.word("angles", "0,360,0").split(",");
                    return "revolve " + Math.max(1, in.number("n", 8)) + " " + (parts.length > 0 ? parts[0] : "0") + " "
                            + (parts.length > 1 ? parts[1] : "360") + " " + (parts.length > 2 ? parts[2] : "0");
                })
                .run("Resize", "//resize", in -> "resize " + in.word("scale", "2"))
                .run("Change a color", "//colorreplace", in -> "colorreplace " + in.text("colors", "red blue"))
                .run("Cast shadow", "//shadow: seen from you", in -> "shadow " + in.pattern())
                .run("Smooth snow", "//smoothsnow", in -> "smoothsnow " + in.number("smooth", 6))
                .run("Ocean", "//ocean: up to the level", in -> "ocean " + in.number("n", 62) + " water")
                .run("Spiral stairs", "//spiralstairs", in -> "spiralstairs " + in.pattern() + " " + Math.min(32, in.number("w", 4))
                        + " " + Math.max(2, in.number("h", 16)))
                .act("Text", "//text on the aimed block", (in, who) -> {
                    trowel.commands().execute(who, "font " + in.text("font", "SansSerif"));
                    trowel.commands().execute(who, "text " + in.pattern() + " " + Math.max(4, in.number("h", 16)) + " "
                            + in.text("text", "Trowel"));
                })
                .stay("Loft: new frame", "//loft frame", in -> "loft frame")
                .stay("Loft: aimed point", "//loft point", in -> "loft point")
                .stay("Loft: remove", "//loft remove", in -> "loft remove")
                .run("Loft: stretch", "//loft set", in -> "loft set " + in.pattern() + flagsOf(in, "down", "d", "straight", "p"))
                .stay("Loft: clear", "//loft clear", in -> "loft clear");
        screen.show();
    }

    /** The checked flags: {@code flagsOf(in, "noise", "n")} gives {@code " -n"} if the box is checked. */
    private static String flagsOf(Values in, String... pairs) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (in.bool(pairs[i])) {
                out.append(" -").append(pairs[i + 1]);
            }
        }
        return out.toString();
    }

    private static String mode(Values in) {
        String mode = in.text("mode", "norm");
        return mode.startsWith("-") ? mode + " " : "";
    }

    private static double parse(String raw, double fallback) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void terrain(Player player) {
        Screen screen = new Screen(player, "Terrain and noise", () -> terrain(player)).columns(2);
        screen.lines(selectionLine(player));
        screen.line("Terrain tools from Arceon, ezEdits and goBrush. They work inside the selection.");
        screen.act("Generate relief", "Hills, mountains, dunes, mesas, islands, craters, canyons",
                        (in, who) -> openCategory(who, "terrain.relief"))
                .act("Noise volumes", "Fill, carve or texture by a noise",
                        (in, who) -> openCategory(who, "terrain.noise"))
                .act("Touch up the relief", "Smooth, roughen, naturalize",
                        (in, who) -> openCategory(who, "terrain.shape"))
                .act("Vegetation and snow", "Scatter grass and flowers, snow, thaw, grass",
                        (in, who) -> openCategory(who, "terrain.flora"));
        screen.show();
    }

    private void relief(Player player) {
        Screen screen = new Screen(player, "Generate relief", () -> relief(player)).columns(2)
                .parent(() -> terrain(player));
        screen.lines(selectionLine(player));
        screen.line("The selection becomes terrain: its height goes from the bottom to the top of the selection.");
        List<String[]> reliefs = new ArrayList<>();
        for (Relief relief : Relief.values()) {
            reliefs.add(new String[]{relief.id(), relief.label()});
        }
        screen.choice("relief", "Relief", reliefs, "hills")
                .number("scale", "Size of the shapes (blocks)", 4, 256, 32)
                .number("strength", "Strength (%)", 5, 100, 100)
                .text("top", "Top", "grass_block")
                .text("under", "Under (3 layers)", "dirt")
                .text("deep", "Deep", "stone");
        screen.run("Generate in the selection", "//terrain", in -> "terrain " + in.text("relief", "hills") + " "
                + in.number("scale", 32) + " " + in.number("strength", 100) + " "
                + in.word("top", "grass_block") + "|" + in.word("under", "dirt") + "|" + in.word("deep", "stone"));
        screen.show();
    }

    private void noise(Player player) {
        Screen screen = new Screen(player, "Noise volumes", () -> noise(player)).columns(2)
                .parent(() -> terrain(player));
        screen.lines(selectionLine(player));
        screen.line("High threshold: rare and small shapes. Low threshold: full shapes.");
        screen.pattern(defaultPattern(player))
                .choice("kind", "Noise", noiseOptions(), "fractal")
                .number("scale", "Size of the shapes (blocks)", 1, 128, 8)
                .number("threshold", "Threshold (%)", 0, 100, 55)
                .toggle("air", "Only in air", false);
        screen.run("Fill by noise", "//noise: rocks, islands, veins, clouds", in -> "noise " + in.pattern() + " "
                        + in.text("kind", "fractal") + " " + in.number("scale", 8) + " " + in.number("threshold", 55)
                        + (in.bool("air") ? " -a" : ""))
                .run("Carve by noise", "//carve: caves, holes", in -> "carve " + in.text("kind", "fractal") + " "
                        + in.number("scale", 8) + " " + in.number("threshold", 55))
                .run("Texture what exists", "//replace #existing with a noise pattern", in -> "replace #existing #noise:"
                        + in.text("kind", "fractal") + ":" + in.number("scale", 8) + ":" + in.base())
                .stay("Mask by noise", "//gmask: the next operations follow the noise", in -> "gmask #noise:"
                        + in.text("kind", "fractal") + ":" + in.number("scale", 8) + ":" + in.number("threshold", 55));
        screen.show();
    }

    private void retouch(Player player) {
        Screen screen = new Screen(player, "Touch up the relief", () -> retouch(player)).columns(3)
                .parent(() -> terrain(player));
        screen.lines(selectionLine(player));
        screen.number("passes", "Smoothing passes", 1, 10, 2)
                .text("mask", "Mask (smoothing)", "")
                .choice("kind", "Roughness noise", noiseOptions(), "fractal")
                .number("scale", "Size of the bumps (blocks)", 1, 64, 6)
                .number("amplitude", "Amplitude (blocks)", 1, 16, 2);
        screen.run("Smooth", "//smooth", in -> "smooth " + in.number("passes", 2) + (in.mask("").isEmpty() ? "" : " " + in.mask("")))
                .run("Roughen", "//roughen", in -> "roughen " + in.text("kind", "fractal") + " "
                        + in.number("scale", 6) + " " + in.number("amplitude", 2))
                .run("Naturalize", "//naturalize", in -> "naturalize");
        screen.show();
    }

    private void flora(Player player) {
        Screen screen = new Screen(player, "Vegetation and snow", () -> flora(player)).columns(3)
                .parent(() -> terrain(player));
        screen.lines(selectionLine(player));
        screen.line("Scatter works in the selection; snow, thaw and grass too, or around you with a radius.");
        screen.pattern("#palette:flowers")
                .number("density", "Density (%)", 1, 100, 20)
                .text("on", "Only on (mask)", "grass_block")
                .number("radius", "Radius around me (0: the selection)", 0, 48, 0);
        screen.run("Scatter", "//scatter", in -> "scatter " + in.pattern() + " " + in.number("density", 20)
                        + (in.maskOf("on", "").isEmpty() ? "" : " " + in.maskOf("on", "")))
                .run("Snow", "//snow", in -> "snow" + radius(in))
                .run("Thaw", "//thaw", in -> "thaw" + radius(in))
                .run("Grass", "//green", in -> "green" + radius(in));
        screen.show();
    }

    private static String radius(Values in) {
        int r = in.number("radius", 0);
        return r > 0 ? " " + r : "";
    }

    private void clipboard(Player player) {
        Clipboard clipboard = trowel.session(player).getClipboard();
        Screen screen = new Screen(player, "Clipboard", () -> clipboard(player)).columns(3);
        screen.line(clipboard == null ? "Empty clipboard." : "Clipboard: " + clipboard.size()
                + (clipboard.markerCount() > 0 ? ", " + clipboard.markerCount() + " marker(s) with settings" : ""));
        screen.line("Copying happens around your feet, pasting at your feet, like WorldEdit.");
        screen.toggle("air", "Paste without air", false)
                .toggle("origin", "Paste at the original place", false)
                .toggle("select", "Select the paste", false)
                .choice("angle", "Rotation", List.of(new String[]{"90", "90 degrees"}, new String[]{"180", "180 degrees"},
                        new String[]{"270", "270 degrees"}), "90")
                .choice("flip", "Flip towards", DIRECTIONS, "me");
        screen.run("Copy", "//copy", in -> "copy")
                .run("Cut", "//cut", in -> "cut")
                .run("Paste", "//paste", in -> "paste" + (in.bool("air") ? " -a" : "") + (in.bool("origin") ? " -o" : "")
                        + (in.bool("select") ? " -s" : ""))
                .stay("Rotate", "//rotate, markers included", in -> "rotate " + in.text("angle", "90"))
                .stay("Flip", "//flip", in -> "flip " + in.text("flip", "me"))
                .stay("Clear", "//clearclipboard", in -> "clearclipboard")
                .act("Stamp brush", "A brush that places the clipboard", (in, who) -> trowel.commands().execute(who, "brush stamp"));
        screen.show();
    }

    private void move(Player player) {
        Screen screen = new Screen(player, "Move and repeat", () -> move(player)).columns(3);
        screen.lines(selectionLine(player));
        screen.number("n", "Distance, or number of copies", 1, 64, 1)
                .choice("dir", "Direction", DIRECTIONS, "me")
                .toggle("air", "Stack without air", false);
        screen.run("Move", "//move, markers included", in -> "move " + in.number("n", 1) + " " + in.text("dir", "me"))
                .run("Stack", "//stack", in -> "stack " + in.number("n", 1) + " " + in.text("dir", "me") + (in.bool("air") ? " -a" : ""))
                .stay("Shift the selection", "//shift", in -> "shift " + in.number("n", 1) + " " + in.text("dir", "me"));
        screen.show();
    }

    private void near(Player player) {
        Screen screen = new Screen(player, "Around me", () -> near(player)).columns(3);
        screen.line("No selection needed: everything happens around you, within the chosen radius.");
        screen.number("r", "Radius", 1, 32, 5)
                .number("h", "Height or depth", 1, 64, 10)
                .pattern(defaultPattern(player))
                .text("mask", "Mask (replace, remove)", "#existing");
        screen.run("Replace nearby", "//replacenear", in -> "replacenear " + in.number("r", 5) + " " + in.mask("#existing") + " " + in.pattern())
                .run("Remove nearby", "//removenear", in -> "removenear " + in.mask("#existing") + " " + in.number("r", 5))
                .run("Clear above", "//removeabove", in -> "removeabove " + in.number("r", 5) + " " + in.number("h", 10))
                .run("Clear below", "//removebelow", in -> "removebelow " + in.number("r", 5) + " " + in.number("h", 10))
                .run("Fill the hole", "//fill: spreads and goes down", in -> "fill " + in.pattern() + " " + in.number("r", 5) + " " + in.number("h", 10))
                .run("Snow", "//snow", in -> "snow " + in.number("r", 5))
                .run("Thaw", "//thaw", in -> "thaw " + in.number("r", 5))
                .run("Grass", "//green", in -> "green " + in.number("r", 5));
        screen.show();
    }

    private void history(Player player) {
        if (trowel.session(player).isBusy()) {
            Screen busy = new Screen(player, "History", () -> history(player)).columns(2);
            busy.line("An operation is running.");
            busy.run("Stop the operation", "//cancel", in -> "cancel");
            busy.show();
            return;
        }
        Session session = trowel.session(player);
        Screen screen = new Screen(player, "History", () -> history(player)).columns(2);
        screen.line(session.getUndo().size() + " operation(s) to undo, " + session.getRedo().size() + " to redo.");
        screen.line("Brush strokes of the same gesture are undone together; marker settings too.");
        screen.stay("Undo", "//undo", in -> "undo")
                .stay("Redo", "//redo", in -> "redo")
                .stay("Undo inside the selection", "//undo sel: the last operation, only inside the selection",
                        in -> "undo sel")
                .stay("Undo the brush in hand", "//undo brush: its last gesture, even if others followed",
                        in -> "undo brush");
        java.util.List<com.stackmc.trowel.engine.Batch> list = trowel.engine().history(player);
        long now = System.currentTimeMillis();
        for (int i = 0; i < Math.min(12, list.size()); i++) {
            com.stackmc.trowel.engine.Batch batch = list.get(i);
            int n = i + 1;
            screen.stay(n + ". " + batch.label(), com.stackmc.trowel.engine.Progress.blocks(batch.size()) + ", "
                    + LibraryCommands.age(now - batch.createdAt()) + ". Click: undo only this one "
                    + "(blocks touched since then stay).", in -> "undo only " + n);
        }
        screen.show();
    }

    private void library(Player player) {
        java.util.List<com.stackmc.trowel.SchematicLibrary.Entry> entries =
                trowel.library().list(player.getUniqueId(), "");
        Screen screen = new Screen(player, "Library", () -> library(player)).columns(3);
        screen.line("Schematics shared between players (" + entries.size() + "): hover for the thumbnail, click to "
                + "load into the clipboard, then //paste.");
        screen.text("name", "Name to save the clipboard", "")
                .toggle("private", "Private", false);
        screen.run("Save the clipboard", "//schem save", in -> "schem save " + in.word("name", "")
                + (in.bool("private") ? " -p" : ""));
        screen.stay("My patterns (@name)", "//pattern list", in -> "pattern list")
                .stay("My masks (@name)", "//mask list", in -> "mask list");
        for (com.stackmc.trowel.SchematicLibrary.Entry e : entries.subList(0, Math.min(27, entries.size()))) {
            screen.act(e.name(), com.stackmc.trowel.SchematicLibrary.render(e.thumbnail())
                            .append(Component.newline())
                            .append(Component.text(e.size() + ", " + e.blocks() + " blocks, by " + e.authorName(),
                                    NamedTextColor.GRAY)),
                    (in, who) -> trowel.commands().execute(who, "schem load " + e.name()));
        }
        screen.show();
    }

    private void settings(Player player) {
        Session session = trowel.session(player);
        Box bounds = trowel.host().bounds(player.getWorld());
        Screen screen = new Screen(player, "Settings and help", () -> settings(player)).columns(2);
        screen.line("Limit: " + trowel.settings().maxBlocks() + " blocks per operation, "
                + trowel.settings().blocksPerTick() + " placed per tick, brushes up to "
                + trowel.settings().maxBrushSize() + " radius.");
        if (bounds != null) {
            screen.line("Buildable area: from " + bounds.minX() + " " + bounds.minY() + " " + bounds.minZ()
                    + " to " + bounds.maxX() + " " + bounds.maxY() + " " + bounds.maxZ() + ". Nothing is placed elsewhere.");
        }
        screen.line("Global mask: " + (session.getGlobalMask() == null ? "none" : session.getGlobalMask()));
        screen.line("Markers: " + (session.isEditMarkers() ? "operations may replace them"
                : "protected from fills and brushes"));
        screen.text("gmask", "Global mask", session.getGlobalMask() == null ? "" : session.getGlobalMask());
        screen.stay("Apply the mask", "//gmask", in -> in.maskOf("gmask", "").isEmpty() ? "gmask" : "gmask " + in.maskOf("gmask", ""))
                .stay("Remove the mask", "//gmask", in -> "gmask")
                .stay(session.isEditMarkers() ? "Protect markers" : "Allow markers", "//markers",
                        in -> session.isEditMarkers() ? "markers protect" : "markers edit")
                .act("Pattern syntax", "Patterns, masks, noises, palettes", (in, who) -> help(who))
                .run("Palettes", "//palettes", in -> "palettes")
                .stay("Co-editing: others " + (session.isCoeditShow() ? "visible" : "hidden"),
                        "The selection and aimed block of the other builders in the same world, in their color. Click to "
                                + (session.isCoeditShow() ? "hide them" : "see them"),
                        in -> "coedit show " + (session.isCoeditShow() ? "off" : "on"))
                .stay("Co-editing: my selection " + (session.isCoeditShare() ? "shared" : "hidden"),
                        "What the others see of you. Click to " + (session.isCoeditShare() ? "hide it" : "share it"),
                        in -> "coedit share " + (session.isCoeditShare() ? "off" : "on"))
                .run("All commands", "//help", in -> "help");
        screen.show();
    }

    private void help(Player player) {
        Screen screen = new Screen(player, "Syntax", () -> help(player)).columns(2).parent(() -> settings(player));
        screen.line(Commands.PATTERN_HELP);
        screen.line(Commands.MASK_HELP);
        screen.line(Commands.NOISE_HELP);
        screen.line("Palettes: " + String.join(", ", Palettes.names()));
        screen.line("In windows, the palette and the texture replace or dress the written pattern.");
        screen.show();
    }

    private List<String> selectionLine(Player player) {
        Box box = trowel.selection(player);
        return List.of(box == null ? "No selection: wand, or the Selection category."
                : "Selection: " + box.size() + " (" + box.volume() + " blocks)");
    }

    private String defaultPattern(Player player) {
        Material held = player.getInventory().getItemInMainHand().getType();
        return held.isBlock() && !held.isAir() ? held.getKey().getKey() : trowel.session(player).getLastPattern();
    }

    private static List<String[]> noiseOptions() {
        List<String[]> options = new ArrayList<>();
        for (Noise.Kind kind : Noise.Kind.values()) {
            options.add(new String[]{kind.id(), kind.label()});
        }
        return options;
    }

    // ----------------------------------------------------------------- brushes

    public void openBrush(Player player) {
        if (denied(player)) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        BrushSettings current = trowel.items().brushOf(hand);
        if (current == null) {
            openKit(player, false);
            return;
        }
        int slot = player.getInventory().getHeldItemSlot();
        BrushType type = current.type();
        int max = trowel.settings().maxBrushSize();
        Screen screen = new Screen(player, "Brush: " + type.getDisplayName(), () -> openBrush(player)).columns(2)
                .parent(() -> openKit(player, false));
        screen.line(type.getDescription());
        TrowelItems.describe(current).forEach(screen::line);
        if (type.uses(BrushType.Setting.SIZE)) {
            screen.number("size", type.label(BrushType.Setting.SIZE), 0, max, current.size());
        }
        if (type.uses(BrushType.Setting.HEIGHT)) {
            screen.number("height", type.label(BrushType.Setting.HEIGHT), 1, 64, current.height());
        }
        if (type.uses(BrushType.Setting.INTENSITY)) {
            screen.number("intensity", type.label(BrushType.Setting.INTENSITY), 1, 16, current.intensity());
        }
        if (type.uses(BrushType.Setting.CHANCE)) {
            screen.number("chance", type.label(BrushType.Setting.CHANCE), 1, 100, current.chance());
        }
        if (type.uses(BrushType.Setting.FALLOFF)) {
            screen.number("falloff", type.label(BrushType.Setting.FALLOFF), 0, 100, current.falloff());
        }
        if (type.uses(BrushType.Setting.ITERATIONS)) {
            screen.number("iterations", type.label(BrushType.Setting.ITERATIONS), 1, 10, current.iterations());
        }
        if (type.uses(BrushType.Setting.PRESET)) {
            screen.choice("preset", type.label(BrushType.Setting.PRESET), same(BrushSettings.PRESETS), current.preset());
        }
        if (type.uses(BrushType.Setting.PROFILE)) {
            List<String[]> shapes = type == BrushType.FLATTEN
                    ? List.of(new String[]{"both", "Fill and cut"}, new String[]{"fill", "Fill only"},
                    new String[]{"cut", "Cut only"})
                    : List.of(new String[]{"dome", "Dome"}, new String[]{"cone", "Cone"},
                    new String[]{"plateau", "Plateau"}, new String[]{"noise", "Bumps"});
            screen.choice("profile", type.label(BrushType.Setting.PROFILE), shapes, current.profile());
        }
        if (type.uses(BrushType.Setting.NOISE)) {
            screen.choice("noise", type.label(BrushType.Setting.NOISE), noiseOptions(), current.noise());
        }
        if (type.uses(BrushType.Setting.PATTERN)) {
            screen.pattern(current.pattern(), type == BrushType.RAISE
                    ? "Pattern (empty: extends the terrain)" : "Pattern (blocks, % and markers)");
        }
        if (type.uses(BrushType.Setting.MASK)) {
            screen.text("mask", type.label(BrushType.Setting.MASK), current.mask());
        }
        if (type.uses(BrushType.Setting.SURFACE)) {
            screen.toggle("surface", type.label(BrushType.Setting.SURFACE), current.surface());
        }
        if (type.uses(BrushType.Setting.RANDOM)) {
            screen.toggle("random", type.label(BrushType.Setting.RANDOM), current.random());
        }
        if (type == BrushType.LOFT) {
            screen.line(trowel.session(player).getFrames().size() + " frame(s).");
            screen.act("Stretch", "//loft set with this pattern", (in, who) -> {
                BrushSettings read = read(in, current);
                if (store(who, slot, read)) {
                    trowel.commands().execute(who, "loft set " + read.pattern());
                }
            })
                    .act("Remove last point", "//loft remove", (in, who) -> trowel.commands().execute(who, "loft remove"))
                    .act("Clear", "//loft clear", (in, who) -> trowel.commands().execute(who, "loft clear"));
        }
        screen.act("Apply", "Keep these settings", (in, who) -> store(who, slot, read(in, current)))
                .act("Pattern = my hotbar", "The blocks of your hotbar, weighted by their amount", (in, who) -> {
                    String hotbar = Patterns.hotbar(who);
                    if (hotbar == null) {
                        Chat.error(who, "No block in your hotbar.");
                        return;
                    }
                    if (store(who, slot, read(in, current).withPattern(hotbar))) {
                        openBrush(who);
                    }
                })
                .act("Change type", "Keep the settings, change the tool", (in, who) -> openKit(who, true))
                .act("Duplicate", "A second identical brush", (in, who) ->
                        who.getInventory().addItem(trowel.items().brush(read(in, current))).values()
                                .forEach(left -> who.getWorld().dropItemNaturally(who.getLocation(), left)));
        screen.show();
    }

    private BrushSettings read(Values in, BrushSettings current) {
        return new BrushSettings(current.type(),
                in.number("size", current.size()),
                in.number("height", current.height()),
                in.number("intensity", current.intensity()),
                in.number("chance", current.chance()),
                in.number("falloff", current.falloff()),
                in.number("iterations", current.iterations()),
                in.text("preset", current.preset()),
                current.type().uses(BrushType.Setting.PATTERN)
                        ? in.pattern(current.type() == BrushType.RAISE ? "" : "stone") : current.pattern(),
                in.text("mask", current.mask()),
                in.bool("surface", current.surface()),
                in.bool("random", current.random()),
                in.text("profile", current.profile()),
                in.text("noise", current.noise())).clamp(trowel.settings().maxBrushSize());
    }

    /** Stores the settings in the brush of this slot, after checking them. */
    private boolean store(Player player, int slot, BrushSettings settings) {
        try {
            if (settings.type().uses(BrushType.Setting.PATTERN) && !settings.pattern().isBlank()) {
                Patterns.parse(Patterns.expand(trowel.expandPattern(player.getUniqueId(), settings.pattern()), player),
                        trowel.host().markers());
            }
            if (!settings.mask().isBlank()) {
                Masks.parse(trowel.expandMask(player.getUniqueId(), settings.mask()), trowel.host().markers());
            }
        } catch (IllegalArgumentException e) {
            Chat.error(player, e.getMessage());
            return false;
        }
        ItemStack there = player.getInventory().getItem(slot);
        if (trowel.items().brushOf(there) != null) {
            player.getInventory().setItem(slot, trowel.items().brush(settings));
        } else {
            player.getInventory().addItem(trowel.items().brush(settings));
        }
        Chat.info(player, "Brush set.");
        return true;
    }

    public void openKit(Player player, boolean replaceHeld) {
        if (denied(player)) {
            return;
        }
        Screen screen = new Screen(player, replaceHeld ? "Change brush" : "Brushes",
                () -> openKit(player, replaceHeld)).columns(3);
        screen.line("Right click to paint, sneak to paint on the aimed face, left click to tune.");
        screen.line("A new brush's pattern takes the blocks of your hotbar.");
        if (!replaceHeld && trowel.items().brushOf(player.getInventory().getItemInMainHand()) != null) {
            screen.act("Tune the brush in hand", "Its sliders and its pattern", (in, who) -> openBrush(who));
        }
        for (BrushType type : BrushType.values()) {
            screen.act(type.getDisplayName(), type.getDescription(), (in, who) -> {
                BrushSettings held = trowel.items().brushOf(who.getInventory().getItemInMainHand());
                if (replaceHeld && held != null) {
                    who.getInventory().setItemInMainHand(trowel.items().brush(held.withType(type)
                            .clamp(trowel.settings().maxBrushSize())));
                    openBrush(who);
                    return;
                }
                String pattern = type == BrushType.RAISE ? null : Patterns.hotbar(who);
                trowel.commands().giveBrush(who, BrushSettings.defaults(type, pattern)
                        .clamp(trowel.settings().maxBrushSize()));
            });
        }
        screen.show();
    }

    private static List<String[]> same(List<String> values) {
        return values.stream().map(value -> new String[]{value, value}).toList();
    }

    // ----------------------------------------------------------------- windows

    /** What the player filled in a window. */
    private record Values(DialogResponseView view) {

        String text(String key, String fallback) {
            String value = view.getText(key);
            return value == null ? fallback : value.trim();
        }

        /** A word without spaces, for a command. */
        String word(String key, String fallback) {
            String value = text(key, fallback).replaceAll("\\s+", "");
            return value.isEmpty() ? fallback : value;
        }

        int number(String key, int fallback) {
            Float value = view.getFloat(key);
            return value == null ? fallback : Math.round(value);
        }

        boolean bool(String key) {
            return bool(key, false);
        }

        boolean bool(String key, boolean fallback) {
            Boolean value = view.getBoolean(key);
            return value == null ? fallback : value;
        }

        String mask(String fallback) {
            return maskOf("mask", fallback);
        }

        /** A mask: its spaces become {@code &} so it fits in a single argument. */
        String maskOf(String key, String fallback) {
            String value = text(key, "").replaceAll("\\s+", "&");
            return value.isEmpty() ? fallback : value;
        }

        /** The written pattern, or the chosen palette, without texture. */
        String base() {
            return base("stone");
        }

        String base(String fallback) {
            String palette = text("palette", "none");
            if (!palette.equals("none")) {
                return "#palette:" + palette;
            }
            return word("pattern", fallback);
        }

        /** The full pattern: the base, dressed with the chosen texture. */
        String pattern() {
            return pattern("stone");
        }

        /** The full pattern; an empty field gives {@code fallback}, which may be empty. */
        String pattern(String fallback) {
            String base = base(fallback);
            if (base.isEmpty()) {
                return base;
            }
            String texture = text("texture", "plain");
            int scale = number("tscale", 8);
            return switch (texture) {
                case "plain" -> base;
                case "gradient" -> "#gradient:" + base;
                default -> "#noise:" + texture + ":" + scale + ":" + base;
            };
        }
    }

    /** A window being built: text, fields, buttons. */
    private final class Screen {

        private final Player player;
        private final String title;
        private final Runnable reopen;
        private final List<String> body = new ArrayList<>();
        private final List<DialogInput> inputs = new ArrayList<>();
        private final List<ActionButton> buttons = new ArrayList<>();
        private Runnable parent;
        private int columns = 2;

        private Screen(Player player, String title, Runnable reopen) {
            this.player = player;
            this.title = title;
            this.reopen = reopen;
            this.parent = () -> openHub(player);
        }

        Screen columns(int count) {
            columns = count;
            return this;
        }

        Screen parent(Runnable back) {
            parent = back;
            return this;
        }

        Screen line(String text) {
            body.add(text);
            return this;
        }

        Screen lines(List<String> texts) {
            body.addAll(texts);
            return this;
        }

        Screen text(String key, String label, String initial) {
            inputs.add(DialogInput.text(key, Component.text(label)).initial(clip(initial)).maxLength(256).width(300).build());
            return this;
        }

        Screen number(String key, String label, int min, int max, int initial) {
            inputs.add(DialogInput.numberRange(key, Component.text(label), min, max)
                    .step(1f).initial((float) Math.max(min, Math.min(max, initial))).width(300).build());
            return this;
        }

        Screen choice(String key, String label, List<String[]> options, String initial) {
            boolean found = options.stream().anyMatch(option -> option[0].equals(initial));
            List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
            for (int i = 0; i < options.size(); i++) {
                String[] option = options.get(i);
                entries.add(SingleOptionDialogInput.OptionEntry.create(option[0], Component.text(option[1]),
                        found ? option[0].equals(initial) : i == 0));
            }
            inputs.add(DialogInput.singleOption(key, Component.text(label), entries).width(300).build());
            return this;
        }

        Screen toggle(String key, String label, boolean initial) {
            inputs.add(DialogInput.bool(key, Component.text(label)).initial(initial).build());
            return this;
        }

        /** The pattern, a ready-made palette, and a noise or gradient texture to dress it. */
        Screen pattern(String initial) {
            return pattern(initial, "Pattern (blocks, % and markers)");
        }

        Screen pattern(String initial, String label) {
            text("pattern", label, initial);
            List<String[]> palettes = new ArrayList<>();
            palettes.add(new String[]{"none", "None: the written pattern"});
            Palettes.names().forEach(name -> palettes.add(new String[]{name, name}));
            choice("palette", "Palette", palettes, "none");
            List<String[]> textures = new ArrayList<>();
            textures.add(new String[]{"plain", "Plain (random)"});
            textures.add(new String[]{"gradient", "Gradient from bottom to top"});
            for (Noise.Kind kind : Noise.Kind.values()) {
                textures.add(new String[]{kind.id(), "Noise: " + kind.label()});
            }
            choice("texture", "Texture", textures, "plain");
            number("tscale", "Texture size (blocks)", 1, 64, 8);
            return this;
        }

        /** A button that runs a command and closes the window. */
        Screen run(String label, String tooltip, Function<Values, String> command) {
            return act(label, tooltip, (in, who) -> trowel.commands().execute(who, command.apply(in)));
        }

        /** A button that runs a command and reopens the window, to chain actions. */
        Screen stay(String label, String tooltip, Function<Values, String> command) {
            return act(label, tooltip, (in, who) -> {
                trowel.commands().execute(who, command.apply(in));
                reopen.run();
            });
        }

        Screen act(String label, String tooltip, BiConsumer<Values, Player> action) {
            return act(label, Component.text(tooltip), action);
        }

        Screen act(String label, Component tooltip, BiConsumer<Values, Player> action) {
            buttons.add(ActionButton.builder(Component.text(label))
                    .tooltip(tooltip)
                    .width(columns >= 3 ? 110 : 150)
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player who) {
                            onMain(() -> action.accept(new Values(view), who));
                        }
                    }, ONCE))
                    .build());
            return this;
        }

        void show() {
            Runnable back = parent;
            if (back != null) {
                buttons.add(ActionButton.builder(Component.text("Back"))
                    .tooltip(Component.text("The previous window"))
                    .width(columns >= 3 ? 110 : 150)
                    .action(DialogAction.customClick((view, audience) -> {
                        if (audience instanceof Player) {
                            onMain(back);
                        }
                    }, ONCE))
                    .build());
            }
            List<DialogBody> text = new ArrayList<>();
            for (String line : body) {
                text.add(DialogBody.plainMessage(Component.text(line), 360));
            }
            Dialog dialog = Dialog.create(factory -> factory.empty()
                    .base(DialogBase.builder(Component.text(title, NamedTextColor.AQUA))
                            .canCloseWithEscape(true)
                            .afterAction(DialogBase.DialogAfterAction.CLOSE)
                            .body(text)
                            .inputs(inputs)
                            .build())
                    .type(DialogType.multiAction(buttons)
                            .columns(columns)
                            .exitAction(ActionButton.builder(Component.text("Close")).width(120).build())
                            .build()));
            player.showDialog(dialog);
        }
    }

    /** A dialog field refuses an initial value longer than its limit. */
    private static String clip(String value) {
        return value == null ? "" : value.length() <= 256 ? value : value.substring(0, 256);
    }

    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(trowel.plugin(), task);
        }
    }
}
