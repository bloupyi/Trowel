package com.stackmc.trowel.brush;

import lombok.Getter;
import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static com.stackmc.trowel.brush.BrushType.Setting.CHANCE;
import static com.stackmc.trowel.brush.BrushType.Setting.FALLOFF;
import static com.stackmc.trowel.brush.BrushType.Setting.HEIGHT;
import static com.stackmc.trowel.brush.BrushType.Setting.INTENSITY;
import static com.stackmc.trowel.brush.BrushType.Setting.ITERATIONS;
import static com.stackmc.trowel.brush.BrushType.Setting.MASK;
import static com.stackmc.trowel.brush.BrushType.Setting.NOISE;
import static com.stackmc.trowel.brush.BrushType.Setting.PATTERN;
import static com.stackmc.trowel.brush.BrushType.Setting.PRESET;
import static com.stackmc.trowel.brush.BrushType.Setting.PROFILE;
import static com.stackmc.trowel.brush.BrushType.Setting.RANDOM;
import static com.stackmc.trowel.brush.BrushType.Setting.SIZE;
import static com.stackmc.trowel.brush.BrushType.Setting.SURFACE;

/**
 * The brushes, and where they come from.
 *
 * <p>Ball, cylinder and cube are WorldEdit's; paint, splatter, overlay and scatter are
 * goPaint's; raise, lower, flatten and smooth are goBrush's; erode and blend are
 * VoxelSniper's; the spike comes from Arceon.</p>
 */
@Getter
public enum BrushType {
    SPHERE("Ball", Material.SLIME_BALL, "Fills a ball.", null,
            SIZE, PATTERN, MASK, CHANCE, FALLOFF, SURFACE),
    CYLINDER("Cylinder", Material.HONEYCOMB, "A thick disc, centered on the aimed block.", "Height",
            SIZE, HEIGHT, PATTERN, MASK, CHANCE, FALLOFF),
    CUBE("Cube", Material.SHULKER_SHELL, "Fills a cube.", null,
            SIZE, PATTERN, MASK, CHANCE),
    PAINT("Paint", Material.FEATHER, "Recolors the surface without adding anything.", null,
            SIZE, PATTERN, MASK, CHANCE, FALLOFF),
    SPLATTER("Splatter", Material.INK_SAC, "Patches that grow around seeds.", null,
            SIZE, PATTERN, MASK, CHANCE, ITERATIONS, SURFACE),
    OVERLAY("Overlay", Material.BONE_MEAL, "Replaces the top layers of the relief.", "Depth",
            SIZE, HEIGHT, PATTERN, MASK, CHANCE, FALLOFF),
    SCATTER("Scatter", Material.WHEAT_SEEDS, "Places on top of the surface: grass, flowers, pebbles.", null,
            SIZE, PATTERN, MASK, CHANCE, FALLOFF),
    SMOOTH("Smooth", Material.SUGAR, "Softens the relief.", null,
            SIZE, ITERATIONS, MASK, FALLOFF),
    RAISE("Raise", Material.GOLDEN_SHOVEL, "Raises the relief: dome, cone, plateau or bumps.", null,
            SIZE, INTENSITY, PROFILE, PATTERN, MASK),
    LOWER("Lower", Material.IRON_SHOVEL, "Lowers the relief: bowl, funnel, pit or dip.", null,
            SIZE, INTENSITY, PROFILE, MASK),
    FLATTEN("Flatten", Material.STONE_SHOVEL, "Brings the relief to the aimed block's height: fill, cut, or both.",
            null, SIZE, PROFILE, MASK, CHANCE),
    ERODE("Erode", Material.FLINT, "Eats away or fills: melt, fill, smooth, lift, floatclean.", null,
            SIZE, PRESET, MASK),
    BLEND("Blend", Material.PRISMARINE_CRYSTALS, "Each block takes the material of its neighbours.", null,
            SIZE, MASK),
    BLOB("Blob", Material.CLAY_BALL, "An organic mass warped by noise: rocks, bushes.", null,
            SIZE, NOISE, FALLOFF, PATTERN, MASK),
    BUCKET("Bucket", Material.BOWL, "Recolors a whole surface of one material around the aimed block.", null,
            SIZE, PATTERN, MASK, SURFACE),
    STAMP("Stamp", Material.PAPER, "Places the clipboard, randomly rotated if wanted.", null,
            RANDOM),
    SPIKE("Spike", Material.AMETHYST_SHARD, "An organic spike coming out of the aimed face.", "Length",
            SIZE, HEIGHT, PATTERN, MASK),
    DISK("Disc", Material.PHANTOM_MEMBRANE, "A flat disc, lying on the aimed face.", null,
            SIZE, PATTERN, MASK, CHANCE, FALLOFF),
    RING("Ring", Material.NAUTILUS_SHELL, "A flat ring on the aimed face.", "Thickness",
            SIZE, HEIGHT, PATTERN, MASK, CHANCE),
    UNDERLAY("Underlay", Material.GUNPOWDER, "Repaints below the surface, keeping the top layer.", "Depth",
            SIZE, HEIGHT, PATTERN, MASK, CHANCE),
    FRACTURE("Fracture", Material.PRISMARINE_SHARD, "Repaints edges and sharp corners: wear, chips.", null,
            SIZE, PATTERN, MASK, CHANCE),
    DRAIN("Drain", Material.SPONGE, "Removes water and lava, and dries waterlogged blocks.", null,
            SIZE),
    FILLDOWN("Fill down", Material.GLOW_INK_SAC, "Fills the void under blocks, column by column.", null,
            SIZE, PATTERN, MASK),
    SHELL("Shell", Material.RABBIT_HIDE, "Hollows volumes, keeping their skin.", null,
            SIZE, PATTERN, MASK),
    SNOWCONE("Snow pile", Material.SNOWBALL, "Piles up snow in a cone, layer by layer.", "Height",
            SIZE, HEIGHT),
    BOULDER("Boulder", Material.ECHO_SHARD, "A faceted rock, like Arceon's.", null,
            SIZE, FALLOFF, ITERATIONS, PATTERN, MASK),
    EXTRUDE("Extrude", Material.BLAZE_ROD, "Extends the blocks of the aimed face outwards.", "Length",
            SIZE, HEIGHT, MASK),
    IVY("Ivy", Material.VINE, "Vines running over walls and hanging under overhangs.", "Length",
            SIZE, HEIGHT, CHANCE, PATTERN, MASK),
    STALACTITE("Stalactites", Material.POINTED_DRIPSTONE, "Stalactites under ceilings, stalagmites on the ground.",
            "Max length", SIZE, HEIGHT, CHANCE, PATTERN, MASK),
    CRACKS("Cracks", Material.CRACKED_STONE_BRICKS, "Cracks winding over the surface.", "Depth",
            SIZE, HEIGHT, ITERATIONS, PATTERN, MASK),
    SCREE("Scree", Material.GRAVEL, "Stones rolling down slopes and piling up at the bottom.", null,
            SIZE, INTENSITY, PATTERN, MASK);

