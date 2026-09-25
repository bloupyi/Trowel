package com.stackmc.trowel;

import com.stackmc.trowel.ui.LibraryCommandsAccess;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicThumbnailTest {

    @Test
    void rendersOneSquarePerPixelRowByRow() {
        Component picture = SchematicLibrary.render("2;2;ff0000------00ff000000ff");
        String plain = PlainTextComponentSerializer.plainText().serialize(picture);
        assertEquals("\u2588\u2588\n\u2588\u2588", plain);
    }

    @Test
    void aBrokenThumbnailSaysSo() {
        assertTrue(PlainTextComponentSerializer.plainText().serialize(SchematicLibrary.render("oops"))
                .contains("no thumbnail"));
    }

    @Test
    void namesAreSafeFileNames() {
        assertTrue(SchematicLibrary.validName("watch_tower-2"));
        assertTrue(!SchematicLibrary.validName("../evil"));
        assertTrue(!SchematicLibrary.validName("Uppercase"));
    }

    @Test
    void agesReadNaturally() {
        assertEquals("5 s ago", LibraryCommandsAccess.age(5_000));
        assertEquals("3 min ago", LibraryCommandsAccess.age(185_000));
        assertEquals("2 h ago", LibraryCommandsAccess.age(7_300_000));
    }
}
