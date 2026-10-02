package io.github.vihuynh72.brownie.core.document;

import java.util.Locale;

/**
 * The three families Brownie writes text into a PDF with. Each is one of
 * the Liberation fonts bundled with the application, chosen because their
 * letters are as wide as the Helvetica, Times and Courier a form was most
 * likely set in, so text sized to a box keeps its size.
 */
public enum PdfFontFamily {
    SANS,
    SERIF,
    MONO;

    private static final String[] SERIF_NAMES = {"times", "serif", "roman", "georgia", "garamond", "cambria", "palatino", "antiqua", "minion"};

    /**
     * The family a font's name suggests: Courier and anything "mono" is
     * {@link #MONO}; Times, Georgia, Garamond and anything "serif" (but not
     * "sans serif") is {@link #SERIF}; everything else, Helvetica and Arial
     * included, is {@link #SANS}. Acrobat's short resource names for its
     * standard fonts ("TiRo", "Cour") are understood too.
     */
    public static PdfFontFamily fromFontName(String fontName) {
        String name = fontName == null ? "" : fontName.toLowerCase(Locale.ROOT);
        if (name.contains("courier") || name.contains("mono") || name.contains("consol") || name.contains("menlo")
                || name.startsWith("cour") || name.equals("cobo")) {
            return MONO;
        }
        if (name.contains("sans")) {
            return SANS;
        }
        for (String serif : SERIF_NAMES) {
            if (name.contains(serif)) {
                return SERIF;
            }
        }
        return name.equals("tiro") || name.equals("tibo") ? SERIF : SANS;
    }

    /** Whether a font's name says it is bold (Bold, Black, Heavy, Semibold, Demi; "HeBo" for Acrobat's Helvetica Bold). */
    public static boolean boldFromFontName(String fontName) {
        String name = fontName == null ? "" : fontName.toLowerCase(Locale.ROOT);
        return name.contains("bold") || name.contains("black") || name.contains("heavy") || name.contains("demi")
                || name.equals("hebo") || name.equals("tibo") || name.equals("cobo");
    }
}
