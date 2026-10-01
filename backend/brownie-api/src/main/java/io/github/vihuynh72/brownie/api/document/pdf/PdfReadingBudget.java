package io.github.vihuynh72.brownie.api.document.pdf;

import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdfparser.PDFStreamParser;

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
 * page's characters are all held until the page is finished. So are a
 * page's operators, and how deeply it saves its drawing state: the library
 * copies the whole state for every save and keeps each copy until it is
 * restored, with no limit of its own, so a few kilobytes of saves that are
 * never restored would otherwise fill the memory long before the expanded
 * bytes ran out. And so are the values a page sets out for one operator,
 * which the library holds, with no limit either, until the operator comes.
 */
public final class PdfReadingBudget {

    public static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;
    public static final long MAX_CHARACTERS = 2_000_000;
    /** A dense printed page holds a few thousand characters; this is far beyond any of them. */
    public static final int MAX_CHARACTERS_ON_ONE_PAGE = 250_000;
    /** A page of a form or a letter is drawn with tens of thousands of operators; this is past the most detailed drawing. */
    public static final int MAX_OPERATORS_ON_ONE_PAGE = 2_000_000;
    /** How deep saved drawing states nest in one content stream. The PDF standard advises 28, and real files keep close to it. */
    public static final int MAX_SAVED_STATES = 256;
    /**
     * How many values may wait for one operator, counting each element of an array or a dictionary among them. The
     * most any real operator takes is a line of text with a gap set between each pair of letters, a few hundred
     * values; this is far past that, and still only a few megabytes held at once.
     */
    public static final int MAX_VALUES_BEFORE_AN_OPERATOR = 50_000;

    /** A font reaches its streams within a few steps (descendant font, descriptor, font file); this is beyond any real one. */
    private static final int MAX_DEPTH_UNDER_A_FONT = 6;
    private static final int MAX_OBJECTS_UNDER_A_FONT = 5_000;

    private final long maxExpandedBytes;
    private final long maxCharacters;
    private final int maxCharactersOnOnePage;
    private final int maxOperatorsOnOnePage;
    private final Map<COSStream, Long> knownLengths = new IdentityHashMap<>();
    private final Set<COSBase> fontsTheLibraryKeeps = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<COSBase> contentsCounted = Collections.newSetFromMap(new IdentityHashMap<>());
    private long expandedBytes;
    private long characters;
    private int charactersOnThisPage;
    private int operatorsOnThisPage;

    public PdfReadingBudget(long maxExpandedBytes, long maxCharacters, int maxCharactersOnOnePage) {
        this(maxExpandedBytes, maxCharacters, maxCharactersOnOnePage, MAX_OPERATORS_ON_ONE_PAGE);
    }

    /** For tests, which prove the operator limit with a small page and a small limit. */
    PdfReadingBudget(long maxExpandedBytes, long maxCharacters, int maxCharactersOnOnePage, int maxOperatorsOnOnePage) {
        this.maxExpandedBytes = maxExpandedBytes;
        this.maxCharacters = maxCharacters;
        this.maxCharactersOnOnePage = maxCharactersOnOnePage;
        this.maxOperatorsOnOnePage = maxOperatorsOnOnePage;
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

    /** A page's characters and operators are counted afresh for each pass over it; the document's characters are not. */
    void startPage() {
        charactersOnThisPage = 0;
        operatorsOnThisPage = 0;
    }

    void countCharacter() {
        characters++;
        charactersOnThisPage++;
        if (characters > maxCharacters || charactersOnThisPage > maxCharactersOnOnePage) {
            throw new Exceeded("The document holds more text than is read.");
        }
    }

    /** One operator of a page's content, or of a form the page draws. */
    void countOperator() {
        if (++operatorsOnThisPage > maxOperatorsOnOnePage) {
            throw new Exceeded("A page holds more drawing instructions than are read.");
        }
    }

    /** Called before the drawing state is saved, with how many states the stream being read holds now (the first is not a save). */
    void requireRoomToSaveState(int statesHeld) {
        if (statesHeld > MAX_SAVED_STATES) {
            throw new Exceeded("The document saves its drawing state more deeply than is read.");
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

    /**
     * Reads a page's content, or a form's, once before the library does, to
     * count the values that would wait for an operator. The library gathers
     * every value it reads until the next operator arrives, and builds an
     * array or a dictionary whole before handing it over; two bytes, "[]",
     * make an empty array of about seventy. So a content stream well inside
     * the expanded-bytes limit can still hold gigabytes of values before its
     * first operator, where nothing counted at an operator ever sees them.
     *
     * <p>The content is read here with the library's own parser, so the same
     * bytes are split into the same values: a string, a comment or a
     * picture's data written into the page hides nothing from the count, and
     * nothing that is not a value is counted. Each value is counted as it is
     * built, each element of an array or dictionary included, and none is
     * kept. Content reads the same each time it is drawn, so each page and
     * each form ({@code identity}) is read here once.
     */
    void countValuesBeforeOperators(PDContentStream content, COSBase identity) {
        if (!contentsCounted.add(identity)) {
            return;
        }
        try {
            ValueCounter counter = new ValueCounter(content);
            try {
                for (Object token = counter.parseNextToken(); token != null; token = counter.parseNextToken()) {
                    if (token instanceof Operator) {
                        counter.waiting = 0;
                    }
                }
            } finally {
                counter.close();
            }
        } catch (Exceeded e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // The library meets the same failure when it reads the content, and stops there in its own way.
        }
    }

    /**
     * The library's content parser, counting each value it builds. A value
     * the page sets out comes back from {@link #parseNextToken}; an element of
     * an array or a dictionary, at any depth, comes from {@link
     * #parseDirObject}, which the parser calls for each one before adding it.
     * So the count stops the reading before an array grows past the limit.
     */
    private static final class ValueCounter extends PDFStreamParser {

        private int waiting;

        ValueCounter(PDContentStream content) throws IOException {
            super(content);
        }

        @Override
        public Object parseNextToken() throws IOException {
            Object token = super.parseNextToken();
            if (token != null && !(token instanceof Operator)) {
                count();
            }
            return token;
        }

        @Override
        protected COSBase parseDirObject() throws IOException {
            COSBase value = super.parseDirObject();
            if (value != null) {
                count();
            }
            return value;
        }

        private void count() {
            if (++waiting > MAX_VALUES_BEFORE_AN_OPERATOR) {
                throw new Exceeded("A page sets out more values for one drawing instruction than are read.");
            }
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
