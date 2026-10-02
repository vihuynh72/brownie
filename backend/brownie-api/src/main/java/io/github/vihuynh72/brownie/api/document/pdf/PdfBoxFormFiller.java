package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.FilledPdf;
import io.github.vihuynh72.brownie.core.document.PdfFillFinding;
import io.github.vihuynh72.brownie.core.document.PdfFillFindingCode;
import io.github.vihuynh72.brownie.core.document.PdfFillItem;
import io.github.vihuynh72.brownie.core.document.PdfFillRequest;
import io.github.vihuynh72.brownie.core.document.PdfFillTarget;
import io.github.vihuynh72.brownie.core.document.PdfFillText;
import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfFormFiller;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfFormNotFillableException;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfFormReason;
import io.github.vihuynh72.brownie.core.template.PdfBoxGeometry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDAppearanceContentStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.AffineTransform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fills a PDF with Apache PDFBox and the bundled Liberation fonts.
 *
 * <p><b>The form's own fields</b> stay fillable. Each filled text field
 * gets its value ({@code /V}) and an appearance Brownie draws itself
 * ({@code /AP /N}) with {@link PdfTextLayout}, rather than the library's
 * appearance generator, so one set of rules covers Vietnamese, wrapped
 * text, comb fields, alignment and overflow for fields and boxes alike.
 * The font goes into the form's default resources and the field's default
 * appearance, embedded whole so the person's reader can redraw the field
 * if they edit it, and the form stops asking readers to redraw its fields
 * ({@code NeedAppearances} false), unless another field's value has no
 * drawing and shows only because readers draw it. Nothing is flattened.
 *
 * <p><b>Boxes</b> are drawn on the page, after all of its own content and
 * with the content's graphics state closed off first, clipped to the box
 * and turned to read upright however the page is rotated.
 *
 * <p>A value is written whole or not at all. Before anything is drawn it
 * is checked: a script whose letters join or run right to left, a
 * character the font has no letter for, text longer than the field
 * allows, text that does not fit. Each is a finding, and the value is left
 * out; the rest of the form is still filled. Dates arrive already
 * formatted; nothing here reads a date.
 */
public final class PdfBoxFormFiller implements PdfFormFiller {

    static final String FILLER_VERSION = "brownie-pdf-filler-v1+pdfbox-3.0.8+liberation-2.1.5";

    /** A field whose size is "automatic" starts at this size, or smaller when the field is shorter. */
    private static final double AUTOMATIC_SIZE_LARGEST = 12;
    /** The largest size a form's own default appearance is taken at, the same as for a box; past it the size is automatic. */
    private static final double LARGEST_DECLARED_SIZE = 72;
    /** Inside a box Brownie draws, text keeps this far from the edges. */
    private static final double BOX_PADDING = 1;
    /** The most characters one value's findings name; past that the value is plainly in the wrong script. */
    private static final int MOST_CHARACTERS_NAMED = 10;
    private static final double TOLERANCE = 0.5;

    private static final Set<Character.UnicodeScript> SHAPED_OR_RIGHT_TO_LEFT = Set.of(
            Character.UnicodeScript.ARABIC, Character.UnicodeScript.HEBREW, Character.UnicodeScript.SYRIAC,
            Character.UnicodeScript.THAANA, Character.UnicodeScript.NKO, Character.UnicodeScript.SAMARITAN,
            Character.UnicodeScript.MANDAIC, Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI,
            Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.GUJARATI, Character.UnicodeScript.ORIYA,
            Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA,
            Character.UnicodeScript.MALAYALAM, Character.UnicodeScript.SINHALA, Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO, Character.UnicodeScript.TIBETAN, Character.UnicodeScript.MYANMAR,
            Character.UnicodeScript.KHMER, Character.UnicodeScript.MONGOLIAN, Character.UnicodeScript.BALINESE,
            Character.UnicodeScript.JAVANESE, Character.UnicodeScript.SUNDANESE, Character.UnicodeScript.TAI_THAM,
            Character.UnicodeScript.NEW_TAI_LUE, Character.UnicodeScript.BUGINESE, Character.UnicodeScript.LEPCHA,
            Character.UnicodeScript.LIMBU, Character.UnicodeScript.CHAKMA, Character.UnicodeScript.TAI_VIET);

