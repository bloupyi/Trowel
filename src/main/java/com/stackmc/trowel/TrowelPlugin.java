package com.stackmc.trowel;

import com.stackmc.trowel.api.TrowelApi;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * The plugin itself: starts Trowel and publishes it as a {@link TrowelApi} service.
 */
public final class TrowelPlugin extends JavaPlugin {

    /** Where Trowel kept its data when it lived inside Celeste, and where it keeps it now. */
    private static final Map<String, String> LEGACY = Map.of(
            "truelle.yml", "config.yml",
            "truelle-palettes", "palettes",
            "truelle-patterns", "patterns",
            "truelle-masks", "masks",
            "truelle-schematics", "schematics");

    private Trowel trowel;

    @Override
    public void onEnable() {
        migrate();
        trowel = new Trowel(this);
        trowel.enable();
        getServer().getServicesManager().register(TrowelApi.class, trowel, this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (trowel != null) {
            trowel.disable();
        }
    }

    /** Copies the data of the embedded Trowel once, without touching the originals. */
    private void migrate() {
        File old = new File(getDataFolder().getParentFile(), "Celeste");
        if (!old.isDirectory()) {
            return;
        }
        LEGACY.forEach((from, to) -> {
            Path source = new File(old, from).toPath();
            Path target = new File(getDataFolder(), to).toPath();
            if (!Files.exists(source) || Files.exists(target)) {
                return;
            }
            try {
                copy(source, target);
                getLogger().info("Imported " + from + " from Celeste.");
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Could not import " + from + " from Celeste", e);
            }
        });
    }

    private static void copy(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        try (Stream<Path> files = Files.walk(source)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                Path out = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(out);
                } else {
                    Files.copy(path, out, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
