package io.github.vihuynh72.brownie.api.document.pdf;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What reading the text of one PDF is allowed to cost, counted while it is
 * being read. A PDF of a few kilobytes can hold a stream that inflates to
 * gigabytes, and the library expands a stream into memory, whole, every
 * time something uses it: a page's content once, a form once for every
 * time a page draws it, a font's program and glyph procedures once for
 * every time the font is loaded. Its own memory ceiling does not cover
 * that. So each of those uses is charged here first, by expanding the
 * stream once with nothing kept and remembering how long it was, and the
 * reading stops the moment the total passes the limit.
 *
 * <p>Charging uses, and not the file's streams once each, is the point. A
 * hostile file does not need a large stream; it needs a modest one and a
 * page that draws it a hundred thousand times. Counting as the reading goes
 * also means nothing has to be predicted about how deep forms nest or which
 * resources a page inherits: whatever the library is about to open is what
 * gets charged.
 *
 * <p>Characters are counted the same way, as each one arrives, because a
 * page's characters are all held until the page is finished.
 */
public final class PdfReadingBudget {

    public static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;
    public static final long MAX_CHARACTERS = 2_000_000;
    /** A dense printed page holds a few thousand characters; this is far beyond any of them. */
    public static final int MAX_CHARACTERS_ON_ONE_PAGE = 250_000;

    /** A font reaches its streams within a few steps (descendant font, descriptor, font file); this is beyond any real one. */
    private static final int MAX_DEPTH_UNDER_A_FONT = 6;
    private static final int MAX_OBJECTS_UNDER_A_FONT = 5_000;

    private final long maxExpandedBytes;
    private final long maxCharacters;
    private final int maxCharactersOnOnePage;
    private final Map<COSStream, Long> knownLengths = new IdentityHashMap<>();
    private final Set<COSBase> fontsTheLibraryKeeps = Collections.newSetFromMap(new IdentityHashMap<>());
    private long expandedBytes;
    private long characters;
    private int charactersOnThisPage;

    public PdfReadingBudget(long maxExpandedBytes, long maxCharacters, int maxCharactersOnOnePage) {
        this.maxExpandedBytes = maxExpandedBytes;
        this.maxCharacters = maxCharacters;
        this.maxCharactersOnOnePage = maxCharactersOnOnePage;
    }

    public static PdfReadingBudget standard() {
        return new PdfReadingBudget(MAX_EXPANDED_BYTES, MAX_CHARACTERS, MAX_CHARACTERS_ON_ONE_PAGE);
    }

    /** Unchecked on purpose: the library logs and carries on past a checked failure while drawing a form. */
    public static final class Exceeded extends RuntimeException {

        private Exceeded(String message) {
            super(message);
        }
    }

    void startPage() {
        charactersOnThisPage = 0;
    }

    void countCharacter() {
        characters++;
        charactersOnThisPage++;
        if (characters > maxCharacters || charactersOnThisPage > maxCharactersOnOnePage) {
            throw new Exceeded("The document holds more text than is read.");
        }
    }

    /**
     * One use of one stream. What the stream says it is makes no difference:
     * the library expands whatever a page names as its content, a form or a
     * font's program, and a label saying "picture" on it is the file's own
     * claim. Callers charge a stream because the library is about to open
     * it, and that is the only thing that decides.
     */
    void charge(COSStream stream) {
        Long length = knownLengths.get(stream);
        if (length == null) {
            length = measure(stream, maxExpandedBytes - expandedBytes);
            knownLengths.put(stream, length);
        }
        expandedBytes += length;
        if (expandedBytes > maxExpandedBytes) {
            throw new Exceeded("The document's contents expand beyond what is read.");
        }
    }

    /** A font the library loads once and keeps for the whole document, however many pages set it. */
    void chargeFontKeptForTheDocument(COSBase font) {
        if (fontsTheLibraryKeeps.add(font)) {
            chargeFont(font);
        }
    }

    /** Every stream a font can reach: its program, its character maps, and for a drawn font each glyph's procedure. */
    void chargeFont(COSBase font) {
        int[] visited = {0};
        chargeStreamsUnder(font, 0, Collections.newSetFromMap(new IdentityHashMap<>()), visited);
    }

