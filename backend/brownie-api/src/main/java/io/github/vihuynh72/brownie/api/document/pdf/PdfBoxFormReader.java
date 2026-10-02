package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfFormReader;
import io.github.vihuynh72.brownie.core.document.PdfFormReading;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfFormReason;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDPushButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a PDF as a form to fill in, with Apache PDFBox, into a {@link
 * PdfFormGraph}. It is held to the same limits as reading a PDF's text for
 * a source (the same memory ceiling, the same {@link PdfReadingBudget}
 * charged for everything opened, at most 200 pages), plus limits of its
 * own on fields (1,000) and their widgets (5,000), and on what the reading
 * keeps: words (20,000 on a page, 100,000 in all) and lines, rectangles
 * and pictures (100,000 in all). The reading is stored whole and read back
 * whole by every request about the form's pages, so what it keeps is held
 * to what a form holds, not to the far larger limits on reading a PDF's
 * text. The form is read with no
 * fix-ups: the library otherwise repairs a form as it opens it, which can
 * mean drawing field appearances, and nothing here should draw anything.
 *
 * <p>The cheap, whole-file checks come first (locked, XFA, signed, actions
 * and attachments), so a file that will be refused is refused before its
 * pages are read. A page with no text is ordinary data, not a refusal:
 * scans are forms too, filled with boxes a person draws.
 *
 * <p>Each page is read twice: once for its characters, grouped into lines
 * exactly as the source text extractor groups them (see {@link
 * PdfLineGrouper}), and once for its lines, rectangles and pictures (see
 * {@link PdfGraphicsCollector}). Both passes charge one budget, since both
 * really do expand the page's content.
 */
public final class PdfBoxFormReader implements PdfFormReader {

    static final String PARSER_VERSION = "brownie-pdf-form-v1+pdfbox-3.0.8";

    static final int MAX_PAGES = 200;
    static final int MAX_FIELDS = 1_000;
    static final int MAX_WIDGETS = 5_000;
    /** Beyond any real form, counting the groups that only hold other fields, and the annotations one page can carry. */
    private static final int MAX_FIELD_NODES = 5 * MAX_FIELDS;
    private static final int MAX_ANNOTATIONS_ON_A_PAGE = 2 * MAX_WIDGETS;
    /** How many actions and outline items are followed; a file with more is refused, since what lies past them was not looked at. */
    private static final int MAX_ACTIONS = 10_000;
    /** A dense printed page holds a few thousand words; a form far fewer. */
    static final int MAX_WORDS_ON_A_PAGE = 20_000;
    static final int MAX_WORDS = 100_000;
    /** Lines, rectangles and pictures together, over the whole document. */
    static final int MAX_SHAPES = 100_000;
    /** A field's formatting script is a line or two; more than this is not read for a date format. */
    private static final int MAX_SCRIPT_BYTES = 64 * 1024;

    /** Acrobat's numbered date formats, in the order its {@code AFDate_Format} function numbers them. */
    private static final List<String> ACROBAT_DATE_FORMATS = List.of(
            "m/d", "m/d/yy", "mm/dd/yy", "mm/yy", "d-mmm", "d-mmm-yy", "dd-mmm-yy", "yy-mm-dd",
            "mmm-yy", "mmmm-yy", "mmm d, yyyy", "mmmm d, yyyy", "m/d/yy h:MM tt", "m/d/yy HH:MM");
    private static final Pattern NAMED_DATE_FORMAT =
            Pattern.compile("AFDate_(?:Format|Keystroke)Ex\\s*\\(\\s*[\"']([^\"']{1,40})[\"']\\s*\\)");
    private static final Pattern NUMBERED_DATE_FORMAT = Pattern.compile("AFDate_(?:Format|Keystroke)\\s*\\(\\s*(\\d{1,2})\\s*\\)");

