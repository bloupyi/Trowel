package com.stackmc.trowel.api;

/**
 * A box of blocks, corners included.
 *
 * <p>Corners are reduced to a minimum and a maximum on construction: a malformed box cannot
 * exist, whatever order its corners were given in.</p>
 */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Box {
        if (minX > maxX) {
            int swap = minX;
            minX = maxX;
            maxX = swap;
        }
        if (minY > maxY) {
            int swap = minY;
            minY = maxY;
            maxY = swap;
        }
        if (minZ > maxZ) {
            int swap = minZ;
            minZ = maxZ;
            maxZ = swap;
        }
    }

    public static Box around(int x, int y, int z, int radius) {
        return new Box(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int height() {
        return maxY - minY + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) width() * height() * depth();
    }

    public String size() {
        return width() + "x" + height() + "x" + depth();
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Grows by {@code n} blocks on every face. */
    public Box grow(int n) {
        return new Box(minX - n, minY - n, minZ - n, maxX + n, maxY + n, maxZ + n);
    }

    /** Pushes the face turned towards this direction: {@code (0, 5, 0)} raises the top by five blocks. */
    public Box expand(int dx, int dy, int dz) {
        return new Box(minX + Math.min(0, dx), minY + Math.min(0, dy), minZ + Math.min(0, dz),
                maxX + Math.max(0, dx), maxY + Math.max(0, dy), maxZ + Math.max(0, dz));
    }

    /**
     * Pulls in the face opposite this direction, like WorldEdit: {@code (0, 5, 0)} raises the bottom.
     *
     * @return {@code null} if the box would vanish
     */
    public Box contract(int dx, int dy, int dz) {
        int x1 = minX + Math.max(0, dx);
        int y1 = minY + Math.max(0, dy);
        int z1 = minZ + Math.max(0, dz);
        int x2 = maxX + Math.min(0, dx);
        int y2 = maxY + Math.min(0, dy);
        int z2 = maxZ + Math.min(0, dz);
        if (x1 > x2 || y1 > y2 || z1 > z2) {
            return null;
        }
        return new Box(x1, y1, z1, x2, y2, z2);
    }

    public Box shift(int dx, int dy, int dz) {
        return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** The overlap, or {@code null}. */
    public Box intersect(Box other) {
        int x1 = Math.max(minX, other.minX);
        int y1 = Math.max(minY, other.minY);
        int z1 = Math.max(minZ, other.minZ);
        int x2 = Math.min(maxX, other.maxX);
        int y2 = Math.min(maxY, other.maxY);
        int z2 = Math.min(maxZ, other.maxZ);
        if (x1 > x2 || y1 > y2 || z1 > z2) {
            return null;
        }
        return new Box(x1, y1, z1, x2, y2, z2);
    }

    public Box union(Box other) {
        return new Box(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    public Box withY(int bottom, int top) {
        return new Box(minX, bottom, minZ, maxX, top, maxZ);
    }
}
