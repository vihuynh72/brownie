package io.github.vihuynh72.brownie.api.document.docx;

import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;

import javax.xml.namespace.QName;
import java.util.Locale;
import java.util.Map;

/**
 * The text a Word run shows, read from its children in document order:
 * text, and the elements that stand for a character of their own -- a tab
 * ({@code w:tab}, {@code w:ptab}) as {@code \t}, a break ({@code w:br},
 * {@code w:cr}) as {@code \n}, a non-breaking hyphen as U+2011, and a
 * symbol ({@code w:sym}) from Wingdings, Wingdings 2 or Symbol as the
 * character it looks like when it is a checkbox, a check mark or a bullet.
 * Everything else a run can hold (field codes, deleted text, pictures, a
 * symbol with no known look-alike) adds nothing.
 *
 * <p>Everything that reads a run's text for a person or a comparison goes
 * through here, so the graph, the page and anything that finds a place in a
 * line all count the same characters.
 */
public final class RunText {

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private static final QName FONT = new QName(W, "font");
    private static final QName CHAR = new QName(W, "char");

    private static final String BALLOT_BOX = "\u2610";
    private static final String BALLOT_BOX_WITH_CHECK = "\u2611";
    private static final String BALLOT_BOX_WITH_X = "\u2612";
    private static final String CHECK_MARK = "\u2713";
    private static final String BALLOT_X = "\u2717";
    private static final String BULLET = "\u2022";

    /**
     * Symbol characters by font, as the low byte of their code: Word writes
     * a symbol font's characters either plainly ({@code 00FE}) or moved into
     * the private area ({@code F0FE}), and both mean the same glyph.
     */
    private static final Map<String, Map<Integer, String>> SYMBOLS = Map.of(
            "wingdings", Map.ofEntries(
                    Map.entry(0x6F, BALLOT_BOX),
                    Map.entry(0x71, BALLOT_BOX),
                    Map.entry(0x72, BALLOT_BOX),
                    Map.entry(0xA8, BALLOT_BOX),
                    Map.entry(0xFE, BALLOT_BOX_WITH_CHECK),
                    Map.entry(0xFD, BALLOT_BOX_WITH_X),
                    Map.entry(0x78, BALLOT_BOX_WITH_X),
                    Map.entry(0xFC, CHECK_MARK),
                    Map.entry(0xFB, BALLOT_X),
                    Map.entry(0x6C, "\u25CF"),
                    Map.entry(0x6E, "\u25A0"),
                    Map.entry(0x76, "\u2756"),
                    Map.entry(0xA1, "\u25CB"),
                    Map.entry(0xA7, "\u25AA"),
                    Map.entry(0xD8, "\u27A2")),
            "wingdings 2", Map.of(
                    0xA3, BALLOT_BOX,
                    0x52, BALLOT_BOX_WITH_CHECK,
                    0x51, BALLOT_BOX_WITH_X,
                    0x53, BALLOT_BOX_WITH_X,
                    0x54, BALLOT_BOX_WITH_X,
                    0x50, CHECK_MARK,
                    0x4F, BALLOT_X),
            "symbol", Map.of(
                    0xB7, BULLET,
                    0xD6, "\u221A"));

    private RunText() {
    }

    /** The shown text of one run ({@code w:r}). */
    public static String of(XmlObject run) {
        StringBuilder text = new StringBuilder();
        try (XmlCursor cursor = run.newCursor()) {
            if (!cursor.toFirstChild()) {
                return "";
            }
            do {
                QName name = cursor.getName();
                if (!W.equals(name.getNamespaceURI())) {
                    continue;
                }
                switch (name.getLocalPart()) {
                    case "t" -> text.append(cursor.getTextValue());
                    case "tab", "ptab" -> text.append('\t');
                    case "br", "cr" -> text.append('\n');
                    case "noBreakHyphen" -> text.append('\u2011');
                    case "sym" -> text.append(symbol(cursor.getAttributeText(FONT), cursor.getAttributeText(CHAR)));
                    default -> {
                        // Field codes, deleted text, pictures, marks and properties show no text of their own.
                    }
                }
            } while (cursor.toNextSibling());
        }
        return text.toString();
    }

    /** The look-alike of one symbol, or nothing when its font or character is not one Brownie knows. */
    static String symbol(String font, String code) {
        if (font == null || code == null) {
            return "";
        }
        Map<Integer, String> glyphs = SYMBOLS.get(font.strip().toLowerCase(Locale.ROOT));
        if (glyphs == null) {
            return "";
        }
        int value;
        try {
            value = Integer.parseInt(code.strip(), 16);
        } catch (NumberFormatException e) {
            return "";
        }
        if ((value & 0xFF00) != 0xF000 && (value & 0xFF00) != 0) {
            return "";
        }
        return glyphs.getOrDefault(value & 0xFF, "");
    }
}
