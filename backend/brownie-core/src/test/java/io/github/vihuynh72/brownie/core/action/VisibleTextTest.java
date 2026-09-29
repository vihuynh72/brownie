package io.github.vihuynh72.brownie.core.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Text that reads differently on screen from what is sent is refused; ordinary text, accents and emoji are not. */
class VisibleTextTest {

    @Test
    void reorderingAndInvisibleCharactersAreFound() {
        for (String hidden : new String[] {"invoice\u202Excod.exe", "a\u2066b", "a\u200Fb", "a\u200Bb", "a\uFEFFb", "a\u00ADb", "a\u2061b"}) {
            assertTrue(VisibleText.hidesSomething(hidden), hidden);
        }
    }

    @Test
    void ordinaryTextAccentsAndEmojiAreNot() {
        assertFalse(VisibleText.hidesSomething("Caf\u00E9 minutes \u2014 March 5"));
        assertFalse(VisibleText.hidesSomething("Team \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 picnic"));
        assertFalse(VisibleText.hidesSomething(null));
    }
}
