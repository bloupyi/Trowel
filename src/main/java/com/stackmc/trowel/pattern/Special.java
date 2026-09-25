package com.stackmc.trowel.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A special pattern or mask with arguments in brackets, like the ezEdits ones:
 * {@code #noise[##magma][cellular(cr:edge)][2]}.
 *
 * <p>Brackets can nest: an argument may contain block states ({@code oak_stairs[half=top]})
 * or palette groups ({@code -[##magma,gold_block]}).</p>
 */
public record Special(String name, List<String> args) {

    /**
     * Reads {@code #name[a][b]...}.
     *
     * @return {@code null} if the text does not have that shape (no bracket right after the name)
     */
    public static Special read(String text) {
        if (text == null || !text.startsWith("#") || text.startsWith("##")) {
            return null;
        }
        int i = 1;
        while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_'
                || text.charAt(i) == '=')) {
            i++;
        }
        if (i >= text.length() || text.charAt(i) != '[') {
            return null;
        }
        String name = text.substring(1, i).toLowerCase(Locale.ROOT);
        List<String> args = new ArrayList<>();
        while (i < text.length()) {
            if (text.charAt(i) != '[') {
                throw new IllegalArgumentException("#" + name + ": '" + text.substring(i) + "' after the arguments. "
                        + "Each argument goes in brackets: #" + name + "[a][b].");
            }
            int depth = 0;
            int start = i + 1;
            int j = i;
            for (; j < text.length(); j++) {
                char c = text.charAt(j);
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            if (j >= text.length()) {
                throw new IllegalArgumentException("#" + name + ": missing closing bracket.");
            }
            args.add(text.substring(start, j).trim());
            i = j + 1;
        }
        return new Special(name, args);
    }

    public String arg(int index, String fallback) {
        return index < args.size() && !args.get(index).isBlank() ? args.get(index) : fallback;
    }

    public String need(int index, String usage) {
        if (index >= args.size() || args.get(index).isBlank()) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
        return args.get(index);
    }

    public double number(int index, double fallback) {
        String raw = arg(index, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.replace("%", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("#" + name + ": number expected, not '" + raw + "'.");
        }
    }

    /**
     * Splits an input on this separator, except inside brackets or parentheses: a mask
     * {@code #near[stone,dirt][3] !air} reads as two terms.
     */
    public static List<String> split(String raw, String separators) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '[' || c == '(') {
                depth++;
            } else if (c == ']' || c == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && separators.indexOf(c) >= 0) {
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
