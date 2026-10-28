package io.github.vihuynh72.brownie.core.compile;

import io.github.vihuynh72.brownie.core.document.FilledPdf;
import io.github.vihuynh72.brownie.core.document.PdfFillFinding;
import io.github.vihuynh72.brownie.core.document.PdfFillFindingCode;
import io.github.vihuynh72.brownie.core.document.PdfFillItem;
import io.github.vihuynh72.brownie.core.document.PdfFillRequest;
import io.github.vihuynh72.brownie.core.document.PdfFillTarget;
import io.github.vihuynh72.brownie.core.document.PdfFillText;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
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
import java.util.Map;
import java.util.Map;
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
    public Result fill(byte[] sourcePdf, PdfFormGraph graph, List<FieldDefinition> fields, DocumentContent content) {
        PdfFillRequest request = request(graph, fields, content);
        FilledPdf filled = filler.fill(sourcePdf, request);
        List<PdfFillFinding> checked = verifier.verify(sourcePdf, filled, request);
        return new Result(request, filled, checked);
    }

    /** One fill item per field, in the fields' order. */
    static PdfFillRequest request(PdfFormGraph graph, List<FieldDefinition> fields, DocumentContent content) {
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
        splitDigitGroups(graph, fields, items);
        return new PdfFillRequest(items);
    }

    /**
     * A run of two or more fields sharing one label, each bound to a form
     * text field with a character limit (a W-9's Social Security number is
     * three), takes a value whose letters and digits alone are exactly their
     * limits summed, split across the run in order; anything else is left as
     * one value per field.
     */
    private static void splitDigitGroups(PdfFormGraph graph, List<FieldDefinition> fields, List<PdfFillItem> items) {
        if (graph == null || graph.acroForm() == null) {
            return;
        }
        Map<String, PdfFormGraph.Field> acroFields = graph.acroForm().fields().stream()
                .collect(java.util.stream.Collectors.toMap(PdfFormGraph.Field::fullName, field -> field, (a, b) -> a));
        for (int index = 0; index < fields.size(); index++) {
            FieldDefinition first = fields.get(index);
            if (!(first.binding() instanceof FieldBindingTarget.AcroFormField) || first.label() == null) {
                continue;
            }
            int end = index;
            while (end + 1 < fields.size()
                    && fields.get(end + 1).binding() instanceof FieldBindingTarget.AcroFormField
                    && first.label().equals(fields.get(end + 1).label())) {
                end++;
            }
            if (end == index) {
                continue;
            }
            List<Integer> limits = new ArrayList<>();
            boolean digitGroup = true;
            for (int at = index; at <= end; at++) {
                if (!(fields.get(at).binding() instanceof FieldBindingTarget.AcroFormField(String name))) {
                    digitGroup = false;
                    break;
                }
                PdfFormGraph.Field acroField = acroFields.get(name);
                if (acroField == null || acroField.kind() != PdfFormGraph.FieldKind.TEXT || acroField.maxLen() == null) {
                    digitGroup = false;
                    break;
                }
                limits.add(acroField.maxLen());
            }
            if (digitGroup) {
                int total = limits.stream().mapToInt(Integer::intValue).sum();
                for (int at = index; at <= end; at++) {
                    String lettersAndDigits = items.get(at).value().codePoints()
                            .filter(Character::isLetterOrDigit)
                            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                            .toString();
                    if (lettersAndDigits.codePointCount(0, lettersAndDigits.length()) == total) {
                        int offset = 0;
                        for (int part = index; part <= end; part++) {
                            int limit = limits.get(part - index);
                            String value = lettersAndDigits.substring(
                                    lettersAndDigits.offsetByCodePoints(0, offset),
                                    lettersAndDigits.offsetByCodePoints(0, offset + limit));
                            items.set(part, new PdfFillItem(
                                    items.get(part).fieldId(), items.get(part).target(), value, items.get(part).overflow()));
                            offset += limit;
                        }
                        break;
                    }
                }
            }
            index = end;
        }
    }

    /** A value as it is printed: text as typed, a date in the long form. */
    static String textOf(DocumentContent content, FieldDefinition field) {
        FieldValue value = content.fields().get(field.fieldId());
        if (value instanceof FieldValue.CheckboxValue || field.type() == io.github.vihuynh72.brownie.core.template.FieldType.CHECKBOX) {
            if (!(field.binding() instanceof FieldBindingTarget.AcroFormField)) {
                throw new UnsupportedOperationException("Checkbox filling is not available for this kind of place yet.");
            }
            return value instanceof FieldValue.CheckboxValue checkbox ? checkbox.checked() ? "checked" : "unchecked" : "";
        }
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
