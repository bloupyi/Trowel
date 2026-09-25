package com.stackmc.trowel.geom;

import java.util.Locale;

/**
 * A quarter turn around the vertical axis, or a mirror.
 *
 * <p>Rotations turn clockwise seen from above: north becomes east. A Minecraft yaw is 0 to
 * the south and 90 to the west.</p>
 */
public enum Transform {
    ROTATE_90, ROTATE_180, ROTATE_270, FLIP_X, FLIP_Y, FLIP_Z;

    /** The rotation by that many degrees, or {@code null} if it is not a quarter turn. */
    public static Transform rotation(int degrees) {
        return switch (Math.floorMod(degrees, 360)) {
            case 90 -> ROTATE_90;
            case 180 -> ROTATE_180;
            case 270 -> ROTATE_270;
            default -> null;
        };
    }

    public int[] apply(int x, int y, int z) {
        return switch (this) {
            case ROTATE_90 -> new int[]{-z, y, x};
            case ROTATE_180 -> new int[]{-x, y, -z};
            case ROTATE_270 -> new int[]{z, y, -x};
            case FLIP_X -> new int[]{-x, y, z};
            case FLIP_Y -> new int[]{x, -y, z};
            case FLIP_Z -> new int[]{x, y, -z};
        };
    }

    public boolean swapsXZ() {
        return this == ROTATE_90 || this == ROTATE_270;
    }

    /** A named direction, rotated; an unknown name comes back unchanged. */
    public String direction(String name) {
        int[] vector = vector(name);
        if (vector == null) {
            return name;
        }
        return name(apply(vector[0], vector[1], vector[2]));
    }

    /** A yaw in degrees, rotated and brought back into [-180, 180[. */
    public double yaw(double yaw) {
        double turned = switch (this) {
            case ROTATE_90 -> yaw + 90;
            case ROTATE_180 -> yaw + 180;
            case ROTATE_270 -> yaw - 90;
            case FLIP_X -> -yaw;
            case FLIP_Z -> 180 - yaw;
            case FLIP_Y -> yaw;
        };
        return turned - 360.0 * Math.floor((turned + 180.0) / 360.0);
    }

    public static int[] vector(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "north", "n" -> new int[]{0, 0, -1};
            case "south", "s" -> new int[]{0, 0, 1};
            case "east", "e" -> new int[]{1, 0, 0};
            case "west", "w" -> new int[]{-1, 0, 0};
            case "up", "u" -> new int[]{0, 1, 0};
            case "down", "d" -> new int[]{0, -1, 0};
            default -> null;
        };
    }

    public static String name(int[] vector) {
        if (vector[0] > 0) {
            return "east";
        }
        if (vector[0] < 0) {
            return "west";
        }
        if (vector[2] > 0) {
            return "south";
        }
        if (vector[2] < 0) {
            return "north";
        }
        return vector[1] > 0 ? "up" : "down";
    }
}
