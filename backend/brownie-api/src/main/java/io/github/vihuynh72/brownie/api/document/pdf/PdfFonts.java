package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.HorizontalHeaderTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fonts Brownie writes into PDFs: Liberation Sans, Serif and Mono,
 * regular and bold, bundled with the application (see the licence file
 * beside them). A filled field's font is embedded whole, because a
 * person's reader redraws the field with it when they edit the value
 * later and may need any letter. Text drawn in a box never changes, so its
 * font is embedded with only the letters used, unless the whole font is
 * already in the file for a field.
 *
 * <p>One {@link ForDocument} serves one fill: the parsed fonts it measures
 * with are its own, so fills running at the same time share nothing but
 * the fonts' bytes.
 */
final class PdfFonts {

    enum Face {
        SANS("LiberationSans-Regular.ttf", "BrSans"),
        SANS_BOLD("LiberationSans-Bold.ttf", "BrSansBold"),
        SERIF("LiberationSerif-Regular.ttf", "BrSerif"),
        SERIF_BOLD("LiberationSerif-Bold.ttf", "BrSerifBold"),
        MONO("LiberationMono-Regular.ttf", "BrMono"),
        MONO_BOLD("LiberationMono-Bold.ttf", "BrMonoBold");

        final String file;
        /** The name the font is known by in a form's default resources and in each filled field's appearance. */
        final String resourceName;

        Face(String file, String resourceName) {
            this.file = file;
            this.resourceName = resourceName;
        }

        static Face of(PdfFontFamily family, boolean bold) {
            return switch (family) {
                case SANS -> bold ? SANS_BOLD : SANS;
                case SERIF -> bold ? SERIF_BOLD : SERIF;
                case MONO -> bold ? MONO_BOLD : MONO;
            };
        }
    }

    private static final Map<Face, byte[]> BYTES = new ConcurrentHashMap<>();

    private PdfFonts() {
    }

    private static byte[] bytesOf(Face face) {
        return BYTES.computeIfAbsent(face, missing -> {
            try (InputStream in = PdfFonts.class.getResourceAsStream("/fonts/" + missing.file)) {
                if (in == null) {
                    throw new IllegalStateException("The bundled font " + missing.file + " is missing from the application.");
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * How wide text is in one face, and which characters it has letters
     * for, read from the font file itself. Sizes are in points; the
     * vertical metrics are fractions of the font size.
     */
    static final class Metrics implements Closeable {

        private final TrueTypeFont font;
        private final CmapLookup characters;
        private final double unitsPerEm;
        final double ascent;
        final double descent;
        final double lineGap;

        private Metrics(TrueTypeFont font) throws IOException {
            this.font = font;
            this.characters = font.getUnicodeCmapLookup();
            this.unitsPerEm = font.getUnitsPerEm();
            HorizontalHeaderTable header = font.getHorizontalHeader();
            this.ascent = header.getAscender() / unitsPerEm;
            this.descent = header.getDescender() / unitsPerEm;
            this.lineGap = header.getLineGap() / unitsPerEm;
        }

        boolean hasLetterFor(int codePoint) {
            return characters.getGlyphId(codePoint) > 0;
        }

        double width(String text, double sizePt) {
            double units = 0;
            try {
                for (int index = 0; index < text.length(); ) {
                    int codePoint = text.codePointAt(index);
                    units += font.getAdvanceWidth(characters.getGlyphId(codePoint));
                    index += Character.charCount(codePoint);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return units / unitsPerEm * sizePt;
        }

        @Override
        public void close() throws IOException {
            font.close();
        }
    }

    /** The fonts for one fill of one document: measured on demand, put into the document on demand. */
    static final class ForDocument implements Closeable {

        private final PDDocument document;
        private final Map<Face, Metrics> metrics = new EnumMap<>(Face.class);
        private final Map<Face, PDType0Font> whole = new EnumMap<>(Face.class);
        private final Map<Face, PDType0Font> subset = new EnumMap<>(Face.class);

        ForDocument(PDDocument document) {
            this.document = document;
        }

        Metrics metrics(Face face) throws IOException {
            Metrics known = metrics.get(face);
            if (known == null) {
                known = new Metrics(new TTFParser().parse(new RandomAccessReadBuffer(bytesOf(face))));
                metrics.put(face, known);
            }
            return known;
        }

        /** The whole font, for a form field a person's reader may redraw. */
        PDType0Font whole(Face face) throws IOException {
            PDType0Font font = whole.get(face);
            if (font == null) {
                font = PDType0Font.load(document, new ByteArrayInputStream(bytesOf(face)), false);
                whole.put(face, font);
            }
            return font;
        }

        /** The font for text drawn in a box: only the letters used, or the whole font when a field already embeds it. */
        PDType0Font forBox(Face face) throws IOException {
            PDType0Font font = whole.get(face);
            if (font != null) {
                return font;
            }
            font = subset.get(face);
            if (font == null) {
                font = PDType0Font.load(document, new ByteArrayInputStream(bytesOf(face)), true);
                subset.put(face, font);
            }
            return font;
        }

        @Override
        public void close() throws IOException {
            for (Metrics known : metrics.values()) {
                known.close();
            }
        }
    }
}