    private static final COSName USAGE_RIGHTS = COSName.getPDFName("UR3");
    private static final COSName DOC_MDP = COSName.getPDFName("DocMDP");
    private static final COSName RICH_VALUE = COSName.getPDFName("RV");

    private final long maxExpandedBytes;

    public PdfBoxFormFiller() {
        this(PdfReadingBudget.MAX_EXPANDED_BYTES);
    }

    PdfBoxFormFiller(long maxExpandedBytes) {
        this.maxExpandedBytes = maxExpandedBytes;
    }

    @Override
    public String fillerVersion() {
        return FILLER_VERSION;
    }

    @Override
    public FilledPdf fill(byte[] sourcePdf, PdfFillRequest request) {
        PDDocument document;
        try {
            document = Loader.loadPDF(sourcePdf, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(maxExpandedBytes).streamCache);
        } catch (InvalidPasswordException e) {
            throw new PdfFormNotFillableException(UnsupportedPdfFormReason.ENCRYPTED, "The PDF needs a password to open.", e);
        } catch (IOException | RuntimeException e) {
            throw unreadable(e);
        }
        try (document; PdfFonts.ForDocument fonts = new PdfFonts.ForDocument(document)) {
            PDAcroForm form = refuseWhatMustNotBeFilled(document);
            List<PdfFillFinding> findings = new ArrayList<>();
            Set<String> written = new HashSet<>();
            for (PdfFillItem item : request.items()) {
                if (item.target() instanceof PdfFillTarget.Widget widget) {
                    if (fillField(document, form, fonts, item, widget, findings)) {
                        written.add(widget.fullName());
                    }
                } else {
                    fillBox(document, fonts, item, (PdfFillTarget.Box) item.target(), findings);
                }
            }
            if (!written.isEmpty() && !othersLeftForTheReaderToDraw(form, written)) {
                form.setNeedAppearances(false);
            }
            dropUsageRights(document.getDocumentCatalog());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return new FilledPdf(out.toByteArray(), findings);
        } catch (PdfFormNotFillableException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw unreadable(e);
        }
    }

    /** Whether a field this fill did not write shows its value only because the form asks readers to draw its fields. */
    private static boolean othersLeftForTheReaderToDraw(PDAcroForm form, Set<String> written) {
        if (!form.getNeedAppearances()) {
            return false;
        }
        for (PDField field : form.getFieldTree()) {
            if (field instanceof PDTerminalField terminal && !written.contains(terminal.getFullyQualifiedName())
                    && PdfWidgets.valueLeftForTheReaderToDraw(terminal)) {
                return true;
            }
        }
        return false;
    }

    /** The reader refuses all of these before a form is ever made from the file; this is the filler's own guard. */
    private static PDAcroForm refuseWhatMustNotBeFilled(PDDocument document) {
        if (document.isEncrypted()) {
            throw new PdfFormNotFillableException(UnsupportedPdfFormReason.ENCRYPTED, "The PDF is protected; a filled copy would drop that.");
        }
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        COSDictionary permissions = catalog.getCOSObject().getCOSDictionary(COSName.PERMS);
        if (permissions != null && permissions.getDictionaryObject(DOC_MDP) != null) {
            throw new PdfFormNotFillableException(UnsupportedPdfFormReason.SIGNED, "The PDF is certified with a signature.");
        }
        PDAcroForm form = catalog.getAcroForm(null);
        if (form == null) {
            return null;
        }
        if (form.hasXFA()) {
            throw new PdfFormNotFillableException(UnsupportedPdfFormReason.XFA, "The form is an XFA form.");
        }
        for (PDField field : form.getFieldTree()) {
            if (field instanceof PDSignatureField && field.getCOSObject().getDictionaryObject(COSName.V) instanceof COSDictionary) {
                throw new PdfFormNotFillableException(UnsupportedPdfFormReason.SIGNED, "The PDF has been signed.");
            }
        }
        return form;
    }