    private void chargeStreamsUnder(COSBase node, int depth, Set<COSBase> seen, int[] visited) {
        COSBase value = node instanceof COSObject reference ? reference.getObject() : node;
        if (value == null || depth > MAX_DEPTH_UNDER_A_FONT || !seen.add(value)) {
            return;
        }
        if (++visited[0] > MAX_OBJECTS_UNDER_A_FONT) {
            throw new Exceeded("A font in the document is larger than any real font.");
        }
        if (value instanceof COSStream stream) {
            charge(stream);
        }
        if (value instanceof COSDictionary dictionary) {
            for (Map.Entry<COSName, COSBase> entry : dictionary.entrySet()) {
                // Loading a font opens its program, its maps and its glyph procedures. What those procedures would
                // draw with (pictures among it) is only opened by drawing them, which reading text never does.
                if (!COSName.RESOURCES.equals(entry.getKey())) {
                    chargeStreamsUnder(entry.getValue(), depth + 1, seen, visited);
                }
            }
        } else if (value instanceof COSArray array) {
            for (COSBase child : array) {
                chargeStreamsUnder(child, depth + 1, seen, visited);
            }
        }
    }

    /**
     * How long the stream is once expanded, or that it is longer than what
     * is left. The library cannot be asked: it expands a stream into
     * memory, whole, before handing back its first byte. So each of the
     * stream's filters is run here by hand, into an output that counts and
     * keeps nothing and refuses the moment it has been given too much. Only
     * a stream with more than one filter has anything kept, the output of
     * one being the input of the next, and that too is held to the limit.
     *
     * <p>A stream that cannot be expanded at all is charged for what came
     * out before it failed; the library will meet the same failure and say
     * so in its own way.
     */
    private static long measure(COSStream stream, long remaining) {
        long length = 0;
        try (InputStream raw = stream.createRawInputStream()) {
            List<Filter> filters = filtersOf(stream);
            if (filters.isEmpty()) {
                return stream.getLength();
            }
            InputStream input = raw;
            for (int index = 0; index < filters.size(); index++) {
                boolean feedsAnotherFilter = index < filters.size() - 1;
                BoundedOutput output = new BoundedOutput(remaining, feedsAnotherFilter);
                try {
                    filters.get(index).decode(input, output, stream, index);
                } finally {
                    length = output.written;
                }
                input = feedsAnotherFilter ? new ByteArrayInputStream(output.kept.toByteArray()) : input;
            }
            return length;
        } catch (Exceeded e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return length;
        }
    }

    /**
     * Content, a form and a font's streams are text and tables of numbers;
     * none of them is ever stored with a picture codec. One that claims to
     * be is refused unopened, because such a codec sizes its output from
     * what the data declares and allocates it in one piece, before anything
     * could be counted.
     */
    private static final Set<String> PICTURE_CODECS =
            Set.of("DCTDecode", "DCT", "JPXDecode", "CCITTFaxDecode", "CCF", "JBIG2Decode");

    private static Filter filterNamed(COSName name) throws IOException {
        if (PICTURE_CODECS.contains(name.getName())) {
            throw new Exceeded("The document stores its content with a picture codec.");
        }
        return FilterFactory.INSTANCE.getFilter(name);
    }

    private static List<Filter> filtersOf(COSStream stream) throws IOException {
        COSBase declared = stream.getFilters();
        List<Filter> filters = new ArrayList<>();
        if (declared instanceof COSName name) {
            filters.add(filterNamed(name));
        } else if (declared instanceof COSArray names) {
            for (COSBase element : names) {
                COSBase value = element instanceof COSObject reference ? reference.getObject() : element;
                if (!(value instanceof COSName name)) {
                    throw new IOException("A stream names a filter that is not a name.");
                }
                filters.add(filterNamed(name));
            }
        }
        return filters;
    }

    private static final class BoundedOutput extends OutputStream {

        private final long limit;
        private final ByteArrayOutputStream kept;
        private long written;

        private BoundedOutput(long limit, boolean keep) {
            this.limit = limit;
            this.kept = keep ? new ByteArrayOutputStream() : null;
        }

        @Override
        public void write(int value) {
            written++;
            requireWithinLimit();
            if (kept != null) {
                kept.write(value);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            written += length;
            requireWithinLimit();
            if (kept != null) {
                kept.write(bytes, offset, length);
            }
        }

        private void requireWithinLimit() {
            if (written > limit) {
                throw new Exceeded("The document's contents expand beyond what is read.");
            }
        }
    }
}
