package com.stackmc.trowel.pattern;

import com.stackmc.trowel.api.MarkerSupport;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A palette: blocks in an order that matters, from first to last. A noise, a gradient or an
 * expression picks a block by its place: 0 gives the first, 1 the last.
 *
 * <p>The syntax is ezEdits':</p>
 * <ul>
 *   <li>{@code stone,andesite,##grayscale}: one after the other;</li>
 *   <li>{@code -##magma}: reversed;</li>
 *   <li>{@code ##grayscale(3:8)}: from the third to the eighth;</li>
 *   <li>{@code gold_block*10}: repeated;</li>
 *   <li>{@code -[##magma,gold_block]}: a group, treated as a single piece;</li>
 *   <li>{@code 60%stone}: the weight becomes a repetition (6 times for 3 times 20%).</li>
 * </ul>
 */
public final class Palette {

    /** Ready-made palettes, ordered dark to light or from one end of a hue to the other. */
    public static final Map<String, String> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("grayscale", "black_concrete,coal_block,blackstone,polished_blackstone,deepslate,cobbled_deepslate,"
                + "gray_concrete,tuff,andesite,stone,light_gray_concrete,diorite,calcite,white_concrete,snow_block");
        PRESETS.put("graywarm", "black_terracotta,blackstone,gray_terracotta,mud_bricks,tuff,light_gray_terracotta,"
                + "andesite,smooth_stone,calcite,white_terracotta");
        PRESETS.put("graycold", "black_concrete,deepslate_tiles,polished_deepslate,cobbled_deepslate,gray_concrete,"
                + "stone,cyan_terracotta,light_gray_concrete,diorite,packed_ice,snow_block");
        PRESETS.put("stone", "cobbled_deepslate,tuff,cobblestone,andesite,stone,stone_bricks,smooth_stone,diorite");
        PRESETS.put("deepslate", "deepslate_tiles,deepslate_bricks,polished_deepslate,cobbled_deepslate,deepslate,tuff");
        PRESETS.put("magma", "black_concrete,blackstone,red_nether_bricks,nether_wart_block,red_concrete,magma_block,"
                + "orange_concrete,shroomlight,ochre_froglight,yellow_concrete");
        PRESETS.put("lava", "blackstone,basalt,magma_block,orange_concrete,shroomlight,ochre_froglight,glowstone");
        PRESETS.put("fire", "red_concrete,orange_concrete,orange_terracotta,yellow_concrete,yellow_glazed_terracotta,"
                + "glowstone");
        PRESETS.put("glowblue", "black_concrete,blue_concrete,lapis_block,blue_wool,light_blue_concrete,light_blue_wool,"
                + "packed_ice,sea_lantern,white_concrete");
        PRESETS.put("glowpurple", "black_concrete,purple_concrete,purple_wool,amethyst_block,magenta_concrete,purpur_block,"
                + "pink_concrete,pearlescent_froglight");
        PRESETS.put("gloworange", "brown_concrete,orange_terracotta,orange_concrete,orange_wool,shroomlight,ochre_froglight,"
                + "glowstone,yellow_concrete");
        PRESETS.put("glowgreen", "black_concrete,green_concrete,green_wool,lime_concrete,lime_wool,verdant_froglight,"
                + "slime_block");
        PRESETS.put("ice", "blue_ice,packed_ice,ice,light_blue_concrete_powder,snow_block,powder_snow,white_concrete");
        PRESETS.put("ocean", "dark_prismarine,prismarine_bricks,prismarine,warped_wart_block,cyan_concrete,sea_lantern");
        PRESETS.put("blue", "blue_concrete,blue_wool,blue_terracotta,light_blue_terracotta,light_blue_concrete,"
                + "light_blue_wool,white_concrete");
        PRESETS.put("red", "black_concrete,red_nether_bricks,red_terracotta,red_concrete,red_wool,pink_terracotta,pink_concrete");
        PRESETS.put("green", "black_concrete,green_terracotta,green_concrete,moss_block,lime_terracotta,lime_concrete,"
                + "lime_wool");
        PRESETS.put("moss", "mossy_cobblestone,mossy_stone_bricks,moss_block,green_concrete_powder,azalea_leaves");
        PRESETS.put("grass", "coarse_dirt,podzol,dirt,rooted_dirt,moss_block,grass_block");
        PRESETS.put("dirt", "mud,packed_mud,coarse_dirt,dirt,rooted_dirt,dirt_path");
        PRESETS.put("sand", "sandstone,cut_sandstone,smooth_sandstone,sand,end_stone,white_concrete_powder");
        PRESETS.put("redsand", "red_sandstone,cut_red_sandstone,smooth_red_sandstone,red_sand,orange_terracotta");
        PRESETS.put("badlands", "brown_terracotta,red_terracotta,orange_terracotta,terracotta,yellow_terracotta,"
                + "white_terracotta,light_gray_terracotta");
        PRESETS.put("brown", "black_concrete,dark_oak_planks,brown_concrete,brown_terracotta,spruce_planks,"
                + "brown_mushroom_block,oak_planks,birch_planks");
        PRESETS.put("bark", "dark_oak_wood,spruce_wood,brown_terracotta,jungle_wood,oak_wood,stripped_spruce_wood,"
                + "mud_bricks,stripped_oak_wood");
        PRESETS.put("wood", "dark_oak_planks,spruce_planks,jungle_planks,oak_planks,birch_planks,stripped_birch_log");
        PRESETS.put("cherry", "cherry_wood,cherry_planks,pink_terracotta,pink_concrete,cherry_leaves,pink_wool");
        PRESETS.put("nether", "blackstone,nether_bricks,netherrack,crimson_nylium,nether_wart_block,magma_block");
        PRESETS.put("warped", "warped_wart_block,warped_nylium,warped_planks,cyan_terracotta,prismarine");
        PRESETS.put("end", "obsidian,purpur_pillar,purpur_block,end_stone_bricks,end_stone,white_concrete");
        PRESETS.put("copper", "oxidized_copper,weathered_copper,exposed_copper,copper_block,cut_copper,raw_copper_block");
        PRESETS.put("gold", "raw_gold_block,gold_block,yellow_concrete,yellow_glazed_terracotta,glowstone");
        PRESETS.put("sunset", "purple_concrete,magenta_concrete,pink_concrete,red_concrete,orange_concrete,yellow_concrete");
        PRESETS.put("rainbow", "red_concrete,orange_concrete,yellow_concrete,lime_concrete,green_concrete,cyan_concrete,"
                + "light_blue_concrete,blue_concrete,purple_concrete,magenta_concrete");
        PRESETS.put("snow", "light_gray_concrete_powder,calcite,snow_block,powder_snow,white_concrete");
        PRESETS.put("crystal", "purple_concrete,amethyst_block,purpur_block,calcite,pink_concrete_powder,white_concrete");
        PRESETS.put("glass", "black_stained_glass,gray_stained_glass,light_gray_stained_glass,white_stained_glass,glass");
        PRESETS.put("bluestained", "blue_stained_glass,light_blue_stained_glass,cyan_stained_glass,white_stained_glass,glass");
        PRESETS.put("mud", "mud,packed_mud,mud_bricks,brown_terracotta,dirt");
        PRESETS.put("basalt", "blackstone,basalt,polished_basalt,smooth_basalt,tuff");
        PRESETS.put("marble", "polished_andesite,diorite,polished_diorite,calcite,quartz_block,white_concrete");
    }

    private final List<BlockData> blocks;

    public Palette(List<BlockData> blocks) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Empty palette.");
        }
        this.blocks = List.copyOf(blocks);
    }

    public List<BlockData> blocks() {
        return blocks;
    }

    public int size() {
        return blocks.size();
    }

    public BlockData get(int index) {
        return blocks.get(Math.max(0, Math.min(blocks.size() - 1, index)));
    }

    /** The block at this place, from 0 (the first) to 1 (the last). */
    public BlockData pick(double t) {
        int n = blocks.size();
        int i = (int) Math.floor(t * n);
        return blocks.get(Math.max(0, Math.min(n - 1, i)));
    }

    /**
     * The block an expression value picks, like ezEdits: ]0, 1] walks the palette, 1 and more
     * give the last block, 0 and less nothing at all.
     */
    public BlockData value(double v) {
        if (!(v > 0)) {
            return null;
        }
        int n = blocks.size();
        return blocks.get(Math.max(0, Math.min(n - 1, (int) Math.ceil(Math.min(1, v) * n) - 1)));
    }

    /** Block number {@code v} (1 for the first), rounded up; nothing if {@code v <= 0}. */
    public BlockData index(double v) {
        if (!(v > 0)) {
            return null;
        }
        return get((int) Math.ceil(v) - 1);
    }

    public static List<String> presets() {
        return List.copyOf(PRESETS.keySet());
    }

    /** Reads a palette; blocks are read by {@link Patterns#block}. */
    public static Palette parse(String raw, MarkerSupport markers) {
        List<String> tokens = parseTokens(raw);
        List<BlockData> out = new ArrayList<>();
        for (String token : tokens) {
            out.add(Patterns.block(token, markers));
        }
        return new Palette(out);
    }

    /** The palette written as block names, without its modifiers: for display. */
    public static List<String> parseTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty palette.");
        }
        Reader reader = new Reader(raw.trim());
        List<String> out = reader.sequence(0);
        if (reader.pos < reader.text.length()) {
            throw new IllegalArgumentException("Palette: unexpected '" + reader.text.substring(reader.pos) + "'.");
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Empty palette.");
        }
        if (out.size() > 4096) {
            throw new IllegalArgumentException("Palette too long (" + out.size() + " blocks).");
        }
        return out;
    }

    private static final class Reader {
        final String text;
        int pos;

        Reader(String text) {
            this.text = text;
        }

        List<String> sequence(int depth) {
            List<String> out = new ArrayList<>();
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ']' && depth > 0) {
                    break;
                }
                if (c == ',' || Character.isWhitespace(c)) {
                    pos++;
                    continue;
                }
                out.addAll(segment(depth));
            }
            return out;
        }

        List<String> segment(int depth) {
            boolean invert = false;
            while (pos < text.length() && text.charAt(pos) == '-') {
                invert = !invert;
                pos++;
            }
            List<String> items;
            if (pos < text.length() && text.charAt(pos) == '[') {
                pos++;
                items = sequence(depth + 1);
                if (pos >= text.length() || text.charAt(pos) != ']') {
                    throw new IllegalArgumentException("Palette: missing ']'.");
                }
                pos++;
            } else {
                items = atom();
            }
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '(') {
                    int close = text.indexOf(')', pos);
                    if (close < 0) {
                        throw new IllegalArgumentException("Palette: missing ')'.");
                    }
                    String[] range = text.substring(pos + 1, close).split(":");
                    pos = close + 1;
                    int from = range[0].isBlank() ? 1 : integer(range[0]);
                    int to = range.length < 2 || range[1].isBlank() ? items.size() : integer(range[1]);
                    from = Math.max(1, Math.min(items.size(), from));
                    to = Math.max(from, Math.min(items.size(), to));
                    items = new ArrayList<>(items.subList(from - 1, to));
                } else if (c == '*') {
                    pos++;
                    int start = pos;
                    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                        pos++;
                    }
                    int times = Math.max(1, Math.min(256, integer(text.substring(start, pos))));
                    List<String> repeated = new ArrayList<>();
                    for (int i = 0; i < times; i++) {
                        repeated.addAll(items);
                    }
                    items = repeated;
                } else {
                    break;
                }
            }
            if (invert) {
                items = new ArrayList<>(items);
                Collections.reverse(items);
            }
            return items;
        }

        /** A block (with its state in brackets), a ##name palette, or a 60%block weight. */
        List<String> atom() {
            int start = pos;
            int bracket = 0;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '[') {
                    bracket++;
                } else if (c == ']') {
                    if (bracket == 0) {
                        break;
                    }
                    bracket--;
                } else if (bracket == 0 && (c == ',' || c == '(' || c == '*' || Character.isWhitespace(c))) {
                    break;
                }
                pos++;
            }
            String token = text.substring(start, pos).trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Palette: block expected at position " + (start + 1) + ".");
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("##")) {
                String list = PRESETS.get(lower.substring(2));
                if (list == null) {
                    throw new IllegalArgumentException("Unknown palette: " + token + ". Palettes: ##"
                            + String.join(", ##", PRESETS.keySet()));
                }
                return new ArrayList<>(List.of(list.split(",")));
            }
            if (lower.startsWith("#palette:")) {
                String list = Palettes.ALL.get(lower.substring("#palette:".length()));
                if (list == null) {
                    list = PRESETS.get(lower.substring("#palette:".length()));
                }
                if (list == null) {
                    throw new IllegalArgumentException("Unknown palette: " + token);
                }
                List<String> out = new ArrayList<>();
                for (String part : list.split(",")) {
                    out.add(part.replaceFirst("^\\d+(\\.\\d+)?%", ""));
                }
                return out;
            }
            java.util.regex.Matcher weight = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)%(.+)$").matcher(token);
            if (weight.matches()) {
                int times = (int) Math.max(1, Math.min(100, Math.round(Double.parseDouble(weight.group(1)) / 10)));
                List<String> out = new ArrayList<>();
                for (int i = 0; i < times; i++) {
                    out.add(weight.group(2));
                }
                return out;
            }
            return new ArrayList<>(List.of(token));
        }

        private static int integer(String raw) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Palette: number expected, not '" + raw + "'.");
            }
        }
    }
}
