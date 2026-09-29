package io.github.vihuynh72.brownie.core.action;

/**
 * Text that will be written into a person's account under their approval
 * must read on the page as it will read there. Characters that reorder text
 * on screen, or that are invisible, would let what the person approved look
 * different from what is sent, so such text is refused rather than sent. The
 * joiner that holds an emoji sequence together is not among them.
 */
public final class VisibleText {

    private VisibleText() {
    }

    /** True when the text holds a direction override or embedding, an invisible mark, or a character with no width. */
    public static boolean hidesSomething(String text) {
        if (text == null) {
            return false;
        }
        return text.codePoints().anyMatch(VisibleText::hidden);
    }

    private static boolean hidden(int c) {
        return (c >= 0x202A && c <= 0x202E) // embeddings and overrides
                || (c >= 0x2066 && c <= 0x2069) // isolates
                || c == 0x200E || c == 0x200F || c == 0x061C // direction marks
                || c == 0x200B || c == 0x200C || c == 0x2060 || c == 0xFEFF // no-width space, non-joiner, word joiner, no-width no-break space
                || (c >= 0x2061 && c <= 0x2064) // invisible operators
                || c == 0x180E || c == 0x00AD; // Mongolian vowel separator, soft hyphen
    }
}
