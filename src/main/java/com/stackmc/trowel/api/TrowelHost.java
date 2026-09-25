package com.stackmc.trowel.api;

import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * What Trowel asks of a plugin that hosts it: who may build where, inside which area, and how
 * the host stores its marker blocks.
 *
 * <p>A host registers itself through {@link TrowelApi#registerHost}. It governs every world it
 * {@linkplain #handles handles}; worlds no host claims fall back to the default rules (the
 * {@code trowel.use} permission, no area limit, no markers).</p>
 */
public interface TrowelHost {

    /** {@code true} if this host decides who builds in this world. */
    default boolean handles(World world) {
        return true;
    }

    /** Why this player may not build where they stand, or {@code null} if they may. */
    String denyEdit(Player player);

    /** The buildable part of this world, or {@code null} if all of it is. */
    Box bounds(World world);

    MarkerSupport markers();

    /** Trowel just placed blocks in this world. */
    void changed(World world, boolean markerParamsChanged);
}
