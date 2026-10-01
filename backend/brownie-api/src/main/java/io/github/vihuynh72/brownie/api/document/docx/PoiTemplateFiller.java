package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.compile.FilledDocument;
import io.github.vihuynh72.brownie.core.compile.TemplateFillException;
import io.github.vihuynh72.brownie.core.compile.TemplateFillProblemReason;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplateRegistry;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;

import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fills one template's original DOCX bytes using only its {@link
 * FieldDefinition} bindings -- never a field the template does not
 * declare, and never generated prose. Binds a scalar field to its one
 * named content control ({@link FieldBindingTarget.ContentControlTag},
 * the convention the DOCX-binding spike chose and every built-in template
 * uses); a repeated field belongs to a shared prototype row (a table's own
 * repeated row) or paragraph, cloned once per item with each clone's
 * controls retagged so a later fill pass can address them individually --
 * the same structural approach the spike's own {@code RepeatingRegion}
 * proved, generalized here to however many repeated fields one template
 * version actually declares rather than a fixed three-column shape.
 *
 * <p>An entirely empty repeated group is its prototype repeated zero times:
 * the row or paragraph is removed, and with it everything that only ever
 * belonged to one item. A table whose one row is the prototype keeps that
 * row, emptied of the group's controls, because a table cannot be left
 * with no rows at all. The built-in templates are the one exception: their
 * empty group says "No action items recorded." in one line instead, the
 * fixed default their own qualification artifacts were generated and
 * checked against (see {@code BuiltInMinutesTemplateRegistry}). That line
 * names action items, so it reads right only under those templates' own
 * heading and is never written into any other form. A group is recognized
 * as the built-in one by its repeated field IDs being exactly a built-in
 * template's own -- the field definitions every caller already hands this
 * filler, so no caller has to say which template it is filling. It is a
 * fixed line rather than a configurable rule because no {@code
 * RuleRevision} exists for a built-in template.
 */
public final class PoiTemplateFiller implements TemplateFiller {

    private static final String EMPTY_REPEATED_GROUP_TEXT = "No action items recorded.";
    private static final String PLACEHOLDER_TEXT_STYLE = "PlaceholderText";

