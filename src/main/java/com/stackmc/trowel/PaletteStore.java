package com.stackmc.trowel;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What each player saves under a name, one file per player read on demand: palettes
 * ({@code //palette save}, then {@code ##name}, like ezEdits ones), patterns and masks
 * ({@code //pattern save}, {@code //mask save}, then {@code @name}), math formulas
 * ({@code //formula save}, then {@code $name}).
 */
public final class PaletteStore {

    private static final Pattern PALETTE = Pattern.compile("##([A-Za-z0-9_]+)");
    /** {@code @name}, but not the {@code @2} of {@code ~water@2}. */
    private static final Pattern NAMED = Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z][A-Za-z0-9_]*)");
    private static final Pattern FORMULA = Pattern.compile("(?<![A-Za-z0-9_$])\\$([A-Za-z][A-Za-z0-9_]*)");

    /** Ready-made formulas: {@code $name} in //terrain, //poly shape, //spline expr, //generate... */
    public static final Map<String, String> FORMULAS = new TreeMap<>(Map.of(
            "alps", "simplex(ft:ridged,fo:6) 96 100",
            "rolling", "perlin(fo:4) 64 60",
            "terraces", "simplex(fo:4,st:8) 64 100",
            "crackle", "cellular(cd:cheb) 48 70",
            "petals", "1+0.3*cos(5*a)",
            "blob", "1+0.2*sin(3*a)+0.1*cos(7*a)",
            "egg", "1+0.25*cos(a)",
            "ribbed", "1-r+0.2*sin(s)",
            "twisted", "1-r+0.3*cos(4*a+s/2)"));

    private final JavaPlugin plugin;
    private final File folder;
    private final Pattern reference;
    private final String open;
    private final String close;
    private final String kind;
    private final Map<String, String> defaults;
    private final Map<UUID, Map<String, String>> cache = new HashMap<>();

    /** Palettes. */
    public PaletteStore(JavaPlugin plugin) {
        this(plugin, "palettes", PALETTE, "[", "]", "palettes", Map.of());
    }

    private PaletteStore(JavaPlugin plugin, String folder, Pattern reference, String open, String close, String kind,
                         Map<String, String> defaults) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), folder);
        this.reference = reference;
        this.open = open;
        this.close = close;
        this.kind = kind;
        this.defaults = defaults;
    }

    /** Saved patterns: {@code @name} inside a pattern. */
    public static PaletteStore patterns(JavaPlugin plugin) {
        return new PaletteStore(plugin, "patterns", NAMED, "", "", "patterns", Map.of());
    }

    /** Saved masks: {@code @name} inside a mask. */
    public static PaletteStore masks(JavaPlugin plugin) {
        return new PaletteStore(plugin, "masks", NAMED, "", "", "masks", Map.of());
    }

    /** Saved formulas: {@code $name} anywhere in a command, the ready-made ones as fallback. */
    public static PaletteStore formulas(JavaPlugin plugin) {
        return new PaletteStore(plugin, "formulas", FORMULA, "", "", "formulas", FORMULAS);
    }

    public String prefix() {
        return reference == PALETTE ? "##" : reference == FORMULA ? "$" : "@";
    }

    public Map<String, String> of(UUID player) {
        return cache.computeIfAbsent(player, this::load);
    }

    private Map<String, String> load(UUID player) {
        Map<String, String> out = new TreeMap<>();
        File file = new File(folder, player + ".yml");
        if (!file.isFile()) {
            return out;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            String value = yaml.getString(key);
            if (value != null) {
                out.put(key.toLowerCase(Locale.ROOT), value);
            }
        }
        return out;
    }

    public void put(UUID player, String name, String palette) {
        of(player).put(name.toLowerCase(Locale.ROOT), palette);
        write(player);
    }

    public boolean remove(UUID player, String name) {
        boolean removed = of(player).remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            write(player);
        }
        return removed;
    }

    private void write(UUID player) {
        YamlConfiguration yaml = new YamlConfiguration();
        of(player).forEach(yaml::set);
        try {
            folder.mkdirs();
            yaml.save(new File(folder, player + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save the " + kind + " of " + player, e);
        }
    }

    /** Replaces each name the player saved with its content (in brackets for a palette). */
    public String expand(UUID player, String raw) {
        if (raw == null || !raw.contains(prefix())) {
            return raw;
        }
        Map<String, String> mine = of(player);
        if (mine.isEmpty() && defaults.isEmpty()) {
            return raw;
        }
        Matcher matcher = reference.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            String list = mine.getOrDefault(key, defaults.get(key));
            matcher.appendReplacement(out, Matcher.quoteReplacement(list == null ? matcher.group() : open + list + close));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