    private static final COSName JAVA_SCRIPT = COSName.getPDFName("JavaScript");
    private static final COSName LAUNCH = COSName.getPDFName("Launch");
    private static final COSName EMBEDDED_FILES = COSName.getPDFName("EmbeddedFiles");
    private static final COSName FILE_ATTACHMENT = COSName.getPDFName("FileAttachment");
    private static final COSName ASSOCIATED_FILES = COSName.getPDFName("AF");
    private static final COSName COLLECTION = COSName.getPDFName("Collection");
    private static final COSName NEEDS_RENDERING = COSName.getPDFName("NeedsRendering");
    private static final COSName DOC_MDP = COSName.getPDFName("DocMDP");
    private static final COSName RICH_MEDIA = COSName.getPDFName("RichMedia");
    /** What an annotation's own actions may do when the page is opened, closed, shown or hidden, with nobody doing anything. */
    private static final Set<COSName> PAGE_TRIGGERS = Set.of(COSName.PO, COSName.PC, COSName.PV, COSName.PI);
    /**
     * Beyond any real file: the objects it holds, and how deeply one object
     * holds others directly. Looking for a carried file opens every object,
     * and each stays in memory for the rest of the read, so the count is
     * held to what a 200-page document could need: compressed, ten megabytes
     * can name millions of tiny objects.
     */
    private static final int MAX_OBJECTS = 200_000;
    private static final int MAX_DIRECT_NESTING = 256;

    private final int maxPages;
    private final long maxCharacters;
    private final long maxExpandedBytes;

    public PdfBoxFormReader() {
        this(MAX_PAGES, PdfReadingBudget.MAX_CHARACTERS, PdfReadingBudget.MAX_EXPANDED_BYTES);
    }

    /** For tests, which prove each limit with a small file and a small limit. */
    PdfBoxFormReader(int maxPages, long maxCharacters, long maxExpandedBytes) {
        this.maxPages = maxPages;
        this.maxCharacters = maxCharacters;
        this.maxExpandedBytes = maxExpandedBytes;
    }

    @Override
    public String parserVersion() {
        return PARSER_VERSION;
    }

    @Override
    public PdfFormReading read(byte[] pdf) {
        PDDocument document;
        try {
            document = Loader.loadPDF(pdf, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(maxExpandedBytes).streamCache);
        } catch (InvalidPasswordException e) {
            return refused(UnsupportedPdfFormReason.ENCRYPTED, "The PDF needs a password to open.");
        } catch (IOException | RuntimeException e) {
            return PdfBoxStructuralExtractor.isExpansionLimit(e) ? expandsTooFar() : damaged();
        }
        try (document) {
            return read(document);
        } catch (TooLarge e) {
            return refused(UnsupportedPdfFormReason.TOO_LARGE, e.getMessage());
        } catch (IOException | RuntimeException e) {
            return PdfBoxStructuralExtractor.isExpansionLimit(e) ? expandsTooFar() : damaged();
        }
    }