    /** Each built-in template's repeated field IDs: the groups that say {@link #EMPTY_REPEATED_GROUP_TEXT} when empty. */
    private static final Set<Set<String>> BUILT_IN_REPEATED_GROUPS = BuiltInMinutesTemplateRegistry.all().stream()
            .map(template -> template.fields().stream()
                    .filter(field -> field.cardinality() == FieldCardinality.REPEATED)
                    .map(FieldDefinition::fieldId)
                    .collect(Collectors.toUnmodifiableSet()))
            .collect(Collectors.toUnmodifiableSet());
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    @Override
    public FilledDocument fill(byte[] templateDocxBytes, List<FieldDefinition> fieldDefinitions, DocumentContent content) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(templateDocxBytes))) {
            Map<String, List<String>> intendedText = new LinkedHashMap<>();

            List<FieldDefinition> scalarFields = fieldDefinitions.stream()
                    .filter(field -> field.cardinality() == FieldCardinality.SCALAR)
                    .toList();
            for (FieldDefinition field : scalarFields) {
                String text = scalarText(content, field);
                setContentControlText(document, tagOf(field), text);
                intendedText.put(field.fieldId(), text.isBlank() ? List.of() : List.of(text));
            }

            List<FieldDefinition> repeatedFields = fieldDefinitions.stream()
                    .filter(field -> field.cardinality() == FieldCardinality.REPEATED)
                    .toList();
            if (!repeatedFields.isEmpty()) {
                intendedText.putAll(fillRepeatedGroup(document, repeatedFields, content));
            }

            byte[] docxBytes = toBytes(document);
            return new FilledDocument(docxBytes, intendedText, reopenBodyText(docxBytes));
        } catch (IOException e) {
            throw new TemplateFillException(TemplateFillProblemReason.UNREADABLE_TEMPLATE, "Failed to open template bytes to fill.", e);
        }
    }

    private String scalarText(DocumentContent content, FieldDefinition field) {
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

    private List<String> repeatedTexts(DocumentContent content, FieldDefinition field) {
        FieldValue value = content.fields().get(field.fieldId());
        if (value == null) {
            return List.of();
        }
        return switch (value) {
            case FieldValue.RepeatedTextValue(List<String> values) -> values;
            case FieldValue.RepeatedDateValue(List<LocalDate> dates) -> dates.stream().map(LONG_DATE::format).toList();
            default -> throw new TemplateFillException(
                    TemplateFillProblemReason.CARDINALITY_MISMATCH,
                    "Field " + field.fieldId() + " is declared REPEATED but its value is scalar.");
        };
    }

    /**
     * Every repeated field in the same group must supply the same item
     * count -- they are parallel columns of one logical row, not
     * independent lists. Missing entirely (not present in {@code content}
     * at all) is treated as zero items, matching a scalar field's own
     * missing-value default of blank text.
     */
    private Map<String, List<String>> fillRepeatedGroup(
            XWPFDocument document, List<FieldDefinition> repeatedFields, DocumentContent content) {
        Map<String, List<String>> valuesByField = new LinkedHashMap<>();
        int itemCount = -1;
        for (FieldDefinition field : repeatedFields) {
            List<String> values = repeatedTexts(content, field);
            valuesByField.put(field.fieldId(), values);
            if (itemCount == -1) {
                itemCount = values.size();
            } else if (itemCount != values.size()) {
                throw new TemplateFillException(
                        TemplateFillProblemReason.MISMATCHED_REPEATED_LENGTHS,
                        "Repeated fields in the same group must supply the same number of items; "
                                + field.fieldId() + " has " + values.size() + " but another field in its group has "
                                + itemCount + ".");
            }
        }

        boolean saysWhenEmpty = BUILT_IN_REPEATED_GROUPS.contains(
                repeatedFields.stream().map(FieldDefinition::fieldId).collect(Collectors.toSet()));
        XWPFTable table = document.getTables().isEmpty() ? null : document.getTables().get(0);
        if (table != null && groupTagsResolveInTable(table, repeatedFields)) {
            bindTableRows(table, repeatedFields, valuesByField, itemCount, saysWhenEmpty);
        } else {
            XWPFParagraph prototype = findPrototypeParagraph(document, repeatedFields);
            bindParagraphs(document, prototype, repeatedFields, valuesByField, itemCount, saysWhenEmpty);
        }

        Map<String, List<String>> intended = new LinkedHashMap<>();
        for (FieldDefinition field : repeatedFields) {
            intended.put(field.fieldId(), itemCount == 0 ? List.of() : valuesByField.get(field.fieldId()));
        }
        return intended;
    }

    private boolean groupTagsResolveInTable(XWPFTable table, List<FieldDefinition> repeatedFields) {
        XWPFTableRow lastRow = table.getRow(table.getNumberOfRows() - 1);
        for (FieldDefinition field : repeatedFields) {
            boolean found = lastRow.getTableCells().stream()
                    .flatMap(cell -> cell.getParagraphs().stream())
                    .anyMatch(paragraph -> hasTag(paragraph, tagOf(field)));
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private void bindTableRows(
            XWPFTable table,
            List<FieldDefinition> repeatedFields,
            Map<String, List<String>> valuesByField,
            int itemCount,
            boolean saysWhenEmpty) {
        int prototypeIndex = table.getNumberOfRows() - 1;
        XWPFTableRow prototype = table.getRow(prototypeIndex);

        if (itemCount == 0) {
            if (saysWhenEmpty) {
                explainEmptyGroup(prototype.getTableCells().getFirst().getParagraphs().getFirst(), repeatedFields);
            } else if (table.getNumberOfRows() > 1 && rowHoldsOnlyGroupControls(prototype, repeatedFields)) {
                // The row goes only when nothing else lives in it: a single value filled into the same row stays.
                table.removeRow(prototypeIndex);
                return;
            }
            // A row that stays keeps none of the group's controls, so none of them is left showing its placeholder.
            for (XWPFTableCell cell : prototype.getTableCells()) {
                for (XWPFParagraph paragraph : cell.getParagraphs()) {
                    removeGroupControls(paragraph, repeatedFields);
                }
            }
            return;
        }

        CTRow prototypeXml = prototype.getCtRow();
        for (int i = 0; i < itemCount; i++) {
            XWPFTableRow clonedRow = new XWPFTableRow((CTRow) prototypeXml.copy(), table);
            int index = i;
            for (XWPFTableCell cell : clonedRow.getTableCells()) {
                for (XWPFParagraph paragraph : cell.getParagraphs()) {
                    bindGroupControls(paragraph, index, repeatedFields, valuesByField);
                }
            }
            table.addRow(clonedRow);
        }
        table.removeRow(prototypeIndex);
    }

    private XWPFParagraph findPrototypeParagraph(XWPFDocument document, List<FieldDefinition> repeatedFields) {
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            if (hasTag(paragraph, tagOf(repeatedFields.getFirst()))) {
                return paragraph;
            }
        }
        throw new TemplateFillException(
                TemplateFillProblemReason.BINDING_NOT_FOUND,
                "No prototype paragraph found for repeated field " + repeatedFields.getFirst().fieldId() + ".");
    }

    private void bindParagraphs(
            XWPFDocument document,
            XWPFParagraph prototype,
            List<FieldDefinition> repeatedFields,
            Map<String, List<String>> valuesByField,
            int itemCount,
            boolean saysWhenEmpty) {
        if (itemCount == 0) {
            if (saysWhenEmpty) {
                explainEmptyGroup(prototype, repeatedFields);
            } else if (holdsOnlyGroupControls(prototype, repeatedFields) && !carriesSectionBreak(prototype)) {
                document.removeBodyElement(document.getPosOfParagraph(prototype));
            } else {
                // A single value filled into the same paragraph, or the section break the paragraph ends, stays.
                removeGroupControls(prototype, repeatedFields);
            }
            return;
        }

        // document.createParagraph() always appends at the absolute end of
        // the document body, regardless of where the prototype paragraph
        // actually lives -- confirmed directly against this POI version,
        // not assumed from its Javadoc. That is only harmless when the
        // prototype happens to be the document's own last paragraph, true
        // of today's built-in fixture but not a guarantee this method can
        // rely on for any other layout. document.insertNewParagraph(cursor)
        // inserts immediately BEFORE the cursor's own position -- also
        // confirmed directly, not assumed -- so every clone is inserted at
        // a cursor anchored on the ORIGINAL prototype (which stays in
        // place, still untouched, until the loop finishes), never on the
        // previously inserted clone: anchoring on the latest clone instead
        // would insert each new one before the last, silently reversing
        // the items' own order. Anchoring on the one paragraph that never
        // moves keeps every clone in its item order, landing exactly where
        // the prototype was rather than trailing behind whatever content
        // follows it.
        CTP prototypeXml = prototype.getCTP();
        for (int i = 0; i < itemCount; i++) {
            XWPFParagraph cloned;
            try (XmlCursor cursor = prototype.getCTP().newCursor()) {
                cloned = document.insertNewParagraph(cursor);
            }
            cloned.getCTP().set(prototypeXml.copy());
            bindGroupControls(cloned, i, repeatedFields, valuesByField);
        }
        document.removeBodyElement(document.getPosOfParagraph(prototype));
    }

    private void bindGroupControls(
            XWPFParagraph paragraph, int index, List<FieldDefinition> repeatedFields, Map<String, List<String>> valuesByField) {
        for (FieldDefinition field : repeatedFields) {
            String tag = tagOf(field);
            for (CTSdtRun sdt : paragraph.getCTP().getSdtArray()) {
                if (!tag.equals(controlTagOf(sdt))) {
                    continue;
                }
                String rewrittenTag = tag + "#" + index;
                sdt.getSdtPr().getTag().setVal(rewrittenTag);
                // A control Word shows by its title keeps it in step; one without a title is left without one.
                if (sdt.getSdtPr().isSetAlias()) {
                    sdt.getSdtPr().getAlias().setVal(rewrittenTag);
                }
                setContentControlText(sdt, valuesByField.get(field.fieldId()).get(index));
            }
        }
    }

    /**
     * The line takes the paragraph: its runs, the group's own controls and any untagged control go, so the
     * line never reads straight on from whatever a control Brownie does not fill still shows. A control
     * bound to another field stays, with the value already written into it.
     */
    private void explainEmptyGroup(XWPFParagraph paragraph, List<FieldDefinition> repeatedFields) {
        while (!paragraph.getRuns().isEmpty()) {
            paragraph.removeRun(0);
        }
        List<String> tags = repeatedFields.stream().map(this::tagOf).toList();
        CTP ctp = paragraph.getCTP();
        for (int i = ctp.sizeOfSdtArray() - 1; i >= 0; i--) {
            String tag = controlTagOf(ctp.getSdtArray(i));
            if (tag == null || tags.contains(tag)) {
                ctp.removeSdt(i);
            }
        }
        if (paragraph.getCTP().isSetPPr() && paragraph.getCTP().getPPr().isSetNumPr()) {
            paragraph.getCTP().getPPr().unsetNumPr();
        }
        paragraph.createRun().setText(EMPTY_REPEATED_GROUP_TEXT);
    }

    /** Whether every tagged control in the paragraph belongs to the group: nothing another field fills would go with it. */
    private boolean holdsOnlyGroupControls(XWPFParagraph paragraph, List<FieldDefinition> repeatedFields) {
        List<String> tags = repeatedFields.stream().map(this::tagOf).toList();
        for (CTSdtRun sdt : paragraph.getCTP().getSdtArray()) {
            String tag = controlTagOf(sdt);
            if (tag != null && !tags.contains(tag)) {
                return false;
            }
        }
        return true;
    }

    private boolean rowHoldsOnlyGroupControls(XWPFTableRow row, List<FieldDefinition> repeatedFields) {
        for (XWPFTableCell cell : row.getTableCells()) {
            for (XWPFParagraph paragraph : cell.getParagraphs()) {
                if (!holdsOnlyGroupControls(paragraph, repeatedFields)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A paragraph that ends a section carries its page settings; removing it would merge two sections. */
    private static boolean carriesSectionBreak(XWPFParagraph paragraph) {
        return paragraph.getCTP().isSetPPr() && paragraph.getCTP().getPPr().isSetSectPr();
    }

    private void removeGroupControls(XWPFParagraph paragraph, List<FieldDefinition> repeatedFields) {
        List<String> tags = repeatedFields.stream().map(this::tagOf).toList();
        CTP ctp = paragraph.getCTP();
        for (int i = ctp.sizeOfSdtArray() - 1; i >= 0; i--) {
            if (tags.contains(controlTagOf(ctp.getSdtArray(i)))) {
                ctp.removeSdt(i);
            }
        }
    }

    private boolean hasTag(XWPFParagraph paragraph, String tag) {
        for (CTSdtRun sdt : paragraph.getCTP().getSdtArray()) {
            if (tag.equals(controlTagOf(sdt))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A control's tag, or null for one without: a form can hold controls nobody tagged (Word's own
     * date pickers, a check box), which no field is bound to and which are left exactly as they are.
     */
    private static String controlTagOf(CTSdtRun sdt) {
        return sdt.isSetSdtPr() && sdt.getSdtPr().isSetTag() ? sdt.getSdtPr().getTag().getVal() : null;
    }

    private String tagOf(FieldDefinition field) {
        if (field.binding() instanceof FieldBindingTarget.ContentControlTag(String tag)) {
            return tag;
        }
        throw new TemplateFillException(
                TemplateFillProblemReason.UNREADABLE_TEMPLATE,
                "Field " + field.fieldId() + " is not bound by a stable content-control tag.");
    }

    private void setContentControlText(XWPFDocument document, String tag, String text) {
        List<CTSdtRun> matches = new ArrayList<>();
        for (XWPFParagraph paragraph : allParagraphs(document)) {
            for (CTSdtRun sdt : paragraph.getCTP().getSdtArray()) {
                if (tag.equals(controlTagOf(sdt))) {
                    matches.add(sdt);
                }
            }
        }
        if (matches.isEmpty()) {
            throw new TemplateFillException(TemplateFillProblemReason.BINDING_NOT_FOUND, "No content control tagged \"" + tag + "\" was found.");
        }
        if (matches.size() > 1) {
            throw new TemplateFillException(TemplateFillProblemReason.AMBIGUOUS_BINDING, "More than one content control is tagged \"" + tag + "\".");
        }
        setContentControlText(matches.getFirst(), text);
    }

    private void setContentControlText(CTSdtRun sdt, String text) {
        // A control can be saved with no content element at all; it gets one rather than stopping the fill.
        CTSdtContentRun sdtContent = sdt.isSetSdtContent() ? sdt.getSdtContent() : sdt.addNewSdtContent();
        CTR kept;
        if (sdtContent.sizeOfRArray() == 0) {
            kept = sdtContent.addNewR();
        } else {
            kept = sdtContent.getRArray(0);
            while (kept.sizeOfTArray() > 0) {
                kept.removeT(0);
            }
        }
        CTText t = kept.addNewT();
        t.setStringValue(text);
        preserveBoundarySpaceIfNeeded(t, text);
        for (int i = sdtContent.sizeOfRArray() - 1; i >= 1; i--) {
            sdtContent.removeR(i);
        }
        if (!text.isEmpty()) {
            clearPlaceholderState(sdt, kept);
        }
    }

    /**
     * A control Word saved still showing its prompt ("Click or tap here to
     * enter text.") is marked as showing its placeholder, and the prompt's
     * run is styled Placeholder Text. Left on a written value, both keep
     * Word treating that value as the prompt: shown grey, and selected
     * whole on the first click so typing replaces it. A run style of any
     * other name is the form's own formatting and is kept.
     */
    private static void clearPlaceholderState(CTSdtRun sdt, CTR kept) {
        if (sdt.isSetSdtPr() && sdt.getSdtPr().isSetShowingPlcHdr()) {
            sdt.getSdtPr().unsetShowingPlcHdr();
        }
        if (kept.isSetRPr()) {
            CTRPr runProperties = kept.getRPr();
            for (int i = runProperties.sizeOfRStyleArray() - 1; i >= 0; i--) {
                if (PLACEHOLDER_TEXT_STYLE.equals(runProperties.getRStyleArray(i).getVal())) {
                    runProperties.removeRStyle(i);
                }
            }
        }
    }

    /**
     * Word and LibreOffice are both permitted to trim or collapse
     * untagged boundary whitespace on a {@code <w:t>} run -- without this,
     * a typed value with a leading or trailing space (routine from
     * dictated or copy-pasted free text) would silently lose it on
     * export, exactly the kind of gap {@code XWPFRun.setText()} already
     * guards against elsewhere in POI itself. This mirrors that same
     * guard for the raw XMLBeans calls a content control's own text
     * requires.
     */
    private static void preserveBoundarySpaceIfNeeded(CTText text, String value) {
        if (value.isEmpty() || !Character.isWhitespace(value.charAt(0)) && !Character.isWhitespace(value.charAt(value.length() - 1))) {
            return;
        }
        try (XmlCursor cursor = text.newCursor()) {
            cursor.toNextToken();
            cursor.insertAttributeWithValue(new QName("http://www.w3.org/XML/1998/namespace", "space"), "preserve");
        }
    }

    private List<XWPFParagraph> allParagraphs(XWPFDocument document) {
        List<XWPFParagraph> result = new ArrayList<>(document.getParagraphs());
        for (XWPFTable table : document.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    result.addAll(cell.getParagraphs());
                }
            }
        }
        return result;
    }

    private byte[] toBytes(XWPFDocument document) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize filled DOCX.", e);
        }
    }

    /** Re-opens the produced bytes from scratch, independent of any in-memory state the fill pass above still holds. */
    private String reopenBodyText(byte[] docxBytes) {
        try (XWPFDocument reopened = new XWPFDocument(new ByteArrayInputStream(docxBytes));
                XWPFWordExtractor extractor = new XWPFWordExtractor(reopened)) {
            return extractor.getText();
        } catch (IOException e) {
            throw new TemplateFillException(TemplateFillProblemReason.UNREADABLE_TEMPLATE, "Failed to reopen the filled DOCX for verification.", e);
        }
    }
}
