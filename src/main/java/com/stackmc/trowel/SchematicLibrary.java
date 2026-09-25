package com.stackmc.trowel;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.engine.Clipboard;
import com.stackmc.trowel.api.Keys;
import com.stackmc.trowel.pattern.Colors;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The schematic library: clipboards saved under a name, shared between players (except the
 * ones kept private), markers and settings included.
 *
 * <p>Each schematic has its thumbnail: a colored top view, computed when saving, shown in chat
 * and in windows. Files are read and written off the main thread.</p>
 */
public final class SchematicLibrary {

    private static final int MAGIC = 0x54534348;
    private static final int VERSION = 1;
    private static final int THUMBNAIL = 16;

    /** What the index remembers about a schematic. */
    public record Entry(String name, UUID author, String authorName, long createdAt, int width, int height, int depth,
                        int blocks, boolean shared, String thumbnail) {

        public boolean visibleTo(UUID viewer) {
            return shared || author.equals(viewer);
        }

        public String size() {
            return width + "x" + height + "x" + depth;
        }
    }

    /** A file that was read, waiting for its blocks to be rebuilt on the main thread. */
    private record Raw(Box bounds, List<String> palette, int[] cells, Map<long[], Map<String, String>> params) {
    }

    private final JavaPlugin plugin;
    private final File folder;
    private final File indexFile;
    private final Map<String, Entry> index = new TreeMap<>();

