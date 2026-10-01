package io.github.vihuynh72.brownie.core.compile;

import io.github.vihuynh72.brownie.core.document.FilledPdf;
import io.github.vihuynh72.brownie.core.document.PdfFillFinding;
import io.github.vihuynh72.brownie.core.document.PdfFillFindingCode;
import io.github.vihuynh72.brownie.core.document.PdfFillItem;
import io.github.vihuynh72.brownie.core.document.PdfFillRequest;
import io.github.vihuynh72.brownie.core.document.PdfFillTarget;
import io.github.vihuynh72.brownie.core.document.PdfFillText;
import io.github.vihuynh72.brownie.core.document.PdfFillVerifier;
import io.github.vihuynh72.brownie.core.document.PdfFormFiller;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fills a PDF template's source with one revision's values and checks the
 * result, the one way compiling, qualifying and validating a PDF template
 * all do it, so the three can never disagree about what was written.
 *
 * <p>Each field becomes one fill item: a form field is filled with its text
 * made smaller to fit when it must (the form set the field's size, and a
 * person can still edit it later in their own reader); a box keeps the
 * overflow choice stored with it. A date is written as the Word filler
 * writes one, "September 28, 2026". A field that holds a list of values is
 * refused: a PDF form holds one value in each place, and nothing here
 * decides how a list would be spread over them.
 */
public class PdfTemplateFill {

    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private final PdfFormFiller filler;
    private final PdfFillVerifier verifier;

    public PdfTemplateFill(PdfFormFiller filler, PdfFillVerifier verifier) {
        this.filler = filler;
        this.verifier = verifier;
    }

    /** Names the filler, the library and the fonts, for the record of how a filled PDF was made. */
    public String fillerVersion() {
        return filler.fillerVersion();
    }

    /**
     * Fills and then checks. Every value the filler could not write, and
     * everything the check found, is in the result; nothing is thrown for
     * a value, only for a template this cannot fill at all.
     *
     * @throws TemplateFillException when a field holds a list, is bound to a
     *         place in a Word file, or holds a value of the wrong shape
     */
    public Result fill(byte[] sourcePdf, List<FieldDefinition> fields, DocumentContent content) {
        PdfFillRequest request = request(fields, content);
        FilledPdf filled = filler.fill(sourcePdf, request);
        List<PdfFillFinding> checked = verifier.verify(sourcePdf, filled, request);
        return new Result(request, filled, checked);
    }

    /** One fill item per field, in the fields' order. */
    static PdfFillRequest request(List<FieldDefinition> fields, DocumentContent content) {
        List<PdfFillItem> items = new ArrayList<>();
        for (FieldDefinition field : fields) {
            if (field.cardinality() == FieldCardinality.REPEATED) {
                throw new TemplateFillException(
                        TemplateFillProblemReason.REPEATED_FIELD_IN_PDF,
                        "Field " + field.fieldId() + " holds a list of values, and a PDF form holds one value in each place."
                                + " Lists cannot be filled into a PDF form yet.");
            }
            items.add(switch (field.binding()) {
                case FieldBindingTarget.AcroFormField(String name) ->
                        new PdfFillItem(field.fieldId(), new PdfFillTarget.Widget(name), textOf(content, field), PdfOverflowPolicy.SHRINK_TO_FIT);
                case FieldBindingTarget.PageBox box -> new PdfFillItem(
                        field.fieldId(),
                        new PdfFillTarget.Box(box.page(), box.box(), box.style(), box.multiline()),
                        textOf(content, field),
                        box.overflow());
                case FieldBindingTarget.ContentControlTag ignored -> throw wordBinding(field);
                case FieldBindingTarget.StructuralNode ignored -> throw wordBinding(field);
            });
        }
        return new PdfFillRequest(items);
    }

    /** A value as it is printed: text as typed, a date in the long form. */
    static String textOf(DocumentContent content, FieldDefinition field) {
        FieldValue value = content.fields().get(field.fieldId());
        if (value == null) {
            return "";
        }
        return switch (value) {
            case FieldValue.TextValue(String text) -> text;
            case FieldValue.DateValue(LocalDate date) -> LONG_DATE.format(date);
            default -> throw new TemplateFillException(
                    TemplateFillProblemReason.CARDINALITY_MISMATCH,
                    "Field " + field.fieldId() + " is declared SCALAR but its value is repeated.");
        };
    }

    /** A date as a PDF template prints it. */
    public static String longDate(LocalDate date) {
        return LONG_DATE.format(date);
    }

    private static TemplateFillException wordBinding(FieldDefinition field) {
        return new TemplateFillException(
                TemplateFillProblemReason.BINDING_NOT_FOUND,
                "Field " + field.fieldId() + " is bound to a place in a Word file, which a PDF does not have.");
    }

    /**
     * What one fill and its check produced. {@code filled} holds the bytes
     * and what filling found; {@code checked} what reading the output back
     * found.
     */
    public record Result(PdfFillRequest request, FilledPdf filled, List<PdfFillFinding> checked) {

        public Result {
            checked = List.copyOf(checked);
        }

        /** What filling found, then what checking found. */
        public List<PdfFillFinding> findings() {
            List<PdfFillFinding> all = new ArrayList<>(filled.findings());
            all.addAll(checked);
            return all;
        }

        /** Every field with a blocking finding from either step, in the order found. */
        public List<String> failedFieldIds() {
            Set<String> failed = new LinkedHashSet<>();
            for (PdfFillFinding finding : findings()) {
                if (finding.blocking() && finding.fieldId() != null) {
                    failed.add(finding.fieldId());
                }
            }
            return List.copyOf(failed);
        }

        /**
         * One integrity finding per value that had text to write, in the
         * shape a Word compilation reports: the value stored in the form (or,
         * for a box, written at all) is the "editable" copy, and the text
         * drawn on the page is the "rendered" copy.
         */
        public List<IntegrityFinding> integrityFindings() {
            List<IntegrityFinding> integrity = new ArrayList<>();
            for (PdfFillItem item : request.items()) {
                String intended = PdfFillText.intended(item.value(), multiline(item.target()));
                if (intended.isBlank()) {
                    continue;
                }
                boolean written = filled.wrote(item.fieldId());
                boolean stored = written && checked.stream().noneMatch(finding -> item.fieldId().equals(finding.fieldId())
                        && finding.code() == PdfFillFindingCode.FIELD_VALUE_NOT_STORED);
                boolean drawn = written && checked.stream().noneMatch(finding -> item.fieldId().equals(finding.fieldId())
                        && finding.blocking() && finding.code() != PdfFillFindingCode.FIELD_VALUE_NOT_STORED);
                integrity.add(new IntegrityFinding(item.fieldId(), intended, stored, drawn));
            }
            return integrity;
        }

        private static boolean multiline(PdfFillTarget target) {
            return target instanceof PdfFillTarget.Box box && box.multiline();
        }
    }
}
