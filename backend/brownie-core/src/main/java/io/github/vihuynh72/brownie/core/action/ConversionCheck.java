package io.github.vihuynh72.brownie.core.action;

import java.text.Normalizer;
import java.util.List;

/**
 * What survived Google's conversion of a saved file, measured the only way
 * that can be stated honestly: of the filled-in values Brownie wrote into the
 * file, how many can be found in the converted text. Layout, tables, lists and
 * formatting are not compared, and the result never calls a conversion
 * identical.
 *
 * <p>Both sides are read the same way before looking: Unicode composed the
 * same way (NFC), and every run of whitespace, including a no-break space,
 * Docs' own line-break character (U+000B) and the character Docs puts in
 * place of anything that is not text (U+E907), read as one space. A value
 * shorter than three characters is not looked for, because it would be found
 * somewhere whether or not it survived.
 */
public final class ConversionCheck {

    static final int MIN_LENGTH = 3;

    private ConversionCheck() {
    }

    public static ConversionCount count(List<String> values, String convertedText) {
        String haystack = normalize(convertedText);
        int total = 0;
        int found = 0;
        for (String value : values) {
            String needle = normalize(value);
            if (needle.codePointCount(0, needle.length()) < MIN_LENGTH) {
                continue;
            }
            total++;
            if (haystack.contains(needle)) {
                found++;
            }
        }
        return new ConversionCount(total, found);
    }

    static String normalize(String text) {
        String composed = Normalizer.normalize(text, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(composed.length());
        boolean space = false;
        for (int i = 0; i < composed.length(); i++) {
            char c = composed.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u000B' || c == '\uE907') {
                space = true;
                continue;
            }
            if (space && !out.isEmpty()) {
                out.append(' ');
            }
            space = false;
            out.append(c);
        }
        return out.toString();
    }
}
