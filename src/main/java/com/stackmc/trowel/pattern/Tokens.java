package com.stackmc.trowel.pattern;

import java.util.ArrayList;
import java.util.List;

/** Splits an input without breaking block states: {@code oak_stairs[facing=north,half=top]}. */
public final class Tokens {

    private Tokens() {
    }

    public static List<String> split(String raw, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : raw.toCharArray()) {
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth = Math.max(0, depth - 1);
            }
            if (c == separator && depth == 0) {
                if (!current.toString().isBlank()) {
                    parts.add(current.toString().trim());
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }
}