    /**
     * A form that grants extra rights in Adobe Reader carries a signature
     * over the whole file for them. Any change breaks it, and Reader then
     * refuses to let the form be edited at all; without it, the filled form
     * stays fillable everywhere.
     */
    private static void dropUsageRights(PDDocumentCatalog catalog) {
        COSDictionary permissions = catalog.getCOSObject().getCOSDictionary(COSName.PERMS);
        if (permissions != null && permissions.containsKey(USAGE_RIGHTS)) {
            permissions.removeItem(USAGE_RIGHTS);
            if (permissions.size() == 0) {
                catalog.getCOSObject().removeItem(COSName.PERMS);
            }
        }
    }

    // ---- the form's own fields ----

    private boolean fillField(PDDocument document, PDAcroForm form, PdfFonts.ForDocument fonts, PdfFillItem item,
            PdfFillTarget.Widget target, List<PdfFillFinding> findings) throws IOException {
        PDField found = form == null ? null : form.getField(target.fullName());
        if (!(found instanceof PDTextField field) || field.isReadOnly() || field.isPassword() || field.isFileSelect()) {
            findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.FIELD_NOT_FILLABLE,
                    "The form has no text field named " + target.fullName() + " that can be filled."));
            return false;
        }
        String text = PdfFillText.intended(item.value(), field.isMultiline());
        DefaultAppearance appearance = DefaultAppearance.of(field.getDefaultAppearance(), form);
        PdfFonts.Face face = PdfFonts.Face.of(appearance.family, appearance.bold);
        PdfFonts.Metrics metrics = fonts.metrics(face);
        if (!writable(item, text, metrics, findings)) {
            return false;
        }
        int maxLen = field.getMaxLen();
        if (maxLen >= 0 && text.codePointCount(0, text.length()) > maxLen) {
            findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.MAX_LENGTH_EXCEEDED, Integer.toString(maxLen)));
            return false;
        }
        boolean comb = field.isComb() && maxLen > 0 && !field.isMultiline();
        boolean automatic = appearance.sizePt <= 0;
        boolean mayShrink = automatic || item.overflow() == PdfOverflowPolicy.SHRINK_TO_FIT;

        List<PDAnnotationWidget> widgets = field.getWidgets();
        List<WidgetLayout> layouts = new ArrayList<>();
        for (PDAnnotationWidget widget : widgets) {
            // A widget no reader draws is left as it is: nothing written there could be seen or read back.
            if (!PdfWidgets.drawn(widget)) {
                continue;
            }
            PDRectangle rect = widget.getRectangle();
            int turns = PdfBoxGeometry.quarterTurns(rotationOf(widget));
            double width = turns % 2 == 0 ? Math.abs(rect.getWidth()) : Math.abs(rect.getHeight());
            double height = turns % 2 == 0 ? Math.abs(rect.getHeight()) : Math.abs(rect.getWidth());
            double border = borderWidthOf(widget);
            double start = automatic ? Math.max(1, Math.min(AUTOMATIC_SIZE_LARGEST, Math.floor((height - 2) * 2) / 2)) : appearance.sizePt;
            PdfTextLayout.Result result = PdfTextLayout.layOut(new PdfTextLayout.Request(
                    text, width, height, 2 * border + 1, field.isMultiline(), false, comb ? maxLen : 0, field.getQ(),
                    start, mayShrink, metrics.ascent, metrics.descent, metrics.lineGap), metrics::width);
            layouts.add(new WidgetLayout(widget, turns, width, height, border, start, result));
        }
        if (layouts.isEmpty()) {
            findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.FIELD_NOT_FILLABLE,
                    "The form does not show the field " + target.fullName() + " on any page."));
            return false;
        }
        for (WidgetLayout layout : layouts) {
            if (!layout.result.fits()) {
                findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.FIXED_FIELD_OVERFLOW, null));
                return false;
            }
        }

        PDType0Font font = fonts.whole(face);
        COSName fontName = COSName.getPDFName(face.resourceName);
        PDResources defaults = form.getDefaultResources();
        if (defaults == null) {
            defaults = new PDResources();
            form.setDefaultResources(defaults);
        }
        defaults.put(fontName, font);

        double smallest = Double.MAX_VALUE;
        for (WidgetLayout layout : layouts) {
            writeAppearance(document, layout, font, fontName, appearance);
            if (!automatic && layout.result.shrunk(layout.startSizePt)) {
                smallest = Math.min(smallest, layout.result.sizePt());
            }
        }
        if (smallest != Double.MAX_VALUE) {
            findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.FIELD_TEXT_SHRUNK, points(smallest)));
        }
        COSDictionary dictionary = field.getCOSObject();
        dictionary.setString(COSName.V, text);
        dictionary.removeItem(RICH_VALUE);
        dictionary.setString(COSName.DA, "/" + face.resourceName + " " + points(Math.max(0, appearance.sizePt)) + " Tf " + appearance.colour);
        return true;
    }

    private record WidgetLayout(
            PDAnnotationWidget widget, int turns, double width, double height, double border, double startSizePt,
            PdfTextLayout.Result result) {
    }

    private static void writeAppearance(PDDocument document, WidgetLayout layout, PDType0Font font, COSName fontName,
            DefaultAppearance appearance) throws IOException {
        PDAppearanceStream stream = new PDAppearanceStream(document);
        stream.setBBox(new PDRectangle((float) layout.width, (float) layout.height));
        stream.setMatrix(turnedBy(layout.turns, layout.widget.getRectangle()));
        PDResources resources = new PDResources();
        resources.put(fontName, font);
        stream.setResources(resources);

        PDAppearanceCharacteristicsDictionary characteristics = layout.widget.getAppearanceCharacteristics();
        PDColor background = characteristics == null ? null : characteristics.getBackground();
        PDColor borderColour = characteristics == null ? null : characteristics.getBorderColour();
        try (PDAppearanceContentStream content = new PDAppearanceContentStream(stream)) {
            if (background != null && background.getComponents().length > 0) {
                content.setNonStrokingColor(background);
                content.addRect(0, 0, (float) layout.width, (float) layout.height);
                content.fill();
            }
            if (borderColour != null && borderColour.getComponents().length > 0 && layout.border > 0) {
                content.setStrokingColor(borderColour);
                content.setLineWidth((float) layout.border);
                float half = (float) (layout.border / 2);
                content.addRect(half, half, (float) (layout.width - layout.border), (float) (layout.height - layout.border));
                content.stroke();
            }
            content.beginMarkedContent(COSName.TX);
            content.saveGraphicsState();
            float inset = (float) layout.border;
            content.addRect(inset, inset, (float) (layout.width - 2 * inset), (float) (layout.height - 2 * inset));
            content.clip();
            writeText(canvasOf(content), layout.result, font, appearance);
            content.restoreGraphicsState();
            content.endMarkedContent();
        }
        PDAppearanceDictionary appearances = new PDAppearanceDictionary();
        appearances.setNormalAppearance(stream);
        layout.widget.setAppearance(appearances);
    }

    /** A widget turned by {@code /MK /R} draws its appearance turned the same way; its box is then the rectangle's sides swapped. */
    private static AffineTransform turnedBy(int turns, PDRectangle rect) {
        return switch (turns) {
            case 1 -> new AffineTransform(0, 1, -1, 0, Math.abs(rect.getWidth()), 0);
            case 2 -> new AffineTransform(-1, 0, 0, -1, Math.abs(rect.getWidth()), Math.abs(rect.getHeight()));
            case 3 -> new AffineTransform(0, -1, 1, 0, 0, Math.abs(rect.getHeight()));
            default -> new AffineTransform();
        };
    }

    private static int rotationOf(PDAnnotationWidget widget) {
        PDAppearanceCharacteristicsDictionary characteristics = widget.getAppearanceCharacteristics();
        int rotation = characteristics == null ? 0 : characteristics.getRotation();
        return rotation % 90 == 0 ? rotation : 0;
    }

    private static double borderWidthOf(PDAnnotationWidget widget) {
        PDAppearanceCharacteristicsDictionary characteristics = widget.getAppearanceCharacteristics();
        if (characteristics == null || characteristics.getBorderColour() == null) {
            return 0;
        }
        PDBorderStyleDictionary style = widget.getBorderStyle();
        return style == null ? 1 : Math.max(0, style.getWidth());
    }

    // ---- boxes Brownie draws ----

    private void fillBox(PDDocument document, PdfFonts.ForDocument fonts, PdfFillItem item, PdfFillTarget.Box target,
            List<PdfFillFinding> findings) throws IOException {
        PDPage page = target.pageNumber() <= document.getNumberOfPages() ? document.getPage(target.pageNumber() - 1) : null;
        if (page == null || !fitsOnPage(page, target.box())) {
            findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.FIELD_NOT_FILLABLE,
                    "The box is not on page " + target.pageNumber() + ", or the page is measured in units Brownie does not draw on."));
            return;
        }
        PdfFonts.Face face = PdfFonts.Face.of(target.style().family(), target.style().bold());
        PdfFonts.Metrics metrics = fonts.metrics(face);
        String text = PdfFillText.intended(item.value(), true);
        if (!writable(item, text, metrics, findings) || text.isBlank()) {
            return;
        }
        PDRectangle crop = page.getCropBox();
        PdfFormGraph.CropBox cropBox = new PdfFormGraph.CropBox(crop.getLowerLeftX(), crop.getLowerLeftY(), crop.getWidth(), crop.getHeight());
        PdfBoxGeometry.UprightFrame frame = PdfBoxGeometry.uprightFrame(target.box(), cropBox, page.getRotation());
        double start = target.style().sizePt();
        double lineAdvance = start * (metrics.ascent - metrics.descent + metrics.lineGap);
        boolean wrap = target.multiline() || frame.height() >= 2 * lineAdvance;
        PdfTextLayout.Result result = PdfTextLayout.layOut(new PdfTextLayout.Request(
                wrap ? text : text.replace('\n', ' '), frame.width(), frame.height(), BOX_PADDING, wrap, true, 0, 0,
                start, item.overflow() == PdfOverflowPolicy.SHRINK_TO_FIT, metrics.ascent, metrics.descent, metrics.lineGap),
                metrics::width);
        if (!result.fits()) {
            findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.FIXED_FIELD_OVERFLOW, null));
            return;
        }
        PDType0Font font = fonts.forBox(face);
        try (PDPageContentStream content = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            content.saveGraphicsState();
            content.transform(new Matrix((float) frame.a(), (float) frame.b(), (float) frame.c(), (float) frame.d(),
                    (float) frame.e(), (float) frame.f()));
            content.addRect(0, 0, (float) frame.width(), (float) frame.height());
            content.clip();
            content.setNonStrokingColor(0f);
            writeText(canvasOf(content), result, font, DefaultAppearance.BLACK);
            content.restoreGraphicsState();
        }
        if (result.shrunk(start)) {
            findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.FIELD_TEXT_SHRUNK, points(result.sizePt())));
        }
    }

    private static boolean fitsOnPage(PDPage page, PdfRect box) {
        PDRectangle crop = page.getCropBox();
        boolean measuredInPoints = Math.abs(page.getUserUnit() - 1) < 1e-6;
        return measuredInPoints && box.width() >= 1 && box.height() >= 1
                && box.x() >= -TOLERANCE && box.y() >= -TOLERANCE
                && box.right() <= crop.getWidth() + TOLERANCE && box.bottom() <= crop.getHeight() + TOLERANCE;
    }

    // ---- shared ----

    /**
     * The few drawing operations text needs, over the two kinds of content
     * stream it is written into (a field's appearance and a page), which
     * the library gives no public common type.
     */
    private interface TextCanvas {
        void beginText() throws IOException;

        void setFont(PDType0Font font, float size) throws IOException;

        void setColour(float[] components) throws IOException;

        void setTextMatrix(Matrix matrix) throws IOException;

        void showText(String text) throws IOException;

        void endText() throws IOException;
    }

    private static TextCanvas canvasOf(PDAppearanceContentStream content) {
        return new TextCanvas() {
            public void beginText() throws IOException {
                content.beginText();
            }

            public void setFont(PDType0Font font, float size) throws IOException {
                content.setFont(font, size);
            }

            public void setColour(float[] components) throws IOException {
                switch (components.length) {
                    case 3 -> content.setNonStrokingColor(components[0], components[1], components[2]);
                    case 4 -> content.setNonStrokingColor(components[0], components[1], components[2], components[3]);
                    default -> content.setNonStrokingColor(components.length == 1 ? components[0] : 0f);
                }
            }

            public void setTextMatrix(Matrix matrix) throws IOException {
                content.setTextMatrix(matrix);
            }

            public void showText(String text) throws IOException {
                content.showText(text);
            }

            public void endText() throws IOException {
                content.endText();
            }
        };
    }

    private static TextCanvas canvasOf(PDPageContentStream content) {
        return new TextCanvas() {
            public void beginText() throws IOException {
                content.beginText();
            }

            public void setFont(PDType0Font font, float size) throws IOException {
                content.setFont(font, size);
            }

            public void setColour(float[] components) throws IOException {
                switch (components.length) {
                    case 3 -> content.setNonStrokingColor(components[0], components[1], components[2]);
                    case 4 -> content.setNonStrokingColor(components[0], components[1], components[2], components[3]);
                    default -> content.setNonStrokingColor(components.length == 1 ? components[0] : 0f);
                }
            }

            public void setTextMatrix(Matrix matrix) throws IOException {
                content.setTextMatrix(matrix);
            }

            public void showText(String text) throws IOException {
                content.showText(text);
            }

            public void endText() throws IOException {
                content.endText();
            }
        };
    }

    private static void writeText(TextCanvas content, PdfTextLayout.Result result, PDType0Font font,
            DefaultAppearance appearance) throws IOException {
        if (result.lines().stream().allMatch(line -> line.text().isEmpty()) && result.combCharacters().isEmpty()) {
            return;
        }
        content.beginText();
        content.setFont(font, (float) result.sizePt());
        content.setColour(appearance.colourComponents());
        for (PdfTextLayout.Line line : result.lines()) {
            if (!line.text().isEmpty()) {
                content.setTextMatrix(Matrix.getTranslateInstance((float) line.x(), (float) line.baseline()));
                content.showText(line.text());
            }
        }
        for (PdfTextLayout.Placed placed : result.combCharacters()) {
            content.setTextMatrix(Matrix.getTranslateInstance((float) placed.x(), (float) placed.baseline()));
            content.showText(placed.character());
        }
        content.endText();
    }

    /**
     * Whether every character of a value can be written: none in a script
     * that needs its letters joined or runs right to left, and a letter in
     * the font for each. Each problem is a finding naming the character.
     */
    private static boolean writable(PdfFillItem item, String text, PdfFonts.Metrics metrics, List<PdfFillFinding> findings) {
        Integer shaped = text.codePoints().filter(PdfBoxFormFiller::needsShapingOrRightToLeft).boxed().findFirst().orElse(null);
        if (shaped != null) {
            findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.SCRIPT_NOT_SUPPORTED, new String(Character.toChars(shaped))));
            return false;
        }
        Set<Integer> missing = new LinkedHashSet<>();
        text.codePoints().filter(codePoint -> codePoint != '\n' && !metrics.hasLetterFor(codePoint)).forEach(missing::add);
        missing.stream().limit(MOST_CHARACTERS_NAMED).forEach(codePoint -> findings.add(new PdfFillFinding(
                item.fieldId(), PdfFillFindingCode.UNSUPPORTED_CHARACTER, new String(Character.toChars(codePoint)))));
        return missing.isEmpty();
    }

    static boolean needsShapingOrRightToLeft(int codePoint) {
        byte direction = Character.getDirectionality(codePoint);
        if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
            return true;
        }
        return SHAPED_OR_RIGHT_TO_LEFT.contains(Character.UnicodeScript.of(codePoint));
    }

    /** A size as a PDF writes it: at most two decimals, no trailing zeros ("8.5", "10"). */
    static String points(double size) {
        String text = String.format(Locale.ROOT, "%.2f", size);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static PdfFormNotFillableException unreadable(Exception failure) {
        if (PdfBoxStructuralExtractor.isExpansionLimit(failure)) {
            return new PdfFormNotFillableException(UnsupportedPdfFormReason.TOO_LARGE,
                    "The PDF's contents expand far beyond what a file of its size holds.", failure);
        }
        return new PdfFormNotFillableException(UnsupportedPdfFormReason.DAMAGED, "The PDF could not be filled.", failure);
    }

    /**
     * A field's default appearance ({@code /DA}): which font and size the
     * form asks for and in what colour. The font is read from its name in
     * the form's default resources, never loaded: only its family and
     * weight are wanted, to pick the Liberation font that stands in for it.
     * A size that is not a number, not above zero, or larger than 72 points
     * is read as automatic: the file can declare any number there, and no
     * field is written at a size that big.
     */
    private static final class DefaultAppearance {

        static final DefaultAppearance BLACK = new DefaultAppearance(PdfFontFamily.SANS, false, 0, "0 g");

        final PdfFontFamily family;
        final boolean bold;
        final double sizePt;
        final String colour;

        private DefaultAppearance(PdfFontFamily family, boolean bold, double sizePt, String colour) {
            this.family = family;
            this.bold = bold;
            this.sizePt = sizePt;
            this.colour = colour;
        }

        static DefaultAppearance of(String defaultAppearance, PDAcroForm form) {
            String[] tokens = defaultAppearance == null ? new String[0] : defaultAppearance.trim().split("\\s+");
            String fontResource = null;
            double size = 0;
            String colour = "0 g";
            for (int index = 0; index < tokens.length; index++) {
                String operator = tokens[index];
                if ("Tf".equals(operator) && index >= 2 && tokens[index - 2].startsWith("/")) {
                    fontResource = tokens[index - 2].substring(1);
                    double declared = number(tokens[index - 1]);
                    size = declared > 0 && declared <= LARGEST_DECLARED_SIZE ? declared : 0;
                } else if (("g".equals(operator) || "rg".equals(operator) || "k".equals(operator))) {
                    int operands = "g".equals(operator) ? 1 : "rg".equals(operator) ? 3 : 4;
                    if (index >= operands && allNumbers(tokens, index - operands, index)) {
                        colour = String.join(" ", Arrays.copyOfRange(tokens, index - operands, index + 1));
                    }
                }
            }
            String fontName = fontResource == null ? "" : baseFontOf(form, fontResource);
            return new DefaultAppearance(PdfFontFamily.fromFontName(fontName), PdfFontFamily.boldFromFontName(fontName), size, colour);
        }

        private static String baseFontOf(PDAcroForm form, String resourceName) {
            PDResources defaults = form.getDefaultResources();
            COSDictionary fonts = defaults == null ? null : defaults.getCOSObject().getCOSDictionary(COSName.FONT);
            COSDictionary font = fonts == null ? null : fonts.getCOSDictionary(COSName.getPDFName(resourceName));
            String baseFont = font == null ? null : font.getNameAsString(COSName.BASE_FONT);
            return baseFont != null ? baseFont : resourceName;
        }

        /** The colour's numbers without its operator: one for grey, three for RGB, four for CMYK. */
        float[] colourComponents() {
            String[] parts = colour.split(" ");
            float[] values = new float[parts.length - 1];
            for (int index = 0; index < values.length; index++) {
                values[index] = (float) number(parts[index]);
            }
            return values;
        }

        private static boolean allNumbers(String[] tokens, int from, int to) {
            for (int index = from; index < to; index++) {
                if (Double.isNaN(number(tokens[index]))) {
                    return false;
                }
            }
            return true;
        }

        private static double number(String token) {
            try {
                return Double.parseDouble(token);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
    }
}
