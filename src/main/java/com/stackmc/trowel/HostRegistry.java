package com.stackmc.trowel;

import com.stackmc.trowel.api.Box;
import com.stackmc.trowel.api.MarkerSupport;
import com.stackmc.trowel.api.ParamKind;
import com.stackmc.trowel.api.TrowelHost;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every registered host seen as one: each world is governed by the first host that handles it,
 * and by the default rules when none does.
 *
 * <p>The rest of Trowel only talks to this object, so a host can come and go (a plugin reload)
 * without anything else noticing.</p>
 */
final class HostRegistry implements TrowelHost {

    /** Worlds no host claims: the permission decides, there is no area and no marker. */
    private static final TrowelHost DEFAULT = new TrowelHost() {
        @Override
        public String denyEdit(Player player) {
            return player.hasPermission(com.stackmc.trowel.ui.Commands.PERMISSION) ? null
                    : "You are not allowed to use Trowel here.";
        }

        @Override
        public Box bounds(World world) {
            return null;
        }

        @Override
        public MarkerSupport markers() {
            return MarkerSupport.NONE;
        }

        @Override
        public void changed(World world, boolean markerParamsChanged) {
        }
    };

    private final Map<Plugin, TrowelHost> hosts = new LinkedHashMap<>();
    private final MarkerSupport markers = new Markers();

    void register(Plugin owner, TrowelHost host) {
        hosts.put(owner, host);
    }

    void unregister(Plugin owner) {
        hosts.remove(owner);
    }

    /** The host that governs this world. */
    TrowelHost of(World world) {
        for (TrowelHost host : hosts.values()) {
            if (host.handles(world)) {
                return host;
            }
        }
        return DEFAULT;
    }

    private List<MarkerSupport> allMarkers() {
        List<MarkerSupport> all = new ArrayList<>();
        hosts.values().forEach(host -> all.add(host.markers()));
        return all;
    }

    @Override
    public String denyEdit(Player player) {
        return of(player.getWorld()).denyEdit(player);
    }

    @Override
    public Box bounds(World world) {
        return of(world).bounds(world);
    }

    @Override
    public Box bounds(Player player, World world) {
        return of(world).bounds(player, world);
    }

    @Override
    public MarkerSupport markers() {
        return markers;
    }

    @Override
    public void changed(World world, boolean markerParamsChanged) {
        of(world).changed(world, markerParamsChanged);
    }

    /**
     * The markers of every host: questions about a world go to its host, questions about a block
     * type go to the first host that knows it.
     */
    private final class Markers implements MarkerSupport {

        private MarkerSupport owner(Material material) {
            for (MarkerSupport support : allMarkers()) {
                if (support.isMarker(material)) {
                    return support;
                }
            }
            return MarkerSupport.NONE;
        }

        @Override
        public boolean isMarker(Material material) {
            return owner(material) != MarkerSupport.NONE;
        }

        @Override
        public Material resolve(String id) {
            for (MarkerSupport support : allMarkers()) {
                Material found = support.resolve(id);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        @Override
        public List<String> ids() {
            List<String> ids = new ArrayList<>();
            allMarkers().forEach(support -> ids.addAll(support.ids()));
            return ids;
        }

        @Override
        public Map<String, ParamKind> kinds(Material marker) {
            return owner(marker).kinds(marker);
        }

        @Override
        public Map<String, String> effective(Material marker, Map<String, String> params) {
            return owner(marker).effective(marker, params);
        }

        @Override
        public Long2ObjectMap<Map<String, String>> within(World world, Box box) {
            return of(world).markers().within(world, box);
        }

        @Override
        public Map<String, String> read(World world, long key) {
            return of(world).markers().read(world, key);
        }

        @Override
        public void write(World world, long key, Map<String, String> values) {
            of(world).markers().write(world, key, values);
        }
    }
}
