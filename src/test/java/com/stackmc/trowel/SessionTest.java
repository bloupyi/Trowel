package com.stackmc.trowel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTest {

    @Test
    @DisplayName("The first click is never taken for a duplicate, even starting from Integer.MIN_VALUE")
    void firstClickPasses() {
        assertFalse(Session.recent(Integer.MIN_VALUE, 12_000, 3));
        assertFalse(Session.recent(Integer.MIN_VALUE, 0, 3));
    }

    @Test
    @DisplayName("Two clicks less than three ticks apart are a duplicate")
    void doubleClickIsIgnored() {
        assertTrue(Session.recent(100, 101, 3));
        assertTrue(Session.recent(100, 102, 3));
        assertFalse(Session.recent(100, 103, 3));
        assertFalse(Session.recent(100, 104, 3));
    }
}
