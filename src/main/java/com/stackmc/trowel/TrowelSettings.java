package com.stackmc.trowel;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Trowel's limits, read from {@code config.yml}.
 *
 * <p>They all serve the same purpose: an operation must never freeze the server or fill its
 * memory. Computing happens off the main thread, placing blocks does not: it is spread out.</p>
 *
 * @param maxBlocks       blocks an operation may change
 * @param blocksPerTick   blocks placed per tick
 * @param undoSteps       undoable operations kept per player
 * @param maxChunks       chunks an operation may read
 * @param reach           reach of the wand and brushes, in blocks
 * @param maxBrushSize    largest brush radius
 * @param strokeTicks     two brush strokes closer than this are undone together
 * @param axiomEnabled    Axiom active wherever Trowel is, if AxiomPaper is installed
 * @param axiomPermission Axiom permission granted while the player may build
 */
public record TrowelSettings(int maxBlocks, int blocksPerTick, int undoSteps, int maxChunks,
                              int reach, int maxBrushSize, int strokeTicks, Material wandMaterial,
                              boolean axiomEnabled, String axiomPermission) {

    public static TrowelSettings load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration yaml = plugin.getConfig();
        Material wand = Material.matchMaterial(yaml.getString("wand.material", "WOODEN_AXE"));
        return new TrowelSettings(
                clamp(yaml.getInt("limits.max-blocks", 50_000), 64, 5_000_000),
                clamp(yaml.getInt("limits.blocks-per-tick", 8_192), 64, 200_000),
                clamp(yaml.getInt("limits.undo-steps", 25), 1, 200),
                clamp(yaml.getInt("limits.max-chunks", 1_024), 1, 16_384),
                clamp(yaml.getInt("tools.reach", 160), 8, 512),
                clamp(yaml.getInt("tools.max-brush-size", 64), 1, 64),
                clamp(yaml.getInt("tools.stroke-ticks", 30), 0, 400),
                wand == null || wand.isBlock() ? Material.WOODEN_AXE : wand,
                yaml.getBoolean("axiom.enabled", true),
                yaml.getString("axiom.permission", "axiom.default"));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