    private PdfFormReading read(PDDocument document) throws IOException {
        if (document.isEncrypted()) {
            return refused(UnsupportedPdfFormReason.ENCRYPTED,
                    "The PDF is protected with permission settings; a filled copy would drop them.");
        }
        List<PDPage> pages = new ArrayList<>();
        for (PDPage page : document.getPages()) {
            if (pages.size() == maxPages) {
                throw new TooLarge("This document has more than " + maxPages + " pages; at most " + maxPages + " are read.");
            }
            pages.add(page);
        }
        Map<COSDictionary, Integer> pageOfAnnotation = annotationPages(pages);
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PdfReadingBudget budget = new PdfReadingBudget(maxExpandedBytes, maxCharacters, PdfReadingBudget.MAX_CHARACTERS_ON_ONE_PAGE);

        PdfFormGraph.AcroForm acroForm = readAcroForm(catalog, pages, pageOfAnnotation, budget);
        if (acroForm.xfa() != PdfFormGraph.XfaKind.NONE) {
            return refused(UnsupportedPdfFormReason.XFA, "The form is an XFA form (" + acroForm.xfa() + ").");
        }
        if (acroForm.signedSignatureCount() > 0 || certified(catalog)) {
            return refused(UnsupportedPdfFormReason.SIGNED, "The PDF has been signed.");
        }
        PdfFormGraph.Risks risks = new RiskScan().scan(document.getDocument(), catalog, pages);
        if (risks.launchActions()) {
            return refused(UnsupportedPdfFormReason.LAUNCH_ACTION, "Something in the PDF starts another program.");
        }
        if (risks.embeddedFiles()) {
            return refused(UnsupportedPdfFormReason.EMBEDDED_FILES, "The PDF carries other files inside it.");
        }
        if (risks.documentJavaScript()) {
            return refused(UnsupportedPdfFormReason.DOCUMENT_JAVASCRIPT, "The PDF runs a script when it is opened.");
        }

        List<PdfFormGraph.Page> read = new ArrayList<>();
        int words = 0;
        int shapes = 0;
        for (int index = 0; index < pages.size(); index++) {
            PdfFormGraph.Page page = readPage(document, pages.get(index), index, budget);
            int wordsOnThePage = page.lines().stream().mapToInt(line -> line.words().size()).sum();
            if (wordsOnThePage > MAX_WORDS_ON_A_PAGE) {
                throw new TooLarge("Page " + (index + 1) + " holds more than " + MAX_WORDS_ON_A_PAGE + " words, more than any form.");
            }
            words += wordsOnThePage;
            if (words > MAX_WORDS) {
                throw new TooLarge("The document holds more than " + MAX_WORDS + " words, more than any form.");
            }
            shapes += page.rules().size() + page.rects().size() + page.images().size();
            if (shapes > MAX_SHAPES) {
                throw new TooLarge("The document draws more than " + MAX_SHAPES + " lines, boxes and pictures, more than any form.");
            }
            read.add(page);
        }
        return new PdfFormReading.Supported(new PdfFormGraph(PARSER_VERSION, read, acroForm, risks));
    }

    // ---- pages ----

    private static PdfFormGraph.Page readPage(PDDocument document, PDPage page, int index, PdfReadingBudget budget)
            throws IOException {
        PDRectangle crop = page.getCropBox();
        PdfFormGraph.CropBox cropBox = new PdfFormGraph.CropBox(
                crop.getLowerLeftX(), crop.getLowerLeftY(), crop.getWidth(), crop.getHeight());

        List<TextPosition> characters = PdfPageText.characters(document, index, budget);
        List<PdfFormGraph.Line> lines = new ArrayList<>();
        for (PdfLineGrouper.GroupedLine grouped : PdfLineGrouper.group(characters)) {
            lines.add(new PdfFormGraph.Line(lines.size(), grouped.text(),
                    PdfGlyphs.envelopeOf(grouped.characters(), crop.getHeight()), wordsOf(grouped, crop.getHeight())));
        }

        PdfGraphicsCollector graphics = new PdfGraphicsCollector(page, budget);
        graphics.collect();
        return new PdfFormGraph.Page(index + 1, cropBox, page.getRotation(), page.getUserUnit(), !lines.isEmpty(),
                lines, graphics.rules(), graphics.rects(), graphics.images());
    }

