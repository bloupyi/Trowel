package com.stackmc.trowel.api;

/**
 * A block position held in a {@code long}, negatives included.
 *
 * <p>Same encoding as the Minecraft world: 26 bits for X and Z, 12 for Y. The relative
 * positions of a clipboard, negative around its origin, fit too.</p>
 */
public final class Keys {

    private static final int Y_BITS = 12;
    private static final int XZ_BITS = 26;
    private static final int Y_MASK = (1 << Y_BITS) - 1;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;

    private Keys() {
    }

    public static long pack(int x, int y, int z) {
        return ((x & XZ_MASK) << (XZ_BITS + Y_BITS)) | ((z & XZ_MASK) << Y_BITS) | (y & Y_MASK);
    }

    public static int x(long key) {
        return sign((int) (key >> (XZ_BITS + Y_BITS)), XZ_BITS);
    }

    public static int y(long key) {
        return sign((int) key, Y_BITS);
    }

    public static int z(long key) {
        return sign((int) (key >> Y_BITS), XZ_BITS);
    }

    public static long offset(long key, int dx, int dy, int dz) {
        return pack(x(key) + dx, y(key) + dy, z(key) + dz);
    }

    private static int sign(int value, int bits) {
        int shift = Integer.SIZE - bits;
        return (value << shift) >> shift;
    }
}
