package com.stackmc.trowel.engine;

import com.stackmc.trowel.geom.Transform;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Speleothem;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;

/**
 * Rotates and flips block states: a copied then rotated staircase still climbs the right way.
 *
 * <p>The direction of the server's rotations and mirrors is measured at startup on a furnace
 * rather than assumed: it is what must match {@link Transform}.</p>
 */
public final class BlockTransforms {

    private final StructureRotation clockwise;
    private final StructureRotation counterClockwise;
    private final Mirror mirrorX;
    private final Mirror mirrorZ;

    public BlockTransforms() {
        Directional north = (Directional) Material.FURNACE.createBlockData();
        north.setFacing(BlockFace.NORTH);
        BlockData turned = north.clone();
        turned.rotate(StructureRotation.CLOCKWISE_90);
        boolean clockwiseIsEast = ((Directional) turned).getFacing() == BlockFace.EAST;
        clockwise = clockwiseIsEast ? StructureRotation.CLOCKWISE_90 : StructureRotation.COUNTERCLOCKWISE_90;
        counterClockwise = clockwiseIsEast ? StructureRotation.COUNTERCLOCKWISE_90 : StructureRotation.CLOCKWISE_90;

        Directional east = (Directional) Material.FURNACE.createBlockData();
        east.setFacing(BlockFace.EAST);
        BlockData mirrored = east.clone();
        mirrored.mirror(Mirror.LEFT_RIGHT);
        boolean leftRightIsX = ((Directional) mirrored).getFacing() == BlockFace.WEST;
        mirrorX = leftRightIsX ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
        mirrorZ = leftRightIsX ? Mirror.FRONT_BACK : Mirror.LEFT_RIGHT;
    }

    public BlockData apply(BlockData data, Transform transform) {
        BlockData out = data.clone();
        switch (transform) {
            case ROTATE_90 -> out.rotate(clockwise);
            case ROTATE_180 -> out.rotate(StructureRotation.CLOCKWISE_180);
            case ROTATE_270 -> out.rotate(counterClockwise);
            case FLIP_X -> out.mirror(mirrorX);
            case FLIP_Z -> out.mirror(mirrorZ);
            case FLIP_Y -> flipVertical(out);
        }
        return out;
    }

    private static void flipVertical(BlockData data) {
        if (data instanceof Bisected bisected) {
            bisected.setHalf(bisected.getHalf() == Bisected.Half.TOP ? Bisected.Half.BOTTOM : Bisected.Half.TOP);
        }
        if (data instanceof Slab slab && slab.getType() != Slab.Type.DOUBLE) {
            slab.setType(slab.getType() == Slab.Type.TOP ? Slab.Type.BOTTOM : Slab.Type.TOP);
        }
        if (data instanceof Directional directional) {
            BlockFace facing = directional.getFacing();
            if (facing == BlockFace.UP && directional.getFaces().contains(BlockFace.DOWN)) {
                directional.setFacing(BlockFace.DOWN);
            } else if (facing == BlockFace.DOWN && directional.getFaces().contains(BlockFace.UP)) {
                directional.setFacing(BlockFace.UP);
            }
        }
        if (data instanceof Speleothem speleothem) {
            speleothem.setVerticalDirection(speleothem.getVerticalDirection() == BlockFace.UP
                    ? BlockFace.DOWN : BlockFace.UP);
        }
    }
}
