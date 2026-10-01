package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.PdfRect;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Where one character sits on its page, in the box convention every PDF
 * form record uses ({@link PdfRect}: the page as stored, crop box top-left,
 * Y down), however its text is turned. The library measures text in the
 * text's own direction; for text that runs sideways that is not the page's
 * frame, so the box here is worked out from the character's own transform
 * instead: the envelope of the glyph's advance and height laid along the
 * directions the transform gives them. For ordinary left-to-right text it
 * is exactly the library's own box. The library already measures from the
 * crop box's lower-left corner, whatever the page's coordinates are.
 */
final class PdfGlyphs {

    private static final Pattern SUBSET_PREFIX = Pattern.compile("^[A-Z]{6}\\+");

    private PdfGlyphs() {
    }

    static PdfRect boxOf(TextPosition character, double cropHeight) {
        Matrix matrix = character.getTextMatrix();
        double advanceX = matrix.getValue(0, 0);
        double advanceY = matrix.getValue(0, 1);
        double upX = matrix.getValue(1, 0);
        double upY = matrix.getValue(1, 1);
        double advanceLength = Math.hypot(advanceX, advanceY);
        double upLength = Math.hypot(upX, upY);
        if (advanceLength == 0 || upLength == 0) {
            advanceX = 1;
            advanceY = 0;
            upX = 0;
            upY = 1;
            advanceLength = 1;
            upLength = 1;
        }
        double width = character.getWidthDirAdj();
        double height = character.getHeightDir();
        double originX = matrix.getTranslateX();
        double originY = matrix.getTranslateY();
        double alongX = advanceX / advanceLength * width;
        double alongY = advanceY / advanceLength * width;
        double risingX = upX / upLength * height;
        double risingY = upY / upLength * height;

        double[] xs = {originX, originX + alongX, originX + risingX, originX + alongX + risingX};
        double[] ys = {originY, originY + alongY, originY + risingY, originY + alongY + risingY};
        double minX = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
        double maxX = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
        double minY = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
        double maxY = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3]));
        return new PdfRect(minX, cropHeight - maxY, maxX - minX, maxY - minY);
    }

    static PdfRect envelopeOf(List<TextPosition> characters, double cropHeight) {
        PdfRect envelope = null;
        for (TextPosition character : characters) {
            PdfRect box = boxOf(character, cropHeight);
            envelope = envelope == null ? box : envelope.union(box);
        }
        return envelope == null ? new PdfRect(0, 0, 0, 0) : envelope;
    }

    /** The direction the character's text runs, in degrees counter-clockwise on the page as stored (0, 90, 180 or 270). */
    static int directionOf(TextPosition character) {
        return Math.floorMod(Math.round(character.getDir()), 360);
    }

    /** The font's name without the six-letter tag a subset font carries ("ABCDEF+Helvetica" is "Helvetica"); empty when it has none. */
    static String fontNameOf(TextPosition character) {
        PDFont font = character.getFont();
        String name = font == null ? null : font.getName();
        return name == null ? "" : SUBSET_PREFIX.matcher(name).replaceFirst("");
    }
}
