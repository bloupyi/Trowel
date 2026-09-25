package com.stackmc.trowel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/** Trowel messages, with their prefix. */
public final class Chat {

    private static final Component PREFIX = Component.text("Trowel ", NamedTextColor.DARK_AQUA)
            .append(Component.text("> ", NamedTextColor.DARK_GRAY));

    private Chat() {
    }

    public static void info(Player player, String text) {
        player.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.GRAY)));
    }

    public static void info(Player player, String text, String value) {
        player.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.GRAY))
                .append(Component.text(value, NamedTextColor.AQUA)));
    }

    public static void error(Player player, String text) {
        player.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.RED)));
    }

    public static void hint(Player player, String text) {
        player.sendMessage(Component.text("  " + text, NamedTextColor.DARK_GRAY));
    }

    public static void bar(Player player, String text, NamedTextColor color) {
        player.sendActionBar(Component.text(text, color));
    }

    /** A clickable command that only types itself into the chat. */
    public static Component suggest(String command, String description) {
        return Component.text("  " + command, NamedTextColor.GOLD)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click: type the command", NamedTextColor.GRAY)))
                .append(Component.text(" " + description, NamedTextColor.GRAY));
    }
}
