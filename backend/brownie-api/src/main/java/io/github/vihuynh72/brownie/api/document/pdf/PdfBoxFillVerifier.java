package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.FilledPdf;
import io.github.vihuynh72.brownie.core.document.PdfFillFinding;
import io.github.vihuynh72.brownie.core.document.PdfFillFindingCode;
import io.github.vihuynh72.brownie.core.document.PdfFillItem;
import io.github.vihuynh72.brownie.core.document.PdfFillRequest;
import io.github.vihuynh72.brownie.core.document.PdfFillTarget;
import io.github.vihuynh72.brownie.core.document.PdfFillText;
import io.github.vihuynh72.brownie.core.document.PdfFillVerifier;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Checks a filled PDF by reading it back the way a person's reader shows
 * it, with Apache PDFBox under the same limits as reading an upload.
 *
 * <p>First the form itself: every value written to a field is stored as
 * the field's value and has a drawn appearance, no other field changed
 * (nor stopped showing its value, as one with no drawing of its own does
 * once the form no longer asks readers to draw its fields), and there are
 * as many fields as before. Then what is on the pages: both
 * the form as uploaded and the filled copy are flattened in memory (each
 * field's appearance drawn onto its page, as printing does), their text is
 * read with {@link BoundedPdfTextStripper}, and the characters the fill
 * added are those in the copy that are not in the original at the same
 * place. The fields the fill wrote lose their drawing in the original
 * first, so a value the form already showed there counts as drawn again,
 * while what the page itself prints under a field (the underscores a form
 * maker put a field over) counts as the page's. The text the fill added
 * inside each place, to within a point, must be the intended text once
 * spaces are evened out: each of a field's widgets on its own, since a form
 * may show one field in several places, and each box. Every added character
 * must lie wholly inside the place it was meant for.
 */
public final class PdfBoxFillVerifier implements PdfFillVerifier {

    /** How far outside its box a letter may reach: rounding in the file and in reading it back, nothing more. */
    static final double TOLERANCE = 1;

    private final long maxExpandedBytes;

    public PdfBoxFillVerifier() {
        this(PdfReadingBudget.MAX_EXPANDED_BYTES);
    }

    PdfBoxFillVerifier(long maxExpandedBytes) {
        this.maxExpandedBytes = maxExpandedBytes;
    }

    @Override
    public List<PdfFillFinding> verify(byte[] sourcePdf, FilledPdf filled, PdfFillRequest request) {
        List<PdfFillItem> written = request.items().stream().filter(item -> filled.wrote(item.fieldId())).toList();
        try (PDDocument source = load(sourcePdf); PDDocument output = load(filled.bytes())) {
            List<PdfFillFinding> findings = new ArrayList<>();
            PDAcroForm sourceForm = source.getDocumentCatalog().getAcroForm(null);
            PDAcroForm outputForm = output.getDocumentCatalog().getAcroForm(null);
            checkFields(sourceForm, outputForm, written, findings);
            List<Target> targets = targetsOf(output, outputForm, written);
            clearWrittenFields(sourceForm, written);
            flatten(sourceForm);
            flatten(outputForm);
            checkPages(source, output, written, targets, findings);
            return findings;
        } catch (IOException | RuntimeException e) {
            return List.of(new PdfFillFinding(null, PdfFillFindingCode.FIELD_NOT_VISIBLE_IN_OUTPUT,
                    "The filled PDF could not be read back to check it."));
        }
    }

    private PDDocument load(byte[] pdf) throws IOException {
        return Loader.loadPDF(pdf, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(maxExpandedBytes).streamCache);
    }

    // ---- the form ----

    private static void checkFields(PDAcroForm sourceForm, PDAcroForm outputForm, List<PdfFillItem> written,
            List<PdfFillFinding> findings) {
        Map<String, String> before = valuesOf(sourceForm);
        Map<String, String> after = valuesOf(outputForm);
        if (before.size() != after.size()) {
            findings.add(new PdfFillFinding(null, PdfFillFindingCode.FIELD_COUNT_CHANGED, before.size() + " to " + after.size()));
        }
        Set<String> filledFields = new HashSet<>();
        for (PdfFillItem item : written) {
            if (item.target() instanceof PdfFillTarget.Widget widget) {
                filledFields.add(widget.fullName());
                checkStoredValue(outputForm, item, widget.fullName(), findings);
            }
        }
        for (Map.Entry<String, String> field : before.entrySet()) {
            if (!filledFields.contains(field.getKey()) && !Objects.equals(field.getValue(), after.get(field.getKey()))) {
                findings.add(new PdfFillFinding(null, PdfFillFindingCode.OTHER_FIELD_CHANGED, field.getKey()));
            }
        }
        if (sourceForm != null && outputForm != null && sourceForm.getNeedAppearances() && !outputForm.getNeedAppearances()) {
            for (PDField field : outputForm.getFieldTree()) {
                if (field instanceof PDTerminalField terminal && !filledFields.contains(terminal.getFullyQualifiedName())
                        && Objects.equals(before.get(terminal.getFullyQualifiedName()), after.get(terminal.getFullyQualifiedName()))
                        && PdfWidgets.valueLeftForTheReaderToDraw(terminal)) {
                    findings.add(new PdfFillFinding(null, PdfFillFindingCode.OTHER_FIELD_CHANGED, terminal.getFullyQualifiedName()));
                }
            }
        }
    }

    private static void checkStoredValue(PDAcroForm form, PdfFillItem item, String fullName, List<PdfFillFinding> findings) {
        PDField found = form == null ? null : form.getField(fullName);
        if (!(found instanceof PDTextField field)) {
            findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.FIELD_VALUE_NOT_STORED, "The field is missing."));
            return;
        }
        String intended = PdfFillText.intended(item.value(), field.isMultiline());
        if (!intended.equals(field.getValueAsString())) {
            findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.FIELD_VALUE_NOT_STORED, field.getValueAsString()));
        }
        for (PDAnnotationWidget widget : field.getWidgets()) {
            if (!PdfWidgets.drawn(widget)) {
                continue;
            }
            PDAppearanceDictionary appearance = widget.getAppearance();
            PDAppearanceEntry normal = appearance == null ? null : appearance.getNormalAppearance();
            if (normal == null || !normal.isStream()) {
                findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.FIELD_APPEARANCE_MISSING, null));
                return;
            }
        }
    }

    /** Each field's stored value by full name ({@code null} when it has none); a group that only holds other fields is not a field. */
    private static Map<String, String> valuesOf(PDAcroForm form) {
        Map<String, String> values = new LinkedHashMap<>();
        if (form == null) {
            return values;
        }
        for (PDField field : form.getFieldTree()) {
            if (field instanceof PDTerminalField) {
                Object value = field.getCOSObject().getDictionaryObject(COSName.V);
                values.put(field.getFullyQualifiedName(), value == null ? null : field.getValueAsString());
            }
        }
        return values;
    }

    /**
     * The written fields' widgets lose their drawing in the original, those
     * the filler draws anew; nothing the form showed there before the fill
     * then passes for what the fill drew, or hides it.
     */
    private static void clearWrittenFields(PDAcroForm sourceForm, List<PdfFillItem> written) {
        if (sourceForm == null) {
            return;
        }
        for (PdfFillItem item : written) {
            if (item.target() instanceof PdfFillTarget.Widget target
                    && sourceForm.getField(target.fullName()) instanceof PDTerminalField field) {
                for (PDAnnotationWidget widget : field.getWidgets()) {
                    if (PdfWidgets.drawn(widget)) {
                        widget.getCOSObject().removeItem(COSName.AP);
                    }
                }
            }
        }
    }

    /** Every field, those inside groups included: the form's own list names only the top of each group. */
    private static void flatten(PDAcroForm form) throws IOException {
        if (form != null) {
            List<PDField> fields = new ArrayList<>();
            for (PDField field : form.getFieldTree()) {
                fields.add(field);
            }
            form.flatten(fields, false);
        }
    }

    // ---- the pages ----

    /** One place a value was meant to go: a page and a rectangle there (one of a field's widgets, or a box). */
    private record Target(PdfFillItem item, int pageIndex, PdfRect box) {
    }

    /**
     * Where each written value was meant to go, read from the copy before
     * flattening takes its fields away; a widget no reader draws is no place.
     * Two widgets of one field drawn on the same rectangle of a page are one
     * place: the text reader keeps one copy of letters drawn twice at the
     * same spot, so the second would read as empty.
     */
    private static List<Target> targetsOf(PDDocument output, PDAcroForm form, List<PdfFillItem> written) {
        Map<COSDictionary, Integer> pageOfAnnotation = new IdentityHashMap<>();
        for (int index = 0; index < output.getNumberOfPages(); index++) {
            COSArray annotations = output.getPage(index).getCOSObject().getCOSArray(COSName.ANNOTS);
            for (int item = 0; annotations != null && item < annotations.size(); item++) {
                if (annotations.getObject(item) instanceof COSDictionary annotation) {
                    pageOfAnnotation.putIfAbsent(annotation, index);
                }
            }
        }
        List<Target> targets = new ArrayList<>();
        for (PdfFillItem item : written) {
            if (item.target() instanceof PdfFillTarget.Box box) {
                targets.add(new Target(item, box.pageNumber() - 1, box.box()));
            } else if (form != null && form.getField(((PdfFillTarget.Widget) item.target()).fullName()) instanceof PDTerminalField field) {
                for (PDAnnotationWidget widget : field.getWidgets()) {
                    Integer page = pageOfAnnotation.get(widget.getCOSObject());
                    if (page != null && PdfWidgets.drawn(widget)) {
                        PdfRect box = PdfBoxFormReader.boxOnPage(widget.getRectangle(), output.getPage(page).getCropBox());
                        boolean shownThereAlready = targets.stream()
                                .anyMatch(target -> target.item == item && target.pageIndex == page && sameRectangle(target.box, box));
                        if (!shownThereAlready) {
                            targets.add(new Target(item, page, box));
                        }
                    }
                }
            }
        }
        return targets;
    }

    private static void checkPages(PDDocument source, PDDocument output, List<PdfFillItem> written, List<Target> targets,
            List<PdfFillFinding> findings) throws IOException {
        PdfReadingBudget budget = PdfReadingBudget.standard();
        List<List<TextPosition>> drawn = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            drawn.add(new ArrayList<>());
        }
        Map<PdfFillItem, StringBuilder> strays = new LinkedHashMap<>();
        StringBuilder strayAnywhere = new StringBuilder();
        for (int pageIndex = 0; pageIndex < output.getNumberOfPages(); pageIndex++) {
            PDPage page = output.getPage(pageIndex);
            double cropHeight = page.getCropBox().getHeight();
            Map<String, Integer> before = new HashMap<>();
            if (pageIndex < source.getNumberOfPages()) {
                double sourceCropHeight = source.getPage(pageIndex).getCropBox().getHeight();
                for (TextPosition character : PdfPageText.characters(source, pageIndex, budget)) {
                    if (!PdfLineGrouper.isBlank(character.getUnicode())) {
                        before.merge(keyOf(character, sourceCropHeight), 1, Integer::sum);
                    }
                }
            }
            for (TextPosition character : PdfPageText.characters(output, pageIndex, budget)) {
                if (PdfLineGrouper.isBlank(character.getUnicode())) {
                    continue;
                }
                String key = keyOf(character, cropHeight);
                if (before.getOrDefault(key, 0) > 0) {
                    // On the page before the fill, at the same place: the page's own, wherever it is.
                    before.merge(key, -1, Integer::sum);
                    continue;
                }
                PdfRect box = PdfGlyphs.boxOf(character, cropHeight);
                int home = targetAt(targets, pageIndex, box);
                if (home >= 0 && targets.get(home).box.encloses(box, TOLERANCE)) {
                    drawn.get(home).add(character);
                } else {
                    StringBuilder stray = home < 0 ? strayAnywhere : strays.computeIfAbsent(targets.get(home).item, item -> new StringBuilder());
                    stray.append(character.getUnicode());
                }
            }
        }
        // One finding a value at most, naming what its first wrong place shows; a value with no place at all shows nothing.
        Map<PdfFillItem, String> notShown = new LinkedHashMap<>();
        for (PdfFillItem item : written) {
            if (targets.stream().noneMatch(target -> target.item == item)) {
                notShown.put(item, "");
            }
        }
        for (int index = 0; index < targets.size(); index++) {
            PdfFillItem item = targets.get(index).item;
            String shown = textOf(drawn.get(index));
            if (!sameText(shown, PdfFillText.intended(item.value(), true), drawn.get(index))) {
                notShown.putIfAbsent(item, shown);
            }
        }
        for (PdfFillItem item : written) {
            if (notShown.containsKey(item)) {
                findings.add(new PdfFillFinding(item.fieldId(), PdfFillFindingCode.FIELD_NOT_VISIBLE_IN_OUTPUT, notShown.get(item)));
            }
        }
        for (Map.Entry<PdfFillItem, StringBuilder> stray : strays.entrySet()) {
            findings.add(new PdfFillFinding(stray.getKey().fieldId(), PdfFillFindingCode.TEXT_OUTSIDE_FILL_SPOT, stray.getValue().toString()));
        }
        if (!strayAnywhere.isEmpty()) {
            findings.add(new PdfFillFinding(null, PdfFillFindingCode.TEXT_OUTSIDE_FILL_SPOT, strayAnywhere.toString()));
        }
    }

    private static boolean sameRectangle(PdfRect one, PdfRect other) {
        return one.encloses(other, TOLERANCE) && other.encloses(one, TOLERANCE);
    }

    /** The place whose rectangle holds the middle of a letter, or -1. */
    private static int targetAt(List<Target> targets, int pageIndex, PdfRect letter) {
        for (int index = 0; index < targets.size(); index++) {
            Target target = targets.get(index);
            if (target.pageIndex == pageIndex && target.box.grownBy(TOLERANCE).contains(letter.centerX(), letter.centerY())) {
                return index;
            }
        }
        return -1;
    }

    /** A letter is the same letter at the same place, to a tenth of a point. */
    private static String keyOf(TextPosition character, double cropHeight) {
        PdfRect box = PdfGlyphs.boxOf(character, cropHeight);
        return character.getUnicode() + "@" + Math.round(box.x() * 10) + "," + Math.round(box.y() * 10);
    }

    private static String textOf(List<TextPosition> characters) {
        StringBuilder text = new StringBuilder();
        for (PdfLineGrouper.GroupedLine line : PdfLineGrouper.group(characters)) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(line.text());
        }
        return Normalizer.normalize(text.toString(), Normalizer.Form.NFC);
    }

    /**
     * The same words with spaces evened out; or, where the text is spread
     * over cells or wrapped inside a word, the same letters in the same
     * order, since reading back cannot tell a space the layout made from
     * one the value had.
     */
    private static boolean sameText(String shown, String intended, List<TextPosition> characters) {
        String wanted = Normalizer.normalize(intended, Normalizer.Form.NFC);
        if (PdfFillText.collapsedWhitespace(shown).equals(PdfFillText.collapsedWhitespace(wanted))) {
            return true;
        }
        return !characters.isEmpty() && shown.replaceAll("\\s+", "").equals(wanted.replaceAll("\\s+", ""));
    }
}
