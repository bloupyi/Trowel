package com.stackmc.trowel.ui;

import com.stackmc.trowel.Chat;
import com.stackmc.trowel.PaletteStore;
import com.stackmc.trowel.SchematicLibrary;
import com.stackmc.trowel.Trowel;
import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.brush.BrushSettings;
import com.stackmc.trowel.engine.Batch;
import com.stackmc.trowel.engine.Clipboard;
import com.stackmc.trowel.engine.Progress;
import com.stackmc.trowel.pattern.Masks;
import com.stackmc.trowel.pattern.Patterns;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What is kept: the shared schematic library, named patterns and masks, and the detailed
 * history (undo a single operation, inside the selection, or of a brush).
 */
final class LibraryCommands {

    static final String LIBRARY = "Library";
    private static final String HISTORY = "History";
    private static final int PAGE = 10;

    private final Trowel trowel;
    private final Commands c;

    LibraryCommands(Trowel trowel, Commands commands) {
        this.trowel = trowel;
        this.c = commands;
    }

    void define() {
        c.add("schem|schematic|lib", LIBRARY, "//schem <save|load|list|info|delete> [name] [-p] [-c]",
                "The shared library: save stores the selection (-c: the clipboard instead, -p: private), "
                        + "load brings it back, list shows the thumbnails.", this::schem,
                (player, args) -> args.length == 1
                        ? Commands.filter(List.of("save", "load", "list", "info", "delete"), args[0])
                        : args.length == 2 && !args[0].equalsIgnoreCase("save")
                        ? Commands.filter(trowel.library().list(player.getUniqueId(), "").stream()
                        .map(SchematicLibrary.Entry::name).toList(), args[1])
                        : List.of());
        c.add("pattern", LIBRARY, "//pattern <save|list|show|delete> [name] [pattern]",
                "Named patterns: //pattern save stones 60%stone,40%andesite, then @stones wherever a pattern is asked.",
                (p, a) -> named(p, a, trowel.patterns(), "pattern", true), namedCompleter(true));
        c.add("mask", LIBRARY, "//mask <save|list|show|delete> [name] [mask]",
                "Named masks: //mask save ground #surface&!water, then @ground wherever a mask is asked.",
                (p, a) -> named(p, a, trowel.masks(), "mask", false), namedCompleter(false));
        c.add("history|hist", HISTORY, "//history [page]",
                "The latest operations: click to undo up to one, or only that one.", this::history, c.args("n:1|2"));
    }

    // -------------------------------------------------------------- schematics

    private void schem(Player player, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        String[] plain = Commands.plain(args);
        SchematicLibrary library = trowel.library();
        boolean admin = player.hasPermission("trowel.library.admin");
        switch (sub) {
            case "save" -> {
                String name = Commands.need(plain, 1, "//schem save <name> [-p]").toLowerCase(Locale.ROOT);
                if (!SchematicLibrary.validName(name)) {
                    throw new IllegalArgumentException("Name: lowercase letters, digits, _ and -, 32 at most.");
                }
                boolean shared = !Commands.flags(args).contains("p");
                java.util.function.Consumer<Clipboard> store = clipboard -> library.save(player.getUniqueId(),
                        player.getName(), admin, name, clipboard, shared, error -> {
                            if (error != null) {
                                Chat.error(player, "Saving: " + error);
                                return;
                            }
                            Chat.info(player, "Schematic saved: ", name + " " + clipboard.size()
                                    + (shared ? " (shared)" : " (private)"));
                            info(player, library.find(name));
                        });
                Box selection = trowel.selection(player);
                if (selection != null && !Commands.flags(args).contains("c")) {
                    if (selection.volume() > trowel.settings().maxBlocks(player)) {
                        throw new IllegalArgumentException("Selection too large: " + selection.volume()
                                + " blocks, the maximum is " + trowel.settings().maxBlocks(player) + ".");
                    }
                    org.bukkit.World world = player.getWorld();
                    org.bukkit.block.Block origin = Commands.feet(player);
                    trowel.engine().read(player, world, selection, "Copy",
                            context -> Clipboard.copy(context, selection, world.getUID(), origin.getX(), origin.getY(),
                                    origin.getZ()),
                            clipboard -> {
                                trowel.session(player).setClipboard(clipboard);
                                store.accept(clipboard);
                            });
                    return;
                }
                Clipboard clipboard = trowel.session(player).getClipboard();
                if (clipboard == null) {
                    throw new IllegalArgumentException("Nothing to save: select an area, or //copy first.");
                }
                store.accept(clipboard);
            }
            case "load" -> {
                String name = Commands.need(plain, 1, "//schem load <name>");
                SchematicLibrary.Entry entry = library.find(name);
                if (entry == null || !entry.visibleTo(player.getUniqueId())) {
                    throw new IllegalArgumentException("Unknown schematic: " + name + ". //schem list for the library.");
                }
                Chat.info(player, "Loading ", entry.name() + "...");
                library.load(entry.name(), clipboard -> {
                    trowel.session(player).setClipboard(clipboard);
                    Chat.info(player, "Clipboard: ", entry.name() + " (" + clipboard.size() + ")");
                    Chat.hint(player, "//paste to place it, //rotate and //flip to turn it.");
                }, error -> Chat.error(player, "Loading: " + error));
            }
            case "info" -> {
                SchematicLibrary.Entry entry = library.find(Commands.need(plain, 1, "//schem info <name>"));
                if (entry == null || !entry.visibleTo(player.getUniqueId())) {
                    throw new IllegalArgumentException("Unknown schematic.");
                }
                info(player, entry);
            }
            case "delete", "remove" -> {
                String error = library.delete(Commands.need(plain, 1, "//schem delete <name>"), player.getUniqueId(), admin);
                if (error != null) {
                    throw new IllegalArgumentException(error);
                }
                Chat.info(player, "Schematic deleted.");
            }
            case "list", "search" -> list(player, plain.length > 1 ? plain[1] : "");
            default -> list(player, sub);
        }
    }

