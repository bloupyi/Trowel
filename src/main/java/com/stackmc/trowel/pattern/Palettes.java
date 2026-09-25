package com.stackmc.trowel.pattern;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Ready-made palettes, like the ezEdits ones: {@code #palette:stone} stands for a list of
 * blocks that go well together. They fit in any pattern:
 * {@code #noise:cracks:6:#palette:deepslate}.
 */
public final class Palettes {

    private static final java.util.regex.Pattern REFERENCE =
            java.util.regex.Pattern.compile("(?i)#palette:([a-z_]+)");

    public static final Map<String, String> ALL = new LinkedHashMap<>();

    static {
        ALL.put("stone", "stone,andesite,cobblestone,tuff,gravel");
        ALL.put("deepslate", "deepslate,cobbled_deepslate,tuff,deepslate_tiles,polished_deepslate");
        ALL.put("bricks", "stone_bricks,cracked_stone_bricks,mossy_stone_bricks,andesite");
        ALL.put("mossy", "mossy_cobblestone,cobblestone,moss_block,mossy_stone_bricks");
        ALL.put("grass", "grass_block,moss_block,podzol,coarse_dirt");
        ALL.put("dirt", "dirt,coarse_dirt,rooted_dirt,mud,packed_mud");
        ALL.put("sand", "sand,sandstone,smooth_sandstone,cut_sandstone");
        ALL.put("badlands", "terracotta,orange_terracotta,red_terracotta,yellow_terracotta,brown_terracotta,"
                + "light_gray_terracotta");
        ALL.put("snow", "snow_block,packed_ice,calcite,white_concrete_powder");
        ALL.put("ice", "ice,packed_ice,blue_ice");
        ALL.put("nether", "netherrack,blackstone,basalt,magma_block,nether_bricks");
        ALL.put("end", "end_stone,end_stone_bricks,purpur_block");
        ALL.put("wood", "oak_planks,spruce_planks,dark_oak_planks,stripped_oak_log");
        ALL.put("dark", "blackstone,polished_blackstone,basalt,deepslate,black_concrete");
        ALL.put("ocean", "prismarine,prismarine_bricks,dark_prismarine");
        ALL.put("crystal", "amethyst_block,calcite,purpur_block");
        ALL.put("basalt", "basalt,polished_basalt,smooth_basalt,blackstone");
        ALL.put("marble", "calcite,diorite,polished_diorite,quartz_block,white_concrete");
        ALL.put("copper", "copper_block,exposed_copper,weathered_copper,oxidized_copper");
        ALL.put("mud", "mud,packed_mud,mud_bricks,dirt");
        ALL.put("flowers", "40%short_grass,10%poppy,10%dandelion,10%cornflower,10%oxeye_daisy,10%azure_bluet,10%fern");
        ALL.put("foliage", "80%short_grass,20%fern");
    }

    private Palettes() {
    }

    public static List<String> names() {
        return List.copyOf(ALL.keySet());
    }

    /** Replaces each {@code #palette:name} with its block list. */
    public static String expand(String raw) {
        if (raw == null || !raw.toLowerCase(Locale.ROOT).contains("#palette:")) {
            return raw;
        }
        Matcher matcher = REFERENCE.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String list = ALL.get(matcher.group(1).toLowerCase(Locale.ROOT));
            if (list == null) {
                throw new IllegalArgumentException("Unknown palette: " + matcher.group(1)
                        + ". Palettes: " + String.join(", ", ALL.keySet()));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(list));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
