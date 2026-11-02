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

    /**
     * The value a field too short for it still holds: every character that is
     * not a letter or digit removed, when only those kept it over the limit.
     * "123-45-6789" fits a 9-character Social Security field as "123456789".
     * Null when the stripped text does not fit either.
     */
    public static String fitFormatted(String text, int maxLen) {
        if (maxLen < 0 || text.codePointCount(0, text.length()) <= maxLen) {
            return null;
        }
        String stripped = text.codePoints()
                .filter(Character::isLetterOrDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return !stripped.equals(text) && stripped.codePointCount(0, stripped.length()) <= maxLen ? stripped : null;
    }

    /** The text with every run of whitespace made one space and the ends trimmed: what reading a page back can be compared with. */
    public static String collapsedWhitespace(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }
}
