package com.stackmc.trowel.api;

/**
 * What a marker setting stands for, so it can be rotated or flipped with its block.
 *
 * <p>A wind blowing east, copied then rotated a quarter turn, must blow south, and a zone
 * five wide by three deep must become three wide by five deep.</p>
 */
public enum ParamKind {
    /** A facing: cardinal point, {@code keep}, or angle in degrees. */
    ANGLE,
    /** Relative waypoints {@code x,y,z;x,y,z}. */
    PATH,
    /** A direction: {@code north}, {@code up}... */
    DIRECTION,
    /** Extent of the zone along X, from the block; negative towards negative X. */
    SIZE_X,
    SIZE_Y,
    SIZE_Z
}
