package com.stackmc.trowel;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.tags.DialogTagKeys;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * The quick actions, on the G key.
 *
 * <p>The client opens the dialogs tagged {@code minecraft:quick_actions} by itself. A dialog can
 * only join that registry while the server starts, which is why it is registered here, before
 * anything else. It is Trowel's home screen: one button per category, and a few shortcuts. Its
 * buttons only send an id; {@link com.stackmc.trowel.ui.Dialogs} then opens the right window,
 * knowing what the player holds, what they selected and where they stand.</p>
 */
public final class TrowelBootstrap implements PluginBootstrap {

    public static final Key QUICK_DIALOG = Key.key("trowel", "quick_actions");
    public static final String QUICK_PREFIX = "quick/";
    public static final String CATEGORY_PREFIX = "cat/";

    public record Entry(String id, String label, String tooltip) {
    }

    public static final List<Entry> CATEGORIES = List.of(
            new Entry("selection", "Selection", "Corners, expand, contract, spline points, count."),
            new Entry("fill", "Fill and replace", "Fill, walls, outline, hollow, naturalize, trees."),
            new Entry("shapes", "Shapes", "Sphere, ellipsoid, cylinder, pyramid, cone, solid or hollow."),
            new Entry("lines", "Lines and curves", "Line, rope, arch, spline through points."),
            new Entry("splines", "Splines", "Tubes, ropes, chains, springs, blades, scales, noise along the points."),
            new Entry("expressions", "Expressions", "Generate and deform with a math formula."),
            new Entry("terrain", "Terrain and noise", "Generated relief, noise volumes, touch-ups, vegetation."),
            new Entry("sculpt", "Sculpt", "3D smoothing, inflate, rocky surfaces, Voronoi, hexagons, voxels, tendrils."),
            new Entry("textures", "Textures", "Repaint by light, hollows, slope, a noise."),
            new Entry("decor", "Decor", "Vines, moss, noise relief."),
            new Entry("palettes", "Palettes", "View, place, swap, save ordered palettes."),
            new Entry("tools", "Arceon tools", "Roof, road, river, dotted line, trail, revolution, ladder, shadow, "
                    + "smooth snow, text, loft."),
            new Entry("library", "Library", "Shared schematics with thumbnails, named patterns and masks."),
            new Entry("clipboard", "Clipboard", "Copy, cut, paste, rotate, flip."),
            new Entry("move", "Move and repeat", "Move, stack, shift the selection."),
            new Entry("near", "Around me", "Replace, remove, fill holes, no selection needed."),
            new Entry("brushes", "Brushes", "Pick a brush, or tune the one in hand."),
            new Entry("history", "History", "Undo and redo, a single operation, inside the selection, a brush."),
            new Entry("settings", "Settings and help", "Global mask, markers, limits, syntax."));

    public static final List<Entry> SHORTCUTS = List.of(
            new Entry("undo", "Undo", "//undo"),
            new Entry("redo", "Redo", "//redo"),
            new Entry("copy", "Copy", "//copy, around your feet."),
            new Entry("paste", "Paste", "//paste, at your feet."),
            new Entry("wand", "Wand", "//wand"),
            new Entry("brush", "Brush in hand", "Its settings, or the brush list."));

    @Override
    public void bootstrap(BootstrapContext context) {
        TypedKey<Dialog> key = TypedKey.create(RegistryKey.DIALOG, QUICK_DIALOG);
        context.getLifecycleManager().registerEventHandler(RegistryEvents.DIALOG.compose(),
                event -> event.registry().register(key, builder -> builder
                        .base(DialogBase.builder(Component.text("Trowel", NamedTextColor.AQUA))
                                .canCloseWithEscape(true)
                                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                                .body(List.of(DialogBody.plainMessage(Component.text(
                                        "Everything the // commands do, sorted by category. "
                                                + "Operations stay inside your buildable area."), 320)))
                                .build())
                        .type(DialogType.multiAction(buttons())
                                .columns(2)
                                .exitAction(ActionButton.builder(Component.text("Close")).width(150).build())
                                .build())));
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.TAGS.postFlatten(RegistryKey.DIALOG),
                event -> event.registrar().addToTag(DialogTagKeys.QUICK_ACTIONS, List.of(key)));
    }

    private static List<ActionButton> buttons() {
        List<ActionButton> buttons = new ArrayList<>();
        for (Entry category : CATEGORIES) {
            buttons.add(button(category, CATEGORY_PREFIX + category.id()));
        }
        for (Entry shortcut : SHORTCUTS) {
            buttons.add(button(shortcut, shortcut.id()));
        }
        return buttons;
    }

    private static ActionButton button(Entry entry, String id) {
        return ActionButton.builder(Component.text(entry.label()))
                .tooltip(Component.text(entry.tooltip()))
                .width(150)
                .action(DialogAction.customClick(Key.key("trowel", QUICK_PREFIX + id), BinaryTagHolder.binaryTagHolder("{}")))
                .build();
    }
}
