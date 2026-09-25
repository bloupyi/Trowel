package com.stackmc.trowel.api;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.List;
import java.util.Map;

/**
 * The host's marker blocks: blocks that carry settings, stored apart from the world.
 *
 * <p>A marker is a position registered by the host, not a material: a glass placed as an
 * ordinary block stays a block, even if it has a marker's material. Trowel moves, copies and
 * rotates markers with their settings, and never overwrites them by accident. The methods
 * without a world are called off the main thread: they must only read frozen data.</p>
 */
public interface MarkerSupport {

    MarkerSupport NONE = new MarkerSupport() {
        @Override
        public boolean isMarker(Material material) {
            return false;
        }

        @Override
        public Material resolve(String id) {
            return null;
        }

        @Override
        public List<String> ids() {
            return List.of();
        }

        @Override
        public Map<String, ParamKind> kinds(Material marker) {
            return Map.of();
        }

        @Override
        public Map<String, String> effective(Material marker, Map<String, String> params) {
            return params;
        }

        @Override
        public Long2ObjectMap<Map<String, String>> within(World world, Box box) {
            return new Long2ObjectOpenHashMap<>();
        }

        @Override
        public Map<String, String> read(World world, long key) {
            return null;
        }

        @Override
        public void write(World world, long key, Map<String, String> values) {
        }
    };

    /** This material can carry a marker. */
    boolean isMarker(Material material);

    /** Block of a named marker ({@code checkpoint}), or {@code null}. */
    Material resolve(String id);

    /** Marker names, for completion. */
    List<String> ids();

    /** Kind of the settings of this marker that change with a rotation. */
    Map<String, ParamKind> kinds(Material marker);

    /** The settings, completed with the defaults of those that have a kind. */
    Map<String, String> effective(Material marker, Map<String, String> params);

    /** The markers registered in this box and their settings (empty if none). Main thread. */
    Long2ObjectMap<Map<String, String>> within(World world, Box box);

    /** The settings of the marker at this position, or {@code null} if there is none. Main thread. */
    Map<String, String> read(World world, long key);

    /** Registers a marker at this position with these settings, or forgets it ({@code null}). Main thread. */
    void write(World world, long key, Map<String, String> values);
}
