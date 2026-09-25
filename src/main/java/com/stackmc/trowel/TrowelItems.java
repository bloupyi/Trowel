package com.stackmc.trowel;

import com.stackmc.trowel.brush.BrushSettings;
import com.stackmc.trowel.brush.BrushType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * The wand and the brushes. A brush carries its settings: the item is the tool.
 */
public final class TrowelItems {

    public enum Kind { WAND, BRUSH }

    private static final NamespacedKey KIND = new NamespacedKey("trowel", "tool");
    private static final NamespacedKey BRUSH = new NamespacedKey("trowel", "brush");
    /** Tools made before Trowel was its own plugin. */
    private static final NamespacedKey LEGACY_KIND = new NamespacedKey("truelle", "tool");
    private static final NamespacedKey LEGACY_BRUSH = new NamespacedKey("truelle", "brush");

    private final Trowel trowel;

    TrowelItems(Trowel trowel) {
        this.trowel = trowel;
    }

    public ItemStack wand() {
        ItemStack stack = ItemStack.of(trowel.settings().wandMaterial());
        stack.editMeta(meta -> {
            meta.displayName(plain("Selection wand", NamedTextColor.AQUA));
            meta.lore(List.of(
                    plain("Left click: first corner", NamedTextColor.GRAY),
                    plain("Right click: second corner", NamedTextColor.GRAY),
                    plain("Sneak + right click: expand to the block", NamedTextColor.GRAY),
                    plain("Works from afar: aims at the block you look at", NamedTextColor.DARK_GRAY),
                    plain("G: quick actions", NamedTextColor.DARK_GRAY)));
            meta.getPersistentDataContainer().set(KIND, PersistentDataType.STRING, Kind.WAND.name());
            hide(meta);
        });
        return stack;
    }

    public ItemStack brush(BrushSettings settings) {
        BrushType type = settings.type();
        ItemStack stack = ItemStack.of(type.getIcon());
        stack.editMeta(meta -> {
            meta.displayName(plain("Brush: " + type.getDisplayName(), NamedTextColor.LIGHT_PURPLE));
            List<Component> lore = new ArrayList<>();
            lore.add(plain(type.getDescription(), NamedTextColor.GRAY));
            lore.add(Component.empty());
            describe(settings).forEach(line -> lore.add(plain(line, NamedTextColor.AQUA)));
            lore.add(Component.empty());
            lore.add(plain("Right click: paint (sneaking: on the face)", NamedTextColor.DARK_GRAY));
            lore.add(plain("Left click: settings", NamedTextColor.DARK_GRAY));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(KIND, PersistentDataType.STRING, Kind.BRUSH.name());
            meta.getPersistentDataContainer().set(BRUSH, PersistentDataType.STRING, settings.encode());
            meta.setEnchantmentGlintOverride(true);
            hide(meta);
        });
        return stack;
    }

    /** The settings that matter for this brush, one line each. */
    public static List<String> describe(BrushSettings s) {
        List<String> lines = new ArrayList<>();
        BrushType type = s.type();
        if (type.uses(BrushType.Setting.SIZE)) {
            lines.add("Radius: " + s.size());
        }
        if (type.uses(BrushType.Setting.HEIGHT)) {
            lines.add(type.getHeightLabel() + ": " + s.height());
        }
        if (type.uses(BrushType.Setting.INTENSITY)) {
            lines.add("Intensity: " + s.intensity());
        }
        if (type.uses(BrushType.Setting.CHANCE) && s.chance() < 100) {
            lines.add("Chance: " + s.chance() + "%");
        }
        if (type.uses(BrushType.Setting.FALLOFF) && s.falloff() > 0) {
            lines.add(type.label(BrushType.Setting.FALLOFF).replace(" (%)", "") + ": " + s.falloff() + "%");
        }
        if (type.uses(BrushType.Setting.ITERATIONS)) {
            lines.add("Passes: " + s.iterations());
        }
        if (type.uses(BrushType.Setting.PRESET)) {
            lines.add("Mode: " + s.preset());
        }
        if (type.uses(BrushType.Setting.PROFILE)) {
            lines.add("Shape: " + s.profile());
        }
        if (type.uses(BrushType.Setting.NOISE)) {
            lines.add("Noise: " + s.noise());
        }
        if (type.uses(BrushType.Setting.PATTERN) && !s.pattern().isBlank()) {
            lines.add("Pattern: " + shorten(s.pattern()));
        }
        if (type.uses(BrushType.Setting.MASK) && !s.mask().isBlank()) {
            lines.add("Mask: " + shorten(s.mask()));
        }
        if (type.uses(BrushType.Setting.SURFACE) && s.surface()) {
            lines.add("Surface only");
        }
        if (type.uses(BrushType.Setting.RANDOM) && s.random()) {
            lines.add("Random rotation");
        }
        return lines;
    }

    public Kind kind(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer data = stack.getItemMeta().getPersistentDataContainer();
        String raw = data.get(KIND, PersistentDataType.STRING);
        if (raw == null) {
            raw = data.get(LEGACY_KIND, PersistentDataType.STRING);
        }
        if (raw == null) {
            return null;
        }
        try {
            return Kind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isWand(ItemStack stack) {
        return kind(stack) == Kind.WAND;
    }

    public BrushSettings brushOf(ItemStack stack) {
        if (kind(stack) != Kind.BRUSH) {
            return null;
        }
        PersistentDataContainer data = stack.getItemMeta().getPersistentDataContainer();
        String raw = data.get(BRUSH, PersistentDataType.STRING);
        BrushSettings settings = BrushSettings.decode(raw != null ? raw : data.get(LEGACY_BRUSH, PersistentDataType.STRING));
        return settings == null ? null : settings.clamp(trowel.settings().maxBrushSize());
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static String shorten(String text) {
        return text.length() <= 40 ? text : text.substring(0, 37) + "...";
    }

    private static void hide(ItemMeta meta) {
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
    }
}