    private void list(Player player, String query) {
        int page = 1;
        String search = query;
        try {
            page = Math.max(1, Integer.parseInt(query));
            search = "";
        } catch (NumberFormatException ignored) {
            // A single word: it is a search.
        }
        List<SchematicLibrary.Entry> entries = trowel.library().list(player.getUniqueId(), search);
        if (entries.isEmpty()) {
            Chat.info(player, search.isEmpty() ? "The library is empty: //copy then //schem save <name>."
                    : "No schematic matches '" + search + "'.");
            return;
        }
        int pages = (entries.size() + PAGE - 1) / PAGE;
        page = Math.min(page, pages);
        Chat.info(player, "Library (" + entries.size() + ", page " + page + "/" + pages + "): hover for the thumbnail, click to load.");
        for (SchematicLibrary.Entry e : entries.subList((page - 1) * PAGE, Math.min(entries.size(), page * PAGE))) {
            player.sendMessage(Component.text("  " + e.name(), NamedTextColor.GOLD)
                    .clickEvent(ClickEvent.runCommand("/trowel schem load " + e.name()))
                    .hoverEvent(HoverEvent.showText(SchematicLibrary.render(e.thumbnail())
                            .append(Component.newline())
                            .append(Component.text(e.size() + ", " + e.blocks() + " blocks, by " + e.authorName(),
                                    NamedTextColor.GRAY))))
                    .append(Component.text(" " + e.size() + " by " + e.authorName() + (e.shared() ? "" : " (private)"),
                            NamedTextColor.GRAY)));
        }
        if (page < pages) {
            player.sendMessage(Component.text("  next page", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/trowel schem list " + (page + 1))));
        }
    }

    private void info(Player player, SchematicLibrary.Entry e) {
        if (e == null) {
            return;
        }
        player.sendMessage(SchematicLibrary.render(e.thumbnail()));
        Chat.info(player, e.name() + ": ", e.size() + ", " + e.blocks() + " blocks, by " + e.authorName()
                + (e.shared() ? ", shared" : ", private"));
        player.sendMessage(Component.text("  [load]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/trowel schem load " + e.name())));
    }

    // -------------------------------------------------------- patterns, masks

    private void named(Player player, String[] args, PaletteStore store, String what, boolean pattern) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        Map<String, String> mine = store.of(player.getUniqueId());
        switch (sub) {
            case "save" -> {
                String name = Commands.need(args, 1, "//" + (pattern ? "pattern" : "mask") + " save <name> <" + what + ">")
                        .toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z][a-z0-9_]{0,23}")) {
                    throw new IllegalArgumentException("Name: a letter then letters, digits and _, 24 at most.");
                }
                String value = Commands.join(args, 2);
                if (value.isBlank()) {
                    throw new IllegalArgumentException("Write the " + what + " after the name.");
                }
                if (pattern) {
                    Patterns.parse(Patterns.expand(trowel.expandPattern(player.getUniqueId(), value), player),
                            trowel.host().markers());
                } else {
                    Masks.parse(trowel.expandMask(player.getUniqueId(), value), trowel.host().markers());
                }
                store.put(player.getUniqueId(), name, value);
                Chat.info(player, (pattern ? "Pattern" : "Mask") + " saved: ", "@" + name);
            }
            case "delete", "remove" -> {
                if (!store.remove(player.getUniqueId(), Commands.need(args, 1, "//... delete <name>"))) {
                    throw new IllegalArgumentException("You have no " + what + " with that name.");
                }
                Chat.info(player, (pattern ? "Pattern" : "Mask") + " deleted.");
            }
            case "show" -> {
                String value = mine.get(Commands.need(args, 1, "//... show <name>").toLowerCase(Locale.ROOT));
                if (value == null) {
                    throw new IllegalArgumentException("You have no " + what + " with that name.");
                }
                player.sendMessage(Chat.suggest(value, "click: type"));
            }
            default -> {
                if (mine.isEmpty()) {
                    Chat.info(player, "No saved " + what + ": //" + (pattern ? "pattern" : "mask")
                            + " save <name> <" + what + ">, then @name.");
                    return;
                }
                Chat.info(player, "Your " + what + "s (click: type @name):");
                mine.forEach((name, value) -> player.sendMessage(Component.text("  @" + name, NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.suggestCommand("@" + name))
                        .append(Component.text(" " + value, NamedTextColor.GRAY))));
            }
        }
    }

    private Commands.Completer namedCompleter(boolean pattern) {
        return (player, args) -> {
            if (args.length == 1) {
                return Commands.filter(List.of("save", "list", "show", "delete"), args[0]);
            }
            PaletteStore store = pattern ? trowel.patterns() : trowel.masks();
            if (args.length == 2 && !args[0].equalsIgnoreCase("save")) {
                return Commands.filter(new ArrayList<>(store.of(player.getUniqueId()).keySet()), args[1]);
            }
            if (args.length >= 3 && args[0].equalsIgnoreCase("save")) {
                return pattern ? c.completePattern(args[args.length - 1]) : c.completeMask(args[args.length - 1]);
            }
            return List.of();
        };
    }

    // ----------------------------------------------------------------- history

    private void history(Player player, String[] args) {
        List<Batch> list = trowel.engine().history(player);
        if (list.isEmpty()) {
            Chat.info(player, "The history is empty.");
            return;
        }
        int page = args.length > 0 ? Commands.integer(args[0], 1, 100, "Page") : 1;
        int pages = (list.size() + PAGE - 1) / PAGE;
        page = Math.min(page, pages);
        Chat.info(player, "History (" + list.size() + ", page " + page + "/" + pages + "):");
        long now = System.currentTimeMillis();
        for (int i = (page - 1) * PAGE; i < Math.min(list.size(), page * PAGE); i++) {
            Batch batch = list.get(i);
            int n = i + 1;
            boolean here = batch.world().equals(player.getWorld().getUID());
            player.sendMessage(Component.text("  " + n + ". ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(batch.label(), here ? NamedTextColor.WHITE : NamedTextColor.GRAY))
                    .append(Component.text(" " + Progress.blocks(batch.size()) + ", " + age(now - batch.createdAt())
                            + (here ? "" : " (other world)"), NamedTextColor.GRAY))
                    .append(Component.text(" [up to here]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/trowel undo " + n))
                            .hoverEvent(HoverEvent.showText(Component.text("Undoes the last " + n + " operations"))))
                    .append(Component.text(" [only this one]", NamedTextColor.GOLD)
                            .clickEvent(ClickEvent.runCommand("/trowel undo only " + n))
                            .hoverEvent(HoverEvent.showText(Component.text(
                                    "Undoes only this one: blocks touched since then stay as they are")))));
        }
        if (page < pages) {
            player.sendMessage(Component.text("  next page", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/trowel history " + (page + 1))));
        }
        Chat.hint(player, "//undo sel [n]: only inside the selection. //undo brush: the last gesture of the brush in hand.");
    }

    static String age(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + " s ago";
        }
        if (seconds < 3600) {
            return seconds / 60 + " min ago";
        }
        return seconds / 3600 + " h ago";
    }

    /** {@code //undo [n]}, {@code //undo only <n>}, {@code //undo sel [n]}, {@code //undo brush}. */
    void undo(Player player, String[] args) {
        if (args.length == 0) {
            trowel.engine().undo(player, 1);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "only" -> trowel.engine().undoOnly(player,
                    Commands.integer(Commands.need(args, 1, "//undo only <n>"), 1, 1000, "Number"));
            case "sel", "selection" -> {
                Box box = c.selection(player);
                trowel.engine().undoInside(player, box, args.length > 1 ? Commands.integer(args[1], 1, 1000, "Count") : 1);
            }
            case "brush" -> {
                BrushSettings held = trowel.items().brushOf(player.getInventory().getItemInMainHand());
                if (held == null) {
                    throw new IllegalArgumentException("Hold the brush to undo.");
                }
                trowel.engine().undoStroke(player, "brush:" + player.getInventory().getHeldItemSlot() + ":"
                        + held.type().id());
            }
            default -> trowel.engine().undo(player, Commands.integer(args[0], 1, 100, "Count"));
        }
    }
}
