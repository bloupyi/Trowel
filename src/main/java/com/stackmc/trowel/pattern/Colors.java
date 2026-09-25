package com.stackmc.trowel.pattern;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Block colors, like Arceon's {@code #cc}: find the block closest to a color, or recognize
 * the blocks of a color.
 *
 * <p>A block's color is the one it has on a map. It is read on the main thread, once and for
 * all, before computations use it elsewhere.</p>
 */
public final class Colors {

    /** Blocks offered for a color: solid, unsurprising, good to build with. */
    private static final List<String> CANDIDATES = List.of("stone", "andesite", "diorite", "granite", "deepslate",
            "cobbled_deepslate", "tuff", "calcite", "sandstone", "red_sandstone", "dirt", "coarse_dirt", "mud", "packed_mud",
            "clay", "snow_block", "packed_ice", "blue_ice", "oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
            "acacia_planks", "dark_oak_planks", "mangrove_planks", "cherry_planks", "crimson_planks", "warped_planks",
            "prismarine", "dark_prismarine", "nether_bricks", "red_nether_bricks", "quartz_block", "blackstone", "basalt",
            "obsidian", "moss_block", "copper_block", "exposed_copper", "weathered_copper", "oxidized_copper",
            "amethyst_block", "end_stone", "purpur_block", "gold_block", "iron_block", "emerald_block", "diamond_block",
            "lapis_block", "redstone_block", "netherrack", "bone_block", "honeycomb_block", "brown_mushroom_block",
            "red_mushroom_block", "mushroom_stem", "sea_lantern", "glowstone", "shroomlight", "magma_block",
            "nether_wart_block", "warped_wart_block", "raw_iron_block", "raw_copper_block", "raw_gold_block", "coal_block",
            "hay_block", "melon", "pumpkin", "dried_kelp_block", "slime_block", "sponge", "terracotta");

    private static volatile Map<Material, Integer> colors;
    private static volatile List<BlockData> candidates;

    private Colors() {
    }

    /** Reads the color of each block. On the main thread, before the first computation that needs it. */
    public static void prepare() {
        if (colors != null) {
            return;
        }
        Map<Material, Integer> map = new EnumMap<>(Material.class);
        List<BlockData> list = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isLegacy() || !material.isBlock() || material.isAir()) {
                continue;
            }
            try {
                org.bukkit.Color color = material.createBlockData().getMapColor();
                map.put(material, color.asRGB());
            } catch (RuntimeException ignored) {
                // A technical block without a color stays out of the table.
            }
        }
        for (String name : CANDIDATES) {
            add(list, name);
        }
        for (DyeColor dye : DyeColor.values()) {
            String prefix = dye.name().toLowerCase(Locale.ROOT);
            add(list, prefix + "_concrete");
            add(list, prefix + "_wool");
            add(list, prefix + "_terracotta");
        }
        colors = map;
        candidates = list;
    }

    private static void add(List<BlockData> list, String name) {
        Material material = Material.matchMaterial(name);
        if (material != null && material.isBlock()) {
            list.add(material.createBlockData());
        }
    }

    public static int rgb(Material material) {
        Map<Material, Integer> map = colors;
        Integer value = map == null ? null : map.get(material);
        return value == null ? -1 : value;
    }

    /** {@code #ff8800}, {@code ff8800}, {@code 255,136,0}, or a dye name ({@code orange}). */
    public static int parse(String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        for (DyeColor dye : DyeColor.values()) {
            if (dye.name().toLowerCase(Locale.ROOT).equals(text)) {
                return dye.getColor().asRGB();
            }
        }
        try {
            if (text.contains(",")) {
                String[] parts = text.split(",");
                return (clamp(Integer.parseInt(parts[0].trim())) << 16) | (clamp(Integer.parseInt(parts[1].trim())) << 8)
                        | clamp(Integer.parseInt(parts[2].trim()));
            }
            String hex = text.startsWith("#") ? text.substring(1) : text;
            if (hex.length() == 6) {
                return Integer.parseInt(hex, 16);
            }
        } catch (RuntimeException ignored) {
            // Falls through to the message below.
        }
        throw new IllegalArgumentException("Unreadable color: " + raw + " (#ff8800, 255,136,0 or a name: red, blue...).");
    }

    /** The {@code count} blocks closest to this color, from closest to farthest. */
    public static List<BlockData> closest(int rgb, int count) {
        prepare();
        List<BlockData> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(data -> distance(rgb, rgb(data.getMaterial()))));
        return sorted.subList(0, Math.max(1, Math.min(count, sorted.size())));
    }

    /** Distance between two colors, weighted like the eye (redmean). */
    public static double distance(int a, int b) {
        if (a < 0 || b < 0) {
            return Double.MAX_VALUE;
        }
        int r1 = (a >> 16) & 255;
        int g1 = (a >> 8) & 255;
        int b1 = a & 255;
        int r2 = (b >> 16) & 255;
        int g2 = (b >> 8) & 255;
        int b2 = b & 255;
        double mean = (r1 + r2) / 2.0;
        double dr = r1 - r2;
        double dg = g1 - g2;
        double db = b1 - b2;
        return Math.sqrt((2 + mean / 256) * dr * dr + 4 * dg * dg + (2 + (255 - mean) / 256) * db * db) / 3;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
