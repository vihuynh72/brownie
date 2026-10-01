package io.github.vihuynh72.brownie.core.document;

import java.text.Normalizer;

/**
 * The exact text a fill item puts in a PDF, worked out the same way by the
 * filler and by the checker so that both mean the same string. Letters are
 * composed (NFC), so "Nguy&#7877;n" typed with separate accent marks is
 * written with the font's own accented letters; line endings become one
 * kind; a tab is a space; other control characters, which print nothing,
 * are dropped; and in a place that holds one line, each line break is a
 * space.
 */
public final class PdfFillText {

    private PdfFillText() {
    }

    public static String intended(String value, boolean multiline) {
        String composed = Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\t', ' ');
        StringBuilder kept = new StringBuilder(composed.length());
        composed.codePoints().forEach(codePoint -> {
            if (codePoint == '\n') {
                kept.append(multiline ? '\n' : ' ');
            } else if (!Character.isISOControl(codePoint)) {
                kept.appendCodePoint(codePoint);
            }
        });
        return kept.toString();
    }

    /** The text with every run of whitespace made one space and the ends trimmed: what reading a page back can be compared with. */
    public static String collapsedWhitespace(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }
}
