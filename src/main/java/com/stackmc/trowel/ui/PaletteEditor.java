package com.stackmc.trowel.ui;

import com.stackmc.trowel.Chat;
import com.stackmc.trowel.Trowel;
import com.stackmc.trowel.pattern.Colors;
import com.stackmc.trowel.pattern.Palette;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockDataMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Compose a palette with the mouse: slot order is palette order, a stack's amount is its
 * number of repetitions. Clicking a block in your inventory appends it; move slots to reorder
 * them; Shift + click removes one.
 */
public final class PaletteEditor implements Listener {

    private static final int AREA = 45;
    private static final int SORT = 45;
    private static final int REVERSE = 46;
    private static final int CLEAR = 47;
    private static final int SAVE = 49;
    private static final int CLOSE = 53;

    private final Trowel trowel;

    public PaletteEditor(Trowel trowel) {
        this.trowel = trowel;
    }

    private static final class Editor implements InventoryHolder {
        private final String name;
        private Inventory inventory;

        private Editor(String name) {
            this.name = name;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Opens the editor, filled with the palette of that name if the player has one. */
    public void open(Player player, String name) {
        Editor editor = new Editor(name);
        editor.inventory = Bukkit.createInventory(editor, 54,
                Component.text("Palette ##" + name, NamedTextColor.DARK_AQUA));
        String saved = trowel.palettes().of(player.getUniqueId()).get(name);
        if (saved != null) {
            try {
                fill(editor.inventory, Palette.parse(saved, trowel.host().markers()).blocks());
            } catch (IllegalArgumentException e) {
                Chat.error(player, "The saved palette can no longer be read: " + e.getMessage());
            }
        }
        controls(editor.inventory, name);
        player.openInventory(editor.inventory);
        Chat.info(player, "Click a block in your inventory: it is added. Move slots to reorder, "
                + "Shift + click to remove. A stack's amount = its repetitions.");
    }

    private static void fill(Inventory inventory, List<BlockData> blocks) {
        int slot = 0;
        BlockData previous = null;
        for (BlockData data : blocks) {
            if (previous != null && previous.equals(data) && inventory.getItem(slot - 1) != null
                    && inventory.getItem(slot - 1).getAmount() < 64) {
                ItemStack last = inventory.getItem(slot - 1);
                last.setAmount(last.getAmount() + 1);
                continue;
            }
            if (slot >= AREA) {
                break;
            }
            ItemStack item = item(data);
            if (item != null) {
                inventory.setItem(slot++, item);
                previous = data;
            }
        }
    }

    private static ItemStack item(BlockData data) {
        Material material = data.getMaterial();
        Material shown = material.isItem() ? material : Material.BARRIER;
        ItemStack stack = new ItemStack(shown);
        stack.editMeta(meta -> {
            if (meta instanceof BlockDataMeta blockMeta && shown == material) {
                blockMeta.setBlockData(data);
            }
            if (shown != material) {
                meta.displayName(Component.text(data.getAsString(true).replace("minecraft:", ""), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
        });
        return stack;
    }

    private static void controls(Inventory inventory, String name) {
        inventory.setItem(SORT, button(Material.GLOWSTONE_DUST, "Sort", "From darkest to lightest"));
        inventory.setItem(REVERSE, button(Material.HOPPER, "Reverse", "The last becomes the first"));
        inventory.setItem(CLEAR, button(Material.LAVA_BUCKET, "Clear", "Removes every block"));
        inventory.setItem(SAVE, button(Material.WRITABLE_BOOK, "Save ##" + name, "Then ##" + name
                + " in a pattern, a brush or a spline"));
        inventory.setItem(CLOSE, button(Material.BARRIER, "Close", "Without saving"));
        for (int slot = AREA; slot < 54; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, button(Material.GRAY_STAINED_GLASS_PANE, " ", null));
            }
        }
    }

    private static ItemStack button(Material material, String name, String lore) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(Component.text(name, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            if (lore != null) {
                meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            }
        });
        return stack;
    }

    /** The blocks in slot order, each repeated by its stack. */
    private static List<BlockData> read(Inventory inventory) {
        List<BlockData> blocks = new ArrayList<>();
        for (int slot = 0; slot < AREA; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir() || !stack.getType().isBlock()) {
                continue;
            }
            BlockData data = stack.getItemMeta() instanceof BlockDataMeta meta && meta.hasBlockData()
                    ? meta.getBlockData(stack.getType()) : stack.getType().createBlockData();
            for (int i = 0; i < stack.getAmount(); i++) {
                blocks.add(data);
            }
        }
        return blocks;
    }

    // ------------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Editor editor)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = editor.inventory;
        if (event.getClickedInventory() != null && event.getClickedInventory() != top) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && clicked.getType().isBlock() && !clicked.getType().isAir()) {
                int free = top.firstEmpty();
                if (free < 0 || free >= AREA) {
                    Chat.bar(player, "The palette is full.", NamedTextColor.RED);
                    return;
                }
                ItemStack copy = clicked.clone();
                copy.setAmount(1);
                top.setItem(free, copy);
            }
            return;
        }
        int slot = event.getRawSlot();
        if (slot >= AREA && slot < 54) {
            event.setCancelled(true);
            button(player, editor, slot);
            return;
        }
        if (event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                || event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                || event.getClick() == org.bukkit.event.inventory.ClickType.DROP
                || event.getClick() == org.bukkit.event.inventory.ClickType.CONTROL_DROP) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            if (event.isShiftClick() && slot >= 0 && slot < AREA) {
                top.setItem(slot, null);
            }
            return;
        }
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir() && !cursor.getType().isBlock()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Editor
                && event.getRawSlots().stream().anyMatch(slot -> slot >= AREA)) {
            event.setCancelled(true);
        }
    }

    /** What the player held on the cursor does not go back to their inventory: they are copies. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Editor) {
            event.getPlayer().setItemOnCursor(null);
        }
    }

    private void button(Player player, Editor editor, int slot) {
        Inventory top = editor.inventory;
        switch (slot) {
            case SORT, REVERSE -> {
                List<ItemStack> items = new ArrayList<>();
                for (int i = 0; i < AREA; i++) {
                    ItemStack stack = top.getItem(i);
                    if (stack != null && !stack.getType().isAir()) {
                        items.add(stack);
                    }
                    top.setItem(i, null);
                }
                if (slot == SORT) {
                    items.sort((a, b) -> Double.compare(light(a.getType()), light(b.getType())));
                } else {
                    java.util.Collections.reverse(items);
                }
                for (int i = 0; i < items.size(); i++) {
                    top.setItem(i, items.get(i));
                }
            }
            case CLEAR -> {
                for (int i = 0; i < AREA; i++) {
                    top.setItem(i, null);
                }
            }
            case SAVE -> {
                List<BlockData> blocks = read(top);
                if (blocks.isEmpty()) {
                    Chat.error(player, "The palette is empty: click blocks in your inventory.");
                    return;
                }
                trowel.palettes().put(player.getUniqueId(), editor.name, String.join(",", blocks.stream()
                        .map(data -> data.getAsString(true).replace("minecraft:", "")).toList()));
                player.closeInventory();
                Chat.info(player, "Palette saved: ", "##" + editor.name + " (" + blocks.size() + " blocks)");
            }
            case CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }

    private static double light(Material material) {
        int rgb = Colors.rgb(material);
        if (rgb < 0) {
            return 0.5;
        }
        return (0.2126 * ((rgb >> 16) & 255) + 0.7152 * ((rgb >> 8) & 255) + 0.0722 * (rgb & 255)) / 255.0;
    }
}