    public SchematicLibrary(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "schematics");
        this.indexFile = new File(folder, "library.yml");
        loadIndex();
    }

    public static boolean validName(String name) {
        return name != null && name.matches("[a-z0-9_-]{1,32}");
    }

    public Entry find(String name) {
        return name == null ? null : index.get(name.toLowerCase(Locale.ROOT));
    }

    /** What this player can see, most recent first, filtered by name or author. */
    public List<Entry> list(UUID viewer, String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry entry : index.values()) {
            if (entry.visibleTo(viewer) && (q.isEmpty() || entry.name().contains(q)
                    || entry.authorName().toLowerCase(Locale.ROOT).contains(q))) {
                out.add(entry);
            }
        }
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    // ------------------------------------------------------------------- index

    private void loadIndex() {
        index.clear();
        if (!indexFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(indexFile);
        for (String name : yaml.getKeys(false)) {
            ConfigurationSection s = yaml.getConfigurationSection(name);
            if (s == null) {
                continue;
            }
            try {
                index.put(name, new Entry(name, UUID.fromString(s.getString("author", "")),
                        s.getString("author-name", "?"), s.getLong("created"), s.getInt("width"), s.getInt("height"),
                        s.getInt("depth"), s.getInt("blocks"), s.getBoolean("shared", true), s.getString("thumbnail", "")));
            } catch (IllegalArgumentException ignored) {
                // A damaged entry is skipped: the rest of the library stays readable.
            }
        }
    }

    private void saveIndex() {
        YamlConfiguration yaml = new YamlConfiguration();
        index.forEach((name, e) -> {
            ConfigurationSection s = yaml.createSection(name);
            s.set("author", e.author().toString());
            s.set("author-name", e.authorName());
            s.set("created", e.createdAt());
            s.set("width", e.width());
            s.set("height", e.height());
            s.set("depth", e.depth());
            s.set("blocks", e.blocks());
            s.set("shared", e.shared());
            s.set("thumbnail", e.thumbnail());
        });
        try {
            folder.mkdirs();
            yaml.save(indexFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save the library index", e);
        }
    }

    // ------------------------------------------------------------------- writing

    /**
     * Saves this clipboard. A taken name can only be overwritten by its author.
     *
     * @param done called on the main thread: {@code null} if all is well, otherwise the reason
     */
    public void save(UUID author, String authorName, boolean admin, String name, Clipboard clipboard, boolean shared,
                     Consumer<String> done) {
        String key = name.toLowerCase(Locale.ROOT);
        Entry existing = index.get(key);
        if (existing != null && !existing.author().equals(author) && !admin) {
            done.accept("'" + key + "' is already taken by " + existing.authorName() + ".");
            return;
        }
        Box b = clipboard.bounds();
        Map<String, Integer> palette = new LinkedHashMap<>();
        Long2ObjectMap<BlockData> cells = clipboard.cells();
        int[] packed = new int[cells.size() * 4];
        int i = 0;
        int solid = 0;
        for (Long2ObjectMap.Entry<BlockData> cell : cells.long2ObjectEntrySet()) {
            long k = cell.getLongKey();
            String state = cell.getValue().getAsString();
            Integer id = palette.computeIfAbsent(state, s -> palette.size());
            packed[i++] = Keys.x(k);
            packed[i++] = Keys.y(k);
            packed[i++] = Keys.z(k);
            packed[i++] = id;
            if (!cell.getValue().getMaterial().isAir()) {
                solid++;
            }
        }
        Map<Long, Map<String, String>> params = new HashMap<>(clipboard.params());
        Entry entry = new Entry(key, author, authorName, System.currentTimeMillis(), b.width(), b.height(), b.depth(),
                solid, shared, thumbnail(clipboard));
        List<String> states = new ArrayList<>(palette.keySet());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String error = null;
            try {
                write(new File(folder, key + ".tsch"), b, states, packed, params);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not save schematic " + key, e);
                error = "could not write (" + e.getMessage() + ").";
            }
            String result = error;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (result == null) {
                    index.put(key, entry);
                    saveIndex();
                }
                done.accept(result);
            });
        });
    }

    private static void write(File file, Box b, List<String> states, int[] cells, Map<Long, Map<String, String>> params)
            throws IOException {
        file.getParentFile().mkdirs();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(b.minX());
            out.writeInt(b.minY());
            out.writeInt(b.minZ());
            out.writeInt(b.maxX());
            out.writeInt(b.maxY());
            out.writeInt(b.maxZ());
            out.writeInt(states.size());
            for (String state : states) {
                out.writeUTF(state);
            }
            out.writeInt(cells.length / 4);
            for (int value : cells) {
                out.writeInt(value);
            }
            out.writeInt(params.size());
            for (Map.Entry<Long, Map<String, String>> p : params.entrySet()) {
                out.writeInt(Keys.x(p.getKey()));
                out.writeInt(Keys.y(p.getKey()));
                out.writeInt(Keys.z(p.getKey()));
                out.writeInt(p.getValue().size());
                for (Map.Entry<String, String> v : p.getValue().entrySet()) {
                    out.writeUTF(v.getKey());
                    out.writeUTF(v.getValue());
                }
            }
        }
    }

    // ------------------------------------------------------------------- reading

    /** Loads a schematic; {@code done} gets the clipboard, or {@code failed} the reason. */
    public void load(String name, Consumer<Clipboard> done, Consumer<String> failed) {
        String key = name.toLowerCase(Locale.ROOT);
        File file = new File(folder, key + ".tsch");
        if (!index.containsKey(key) || !file.isFile()) {
            failed.accept("Unknown schematic: " + key + ". //schem list for the library.");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Raw raw;
            try {
                raw = read(file);
            } catch (IOException e) {
                Bukkit.getScheduler().runTask(plugin, () -> failed.accept("could not read (" + e.getMessage() + ")."));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    done.accept(build(raw));
                } catch (IllegalArgumentException e) {
                    failed.accept("a block of this schematic does not exist in this version.");
                }
            });
        });
    }

    private static Raw read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readInt() != MAGIC || in.readInt() > VERSION) {
                throw new IOException("unknown format");
            }
            Box bounds = new Box(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
            int states = in.readInt();
            List<String> palette = new ArrayList<>(states);
            for (int i = 0; i < states; i++) {
                palette.add(in.readUTF());
            }
            int count = in.readInt();
            int[] cells = new int[count * 4];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = in.readInt();
            }
            int paramCount = in.readInt();
            Map<long[], Map<String, String>> params = new LinkedHashMap<>();
            for (int i = 0; i < paramCount; i++) {
                long[] at = {in.readInt(), in.readInt(), in.readInt()};
                int n = in.readInt();
                Map<String, String> values = new LinkedHashMap<>();
                for (int j = 0; j < n; j++) {
                    values.put(in.readUTF(), in.readUTF());
                }
                params.put(at, values);
            }
            return new Raw(bounds, palette, cells, params);
        }
    }

    private static Clipboard build(Raw raw) {
        List<BlockData> states = new ArrayList<>();
        for (String state : raw.palette()) {
            states.add(Bukkit.createBlockData(state));
        }
        Long2ObjectOpenHashMap<BlockData> cells = new Long2ObjectOpenHashMap<>(raw.cells().length / 4);
        int[] c = raw.cells();
        for (int i = 0; i < c.length; i += 4) {
            cells.put(Keys.pack(c[i], c[i + 1], c[i + 2]), states.get(c[i + 3]));
        }
        Long2ObjectOpenHashMap<Map<String, String>> params = new Long2ObjectOpenHashMap<>();
        raw.params().forEach((at, values) -> params.put(Keys.pack((int) at[0], (int) at[1], (int) at[2]), values));
        return Clipboard.of(cells, params, raw.bounds());
    }

    // ------------------------------------------------------------------ removing

    /** @return the reason for refusing, or {@code null} */
    public String delete(String name, UUID requester, boolean admin) {
        String key = name.toLowerCase(Locale.ROOT);
        Entry entry = index.get(key);
        if (entry == null) {
            return "Unknown schematic: " + key + ".";
        }
        if (!entry.author().equals(requester) && !admin) {
            return "Only " + entry.authorName() + " can delete " + key + ".";
        }
        index.remove(key);
        saveIndex();
        new File(folder, key + ".tsch").delete();
        return null;
    }

    // ----------------------------------------------------------------- thumbnail

    /**
     * Top view: for each column, the color of the highest block, a bit lighter when higher.
     * Reduced to {@value #THUMBNAIL} cells per side, hex encoded (dashes: empty).
     */
    public static String thumbnail(Clipboard clipboard) {
        Box b = clipboard.bounds();
        int w = Math.min(THUMBNAIL, b.width());
        int d = Math.min(THUMBNAIL, b.depth());
        StringBuilder out = new StringBuilder().append(w).append(';').append(d).append(';');
        for (int pz = 0; pz < d; pz++) {
            for (int px = 0; px < w; px++) {
                int x = b.minX() + px * b.width() / w;
                int z = b.minZ() + pz * b.depth() / d;
                int rgb = -1;
                for (int y = b.maxY(); y >= b.minY() && rgb < 0; y--) {
                    BlockData data = clipboard.at(x, y, z);
                    if (data != null && !data.getMaterial().isAir()) {
                        int base = Colors.rgb(data.getMaterial());
                        if (base >= 0) {
                            double light = 0.65 + 0.35 * (y - b.minY() + 1) / b.height();
                            rgb = shade(base, light);
                        }
                    }
                }
                out.append(rgb < 0 ? "------" : String.format("%06x", rgb));
            }
        }
        return out.toString();
    }

    private static int shade(int rgb, double f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * f);
        int g = (int) Math.min(255, ((rgb >> 8) & 255) * f);
        int b = (int) Math.min(255, (rgb & 255) * f);
        return (r << 16) | (g << 8) | b;
    }

    /** The thumbnail as lines of colored squares, for chat or a tooltip. */
    public static Component render(String code) {
        String[] parts = code == null ? new String[0] : code.split(";", 3);
        if (parts.length < 3) {
            return Component.text("(no thumbnail)");
        }
        int w;
        int d;
        try {
            w = Integer.parseInt(parts[0]);
            d = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return Component.text("(no thumbnail)");
        }
        String pixels = parts[2];
        Component out = Component.empty();
        for (int z = 0; z < d; z++) {
            if (z > 0) {
                out = out.append(Component.newline());
            }
            for (int x = 0; x < w; x++) {
                int at = (z * w + x) * 6;
                if (at + 6 > pixels.length()) {
                    break;
                }
                String hex = pixels.substring(at, at + 6);
                int rgb = hex.startsWith("-") ? 0x1c1c1c : Integer.parseInt(hex, 16);
                out = out.append(Component.text("█", TextColor.color(rgb)));
            }
        }
        return out;
    }
}
