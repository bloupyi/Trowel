package com.stackmc.trowel.pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokensTest {

    @Test
    @DisplayName("The commas of a block state do not split the pattern")
    void keepsBlockStates() {
        assertEquals(List.of("50%oak_stairs[facing=north,half=top]", "stone"),
                Tokens.split("50%oak_stairs[facing=north,half=top], stone", ','));
        assertEquals(List.of("a", "b"), Tokens.split("a,,b,", ','));
    }
}
