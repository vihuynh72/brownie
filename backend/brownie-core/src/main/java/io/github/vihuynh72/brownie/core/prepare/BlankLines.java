package io.github.vihuynh72.brownie.core.prepare;

import java.util.regex.Pattern;

/**
 * The one rule for what a line holds when a fill spot is put over the whole
 * of it ({@link AnchorPlacement#WHOLE_LINE}). The Word editor replaces a line
 * that is only a place to write with the spot, which keeps that blank as its
 * own text, and puts the spot at the end of any other line; the version the
 * spot is added to records the same blank as what an empty spot prints, and
 * places the spot where the editor put it. Both ask here, so a dashed line,
 * for example, is not kept by one and printed again by the other.
 */
public final class BlankLines {

    /** Underscores (fullwidth ones too), dots, an ellipsis, dashes and spaces, or nothing at all. */
    private static final Pattern ONLY_A_BLANK = Pattern.compile("[\\s\\u00A0\\u2002\\u2003_\\uFF3F.\\u2026-]*");
    /** One prompt in brackets: "[Company]", "<<Name>>", "<Name>", "{{name}}", "${name}", "{name}", and the guillemet and lenticular kinds. */
    private static final Pattern BRACKETED = Pattern.compile(
            "\\s*(\\[[^\\[\\]]+]|<<[^<>]+>>|<[^<>]+>|\\{\\{[^{}]+}}|\\$\\{[^{}]+}|\\{[^{}]+}|\\u00AB[^\\u00AB\\u00BB]+\\u00BB"
                    + "|\\u2039[^\\u2039\\u203A]+\\u203A|\\u3010[^\\u3010\\u3011]+\\u3011)\\s*");

    private BlankLines() {
    }

    /** Whether a spot over the whole of this line replaces it: the line is only a blank, empty, or one bracketed prompt. */
    public static boolean isOnlyABlank(String line) {
        return ONLY_A_BLANK.matcher(line).matches() || BRACKETED.matcher(line).matches();
    }

    /** Whether the text is one bracketed prompt, with spaces around it at most. */
    public static boolean isBracketed(String text) {
        return BRACKETED.matcher(text).matches();
    }
}