    /**
     * Splits a grouped line into words at spaces, literal or made up from a
     * gap, and around each run of two or more underscores or three or more
     * dots, so that a blank printed hard against its label ("Name:______")
     * is a word of its own with its own box.
     */
    static List<PdfFormGraph.Word> wordsOf(PdfLineGrouper.GroupedLine line, double cropHeight) {
        List<List<TextPosition>> rough = new ArrayList<>();
        List<TextPosition> current = new ArrayList<>();
        for (int index = 0; index < line.characters().size(); index++) {
            TextPosition character = line.characters().get(index);
            if (PdfLineGrouper.isBlank(character.getUnicode()) || line.spaceBefore().get(index)) {
                if (!current.isEmpty()) {
                    rough.add(current);
                    current = new ArrayList<>();
                }
            }
            if (!PdfLineGrouper.isBlank(character.getUnicode())) {
                current.add(character);
            }
        }
        if (!current.isEmpty()) {
            rough.add(current);
        }
        List<PdfFormGraph.Word> words = new ArrayList<>();
        for (List<TextPosition> word : rough) {
            for (List<TextPosition> piece : splitAroundBlanks(word)) {
                words.add(wordOf(piece, cropHeight));
            }
        }
        return words;
    }

    private static List<List<TextPosition>> splitAroundBlanks(List<TextPosition> word) {
        List<List<TextPosition>> pieces = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < word.size()) {
            char kind = blankKind(word.get(index).getUnicode());
            if (kind == 0) {
                index++;
                continue;
            }
            int end = index;
            int dots = 0;
            while (end < word.size() && blankKind(word.get(end).getUnicode()) == kind) {
                dots += "\u2026".equals(word.get(end).getUnicode()) ? 3 : 1;
                end++;
            }
            boolean longEnough = kind == '_' ? end - index >= 2 : dots >= 3;
            if (longEnough) {
                if (index > start) {
                    pieces.add(word.subList(start, index));
                }
                pieces.add(word.subList(index, end));
                start = end;
            }
            index = end;
        }
        if (start < word.size()) {
            pieces.add(word.subList(start, word.size()));
        }
        return pieces;
    }

    private static char blankKind(String unicode) {
        if ("_".equals(unicode) || "\uFF3F".equals(unicode)) {
            return '_';
        }
        return ".".equals(unicode) || "\u2026".equals(unicode) ? '.' : 0;
    }

    private static PdfFormGraph.Word wordOf(List<TextPosition> characters, double cropHeight) {
        StringBuilder text = new StringBuilder();
        for (TextPosition character : characters) {
            text.append(character.getUnicode());
        }
        TextPosition first = characters.get(0);
        double size = Math.round(first.getFontSizeInPt() * 100) / 100.0;
        return new PdfFormGraph.Word(text.toString(), PdfGlyphs.envelopeOf(characters, cropHeight),
                PdfGlyphs.fontNameOf(first), size, PdfGlyphs.directionOf(first));
    }

    // ---- the fillable form ----

    private static Map<COSDictionary, Integer> annotationPages(List<PDPage> pages) {
        Map<COSDictionary, Integer> pageOf = new IdentityHashMap<>();
        for (int index = 0; index < pages.size(); index++) {
            COSArray annotations = pages.get(index).getCOSObject().getCOSArray(COSName.ANNOTS);
            if (annotations == null) {
                continue;
            }
            if (annotations.size() > MAX_ANNOTATIONS_ON_A_PAGE) {
                throw new TooLarge("Page " + (index + 1) + " carries more annotations than any form does.");
            }
            for (int item = 0; item < annotations.size(); item++) {
                if (annotations.getObject(item) instanceof COSDictionary annotation) {
                    pageOf.putIfAbsent(annotation, index + 1);
                }
            }
        }
        return pageOf;
    }

    private static PdfFormGraph.AcroForm readAcroForm(
            PDDocumentCatalog catalog, List<PDPage> pages, Map<COSDictionary, Integer> pageOfAnnotation, PdfReadingBudget budget)
            throws IOException {
        PDAcroForm form = catalog.getAcroForm(null);
        if (form == null) {
            return PdfFormGraph.AcroForm.absent();
        }
        PdfFormGraph.XfaKind xfa = PdfFormGraph.XfaKind.NONE;
        if (form.hasXFA()) {
            boolean dynamic = catalog.getCOSObject().getBoolean(NEEDS_RENDERING, false) || form.xfaIsDynamic();
            xfa = dynamic ? PdfFormGraph.XfaKind.DYNAMIC : PdfFormGraph.XfaKind.STATIC;
        }
        List<PdfFormGraph.Field> fields = new ArrayList<>();
        int nodes = 0;
        int widgets = 0;
        int signed = 0;
        for (PDField field : form.getFieldTree()) {
            if (++nodes > MAX_FIELD_NODES) {
                throw new TooLarge("The form has more fields than are read.");
            }
            if (!(field instanceof PDTerminalField terminal)) {
                continue;
            }
            PdfFormGraph.FieldKind kind = kindOf(terminal);
            if (kind == null) {
                continue;
            }
            if (fields.size() == MAX_FIELDS) {
                throw new TooLarge("The form has more than " + MAX_FIELDS + " fields; at most " + MAX_FIELDS + " are read.");
            }
            if (kind == PdfFormGraph.FieldKind.SIGNATURE
                    && terminal.getCOSObject().getDictionaryObject(COSName.V) instanceof COSDictionary) {
                signed++;
            }
            List<PdfFormGraph.Widget> placed = new ArrayList<>();
            for (PDAnnotationWidget widget : terminal.getWidgets()) {
                if (++widgets > MAX_WIDGETS) {
                    throw new TooLarge("The form's fields are shown in more than " + MAX_WIDGETS + " places.");
                }
                PdfFormGraph.Widget where = widgetOf(widget, pages, pageOfAnnotation);
                if (where != null) {
                    placed.add(where);
                }
            }
            boolean text = terminal instanceof PDTextField;
            PDTextField textField = text ? (PDTextField) terminal : null;
            Integer maxLen = text && textField.getMaxLen() >= 0 ? textField.getMaxLen() : null;
            fields.add(new PdfFormGraph.Field(
                    terminal.getFullyQualifiedName(),
                    kind,
                    terminal.isReadOnly(),
                    terminal.isRequired(),
                    text && textField.isMultiline(),
                    text && textField.isComb(),
                    maxLen,
                    blankToNull(terminal.getAlternateFieldName()),
                    text ? dateFormatOf(terminal.getCOSObject(), budget) : null,
                    placed));
        }
        return new PdfFormGraph.AcroForm(true, xfa, form.getNeedAppearances(), fields, signed);
    }

    private static PdfFormGraph.FieldKind kindOf(PDTerminalField field) {
        if (field instanceof PDTextField) {
            return PdfFormGraph.FieldKind.TEXT;
        }
        if (field instanceof PDCheckBox) {
            return PdfFormGraph.FieldKind.CHECKBOX;
        }
        if (field instanceof PDRadioButton) {
            return PdfFormGraph.FieldKind.RADIO;
        }
        if (field instanceof PDPushButton) {
            return PdfFormGraph.FieldKind.BUTTON;
        }
        if (field instanceof PDChoice) {
            return PdfFormGraph.FieldKind.CHOICE;
        }
        return field instanceof PDSignatureField ? PdfFormGraph.FieldKind.SIGNATURE : null;
    }

    /**
     * The page a widget is on is the page whose annotations list it; the
     * widget's own claim ({@code /P}) is used only when none does. A widget
     * no reader draws ({@link PdfWidgets#drawn}) is left out, so a field
     * shown nowhere has no widgets and is not a place to fill.
     */
    private static PdfFormGraph.Widget widgetOf(
            PDAnnotationWidget widget, List<PDPage> pages, Map<COSDictionary, Integer> pageOfAnnotation) {
        if (!PdfWidgets.drawn(widget)) {
            return null;
        }
        PDRectangle rect = widget.getRectangle();
        Integer pageNumber = pageOfAnnotation.get(widget.getCOSObject());
        if (pageNumber == null && widget.getCOSObject().getDictionaryObject(COSName.P) instanceof COSDictionary claimed) {
            for (int index = 0; index < pages.size(); index++) {
                if (pages.get(index).getCOSObject() == claimed) {
                    pageNumber = index + 1;
                }
            }
        }
        if (pageNumber == null) {
            return null;
        }
        return new PdfFormGraph.Widget(pageNumber, boxOnPage(rect, pages.get(pageNumber - 1).getCropBox()));
    }

    static PdfRect boxOnPage(PDRectangle rect, PDRectangle crop) {
        double left = Math.min(rect.getLowerLeftX(), rect.getUpperRightX());
        double top = Math.max(rect.getLowerLeftY(), rect.getUpperRightY());
        return new PdfRect(left - crop.getLowerLeftX(), crop.getUpperRightY() - top,
                Math.abs(rect.getWidth()), Math.abs(rect.getHeight()));
    }

    /** The date format a text field's own formatting (or keystroke) script names, read from the script's text; the script is never run. */
    private static String dateFormatOf(COSDictionary field, PdfReadingBudget budget) {
        COSDictionary actions = field.getCOSDictionary(COSName.AA);
        if (actions == null) {
            return null;
        }
        for (COSName trigger : new COSName[] {COSName.F, COSName.K}) {
            String script = scriptOf(actions.getDictionaryObject(trigger), budget);
            if (script == null) {
                continue;
            }
            Matcher named = NAMED_DATE_FORMAT.matcher(script);
            if (named.find()) {
                return named.group(1);
            }
            Matcher numbered = NUMBERED_DATE_FORMAT.matcher(script);
            if (numbered.find()) {
                int index = Integer.parseInt(numbered.group(1));
                if (index < ACROBAT_DATE_FORMATS.size()) {
                    return ACROBAT_DATE_FORMATS.get(index);
                }
            }
        }
        return null;
    }

    /** A script kept in a stream is charged to the budget before it is expanded, like everything else read here. */
    private static String scriptOf(COSBase action, PdfReadingBudget budget) {
        if (!(action instanceof COSDictionary dictionary) || !JAVA_SCRIPT.equals(dictionary.getCOSName(COSName.S))) {
            return null;
        }
        COSBase script = dictionary.getDictionaryObject(COSName.JS);
        if (script instanceof COSString text) {
            return text.getString();
        }
        if (script instanceof COSStream stream) {
            budget.charge(stream);
            try (InputStream in = stream.createInputStream()) {
                return new String(in.readNBytes(MAX_SCRIPT_BYTES), StandardCharsets.ISO_8859_1);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /** A certified document carries its certifying signature in the catalog's permissions; filling it would break that too. */
    private static boolean certified(PDDocumentCatalog catalog) {
        COSDictionary permissions = catalog.getCOSObject().getCOSDictionary(COSName.PERMS);
        return permissions != null && permissions.getDictionaryObject(DOC_MDP) != null;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    // ---- what the file would do on its own ----

    /**
     * Looks, without running anything, for what a PDF would do when opened:
     * a script the document or a page runs, or an annotation runs when its
     * page is opened, closed, shown or hidden; any script in media an
     * annotation plays; an action anywhere that starts another program; and
     * files carried inside, wherever they hang. A field's own formatting
     * scripts, and scripts a click or a keystroke runs, are allowed (they
     * are kept and never run here); a launch action is refused wherever it
     * is, since a click is enough to run it.
     */
    private static final class RiskScan {

        /** Actions looked at, from wherever they were reached. */
        private final Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        /**
         * Actions looked at from a trigger that runs by itself. One action can
         * be reached from a click and from a page opening alike; looked at
         * first from the click, it is looked at again from the page opening,
         * where a script in it does run by itself.
         */
        private final Set<COSBase> seenRunningByItself = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<COSBase> outlineItemsSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean javaScript;
        private boolean launch;
        private boolean embedded;
        private int followed;

        PdfFormGraph.Risks scan(COSDocument file, PDDocumentCatalog catalog, List<PDPage> pages) {
            COSDictionary root = catalog.getCOSObject();
            action(root.getDictionaryObject(COSName.OPEN_ACTION), true);
            additionalActions(root.getDictionaryObject(COSName.AA), true);
            COSDictionary names = root.getCOSDictionary(COSName.NAMES);
            if (names != null) {
                javaScript |= nameTreeHasEntries(names.getDictionaryObject(JAVA_SCRIPT));
                embedded |= nameTreeHasEntries(names.getDictionaryObject(EMBEDDED_FILES));
            }
            embedded |= root.getDictionaryObject(ASSOCIATED_FILES) != null || root.getDictionaryObject(COLLECTION) != null;
            embedded |= carriesAFile(file);
            outline(root.getCOSDictionary(COSName.OUTLINES));
            for (PDPage page : pages) {
                COSDictionary pageDictionary = page.getCOSObject();
                additionalActions(pageDictionary.getDictionaryObject(COSName.AA), true);
                COSArray annotations = pageDictionary.getCOSArray(COSName.ANNOTS);
                if (annotations == null) {
                    continue;
                }
                for (int index = 0; index < annotations.size(); index++) {
                    if (annotations.getObject(index) instanceof COSDictionary annotation) {
                        annotation(annotation);
                    }
                }
            }
            return new PdfFormGraph.Risks(javaScript, launch, embedded);
        }

        private void annotation(COSDictionary annotation) {
            COSName subtype = annotation.getCOSName(COSName.SUBTYPE);
            // Media plays by itself when its page opens (that is how it is usually set up), and so do its scripts.
            boolean media = COSName.SCREEN.equals(subtype) || RICH_MEDIA.equals(subtype);
            action(annotation.getDictionaryObject(COSName.A), media);
            additionalActions(annotation.getDictionaryObject(COSName.AA), media);
            // A widget's field may keep its actions on a parent it shares with other widgets.
            COSBase parent = annotation.getDictionaryObject(COSName.PARENT);
            for (int depth = 0; depth < 32 && parent instanceof COSDictionary field; depth++) {
                additionalActions(field.getDictionaryObject(COSName.AA), false);
                parent = field.getDictionaryObject(COSName.PARENT);
            }
            if (FILE_ATTACHMENT.equals(subtype)) {
                embedded = true;
            }
            if (annotation.getDictionaryObject(COSName.FS) instanceof COSDictionary file && file.getDictionaryObject(COSName.EF) != null) {
                embedded = true;
            }
        }

        /**
         * Whether any object in the file is a file carried inside it: a file
         * specification holding the file itself ({@code /EF}), or a stream
         * that says it is one. A stream is always an object of its own in the
         * file's table, and every dictionary is either one too or held
         * directly inside one, so walking the table, and what each object
         * holds directly, finds a carried file wherever it hangs: the
         * catalog, a page, an annotation, a picture's associated files, the
         * assets of rich media, or nowhere at all.
         */
        private static boolean carriesAFile(COSDocument file) {
            List<COSObjectKey> keys = new ArrayList<>(file.getXrefTable().keySet());
            if (keys.size() > MAX_OBJECTS) {
                throw new TooLarge("The file holds more objects than any form does.");
            }
            for (COSObjectKey key : keys) {
                COSObject object = file.getObjectFromPool(key);
                if (object != null && holdsAFile(object.getObject(), 0)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean holdsAFile(COSBase value, int depth) {
            if (depth > MAX_DIRECT_NESTING) {
                throw new TooLarge("The file nests its contents more deeply than any form does.");
            }
            if (value instanceof COSDictionary dictionary) {
                if (dictionary.getItem(COSName.EF) != null
                        || (value instanceof COSStream && COSName.EMBEDDED_FILE.equals(dictionary.getCOSName(COSName.TYPE)))) {
                    return true;
                }
                for (COSBase child : dictionary.getValues()) {
                    // Another object is looked at in its own turn; only what is held directly is walked here.
                    if (!(child instanceof COSObject) && holdsAFile(child, depth + 1)) {
                        return true;
                    }
                }
            } else if (value instanceof COSArray array) {
                for (COSBase child : array) {
                    if (!(child instanceof COSObject) && holdsAFile(child, depth + 1)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * The document's and a page's own triggers all fire with nobody doing
         * anything, and so do media's; of anything else's, only those that
         * fire as its page opens, closes, shows or hides.
         */
        private void additionalActions(COSBase actions, boolean everyTriggerRunsByItself) {
            if (actions instanceof COSDictionary triggers) {
                for (COSName trigger : triggers.keySet()) {
                    action(triggers.getDictionaryObject(trigger), everyTriggerRunsByItself || PAGE_TRIGGERS.contains(trigger));
                }
            }
        }

        /** One action and every action it chains to next. A script counts only where it runs by itself. */
        private void action(COSBase value, boolean documentLevel) {
            COSBase resolved = value instanceof COSObject reference ? reference.getObject() : value;
            if (resolved instanceof COSArray array) {
                for (COSBase element : array) {
                    action(element, documentLevel);
                }
                return;
            }
            if (!(resolved instanceof COSDictionary action)) {
                return;
            }
            boolean firstLook = seen.add(action);
            boolean firstLookRunningByItself = documentLevel && seenRunningByItself.add(action);
            if (!firstLook && !firstLookRunningByItself) {
                return;
            }
            follow();
            COSName kind = action.getCOSName(COSName.S);
            if (LAUNCH.equals(kind)) {
                launch = true;
            } else if (JAVA_SCRIPT.equals(kind) && documentLevel) {
                javaScript = true;
            }
            action(action.getDictionaryObject(COSName.NEXT), documentLevel);
        }

        private void outline(COSDictionary outlines) {
            if (outlines == null) {
                return;
            }
            List<COSDictionary> pending = new ArrayList<>();
            pending.add(outlines);
            while (!pending.isEmpty()) {
                COSDictionary item = pending.remove(pending.size() - 1);
                if (!outlineItemsSeen.add(item)) {
                    continue;
                }
                follow();
                action(item.getDictionaryObject(COSName.A), false);
                for (COSName link : new COSName[] {COSName.FIRST, COSName.NEXT}) {
                    if (item.getDictionaryObject(link) instanceof COSDictionary next) {
                        pending.add(next);
                    }
                }
            }
        }

        /** Past the limit the file is refused: a script or a launch hidden behind a long run of harmless actions is still one. */
        private void follow() {
            if (++followed > MAX_ACTIONS) {
                throw new TooLarge("The file holds more actions and outline entries than any form does.");
            }
        }

        private boolean nameTreeHasEntries(COSBase tree) {
            if (!(tree instanceof COSDictionary node)) {
                return false;
            }
            COSArray names = node.getCOSArray(COSName.NAMES);
            COSArray kids = node.getCOSArray(COSName.KIDS);
            return (names != null && names.size() > 0) || (kids != null && kids.size() > 0);
        }
    }

    // ---- outcomes ----

    /** A limit on the form's shape (fields, widgets, annotations, pages) was passed while reading. */
    private static final class TooLarge extends RuntimeException {

        TooLarge(String message) {
            super(message);
        }
    }

    private static PdfFormReading refused(UnsupportedPdfFormReason reason, String detail) {
        return new PdfFormReading.Unsupported(reason, detail);
    }

    private static PdfFormReading expandsTooFar() {
        return refused(UnsupportedPdfFormReason.TOO_LARGE,
                "This document's contents expand far beyond what a document of its size holds, so it was not read.");
    }

    private static PdfFormReading damaged() {
        return refused(UnsupportedPdfFormReason.DAMAGED, "The file could not be read as a PDF.");
    }
}
