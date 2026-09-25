package com.stackmc.trowel.ui;

public final class LibraryCommandsAccess {

    private LibraryCommandsAccess() {
    }

    public static String age(long millis) {
        return LibraryCommands.age(millis);
    }
}