    public enum Setting { SIZE, HEIGHT, INTENSITY, CHANCE, FALLOFF, ITERATIONS, PRESET, PROFILE, NOISE, PATTERN, MASK,
        SURFACE, RANDOM }

    private final String displayName;
    private final Material icon;
    private final String description;
    private final String heightLabel;
    private final Set<Setting> settings;

    BrushType(String displayName, Material icon, String description, String heightLabel, Setting... settings) {
        this.displayName = displayName;
        this.icon = icon;
        this.description = description;
        this.heightLabel = heightLabel;
        this.settings = settings.length == 0 ? EnumSet.noneOf(Setting.class) : EnumSet.of(settings[0], settings);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean uses(Setting setting) {
        return settings.contains(setting);
    }

    /** The name of a setting for this brush: the falloff of a blob is its irregularity. */
    public String label(Setting setting) {
        return switch (setting) {
            case SIZE -> "Radius";
            case HEIGHT -> heightLabel == null ? "Height" : heightLabel;
            case INTENSITY -> this == SCREE ? "Amount" : "Intensity";
            case CHANCE -> "Chance (%)";
            case FALLOFF -> this == BLOB || this == BOULDER ? "Irregularity (%)" : "Falloff towards the edge (%)";
            case ITERATIONS -> this == BOULDER ? "Facet detail (1 to 4)"
                    : this == CRACKS ? "Number of cracks" : "Passes";
            case PRESET -> "Erosion mode";
            case PROFILE -> this == FLATTEN ? "Mode" : "Shape";
            case NOISE -> "Noise";
            case PATTERN -> "Pattern";
            case MASK -> "Mask";
            case SURFACE -> "Surface only";
            case RANDOM -> "Random rotation";
        };
    }

    public static BrushType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        for (BrushType type : values()) {
            if (type.id().equals(id) || type.displayName.toLowerCase(Locale.ROOT).equals(id)) {
                return type;
            }
        }
        return switch (id) {
            case "ball" -> SPHERE;
            case "cyl", "disc", "disk" -> CYLINDER;
            case "spray" -> SPLATTER;
            case "melt", "fill", "lift" -> ERODE;
            case "clipboard", "copy" -> STAMP;
            case "blob", "rock" -> BLOB;
            case "bucket" -> BUCKET;
            case "vine", "vines" -> IVY;
            case "dripstone", "stalagmite" -> STALACTITE;
            case "crack", "fissure" -> CRACKS;
            case "talus", "rubble" -> SCREE;
            default -> null;
        };
    }
}
