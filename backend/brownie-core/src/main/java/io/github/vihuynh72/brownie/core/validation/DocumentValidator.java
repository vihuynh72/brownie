package io.github.vihuynh72.brownie.core.validation;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxFeatureFinding;
import io.github.vihuynh72.brownie.core.document.DocxFeatureReport;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.PdfFillFinding;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.ResolvedStyle;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.document.UnsupportedDocxFeature;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, static checks against already-loaded data -- no artifact, renderer,
 * or repository access of its own, the same shape {@link
 * io.github.vihuynh72.brownie.core.compile.IntegrityChecker} and {@code
 * DocumentContentValidator} already use. {@link ValidationService} owns
 * every I/O step (filling, re-extracting, resolving evidence) and calls
 * into these methods with the results.
 */
public final class DocumentValidator {

    /** A page whose sampled pixels differ by more than this fraction from the qualified baseline is worth a person's attention -- see {@link #checkPageRaster}'s own javadoc for why this is a warning, never a blocking finding, on its own. */
    private static final double VISUAL_DIFFERENCE_WARNING_THRESHOLD = 0.05;

    private DocumentValidator() {
    }

    /**
     * Maps a coarse raster comparison into findings: a page-count
     * difference is informational (expected under the default flowing
     * layout), and a page whose pixel difference exceeds {@link
     * #VISUAL_DIFFERENCE_WARNING_THRESHOLD} is a warning, never blocking --
     * anti-aliasing, font hinting, and legitimate reflow all produce
     * nonzero pixel differences on an otherwise-correct page, so this
     * coarse signal alone can suggest a person look, but cannot itself
     * prove a defect the way {@link #checkFieldContentInOutput} or {@link
     * LayoutComparator#compareStructure} can.
     */
    public static List<ValidationFinding> checkPageRaster(PageRasterComparison comparison) {
        List<ValidationFinding> findings = new ArrayList<>();
        if (comparison.baselinePageCount() != comparison.filledPageCount()) {
            findings.add(new ValidationFinding(
                    ValidationFindingCode.LAYOUT_PAGE_COUNT_CHANGED, null,
                    "Rendered page count changed: the qualified baseline is " + comparison.baselinePageCount()
                            + " page(s), the filled output is " + comparison.filledPageCount() + " page(s)."));
        }
        List<Double> perPage = comparison.perPageDifferenceFraction();
        for (int pageIndex = 0; pageIndex < perPage.size(); pageIndex++) {
            double difference = perPage.get(pageIndex);
            if (difference > VISUAL_DIFFERENCE_WARNING_THRESHOLD) {
                findings.add(new ValidationFinding(
                        ValidationFindingCode.LAYOUT_VISUAL_DIFFERENCE_DETECTED, null,
                        "Page " + (pageIndex + 1) + " differs from the qualified baseline by "
                                + Math.round(difference * 100) + "% of sampled pixels."));
            }
        }
        return findings;
    }

    /**
     * A field is required either because the template itself declares it
     * {@link FieldRequiredness#REQUIRED}, or because an accepted {@code
     * RequiredFields} rule names it -- two independent sources this
     * codebase deliberately keeps separate (built-in template metadata
     * vs. a user-approved rule), collapsed here into one finding code
     * since a caller only needs to know a required field is missing, not
     * which source demanded it.
     */
    public static List<ValidationFinding> checkRequiredness(
            DocumentContent content, List<FieldDefinition> fieldDefinitions, List<RuleRevision> acceptedRules) {
        List<ValidationFinding> findings = new ArrayList<>();
        for (FieldDefinition definition : fieldDefinitions) {
            if (definition.requiredness() == FieldRequiredness.REQUIRED && !hasValue(content, definition.fieldId())) {
                findings.add(new ValidationFinding(
                        ValidationFindingCode.MISSING_REQUIRED_FIELD, definition.fieldId(),
                        "Field " + definition.fieldId() + " is required by the template and has no value."));
            }
        }
        for (RuleRevision rule : acceptedRules) {
            if (rule.payload() instanceof RulePayload.RequiredFields(List<String> fieldIds)) {
                for (String fieldId : fieldIds) {
                    if (!hasValue(content, fieldId)) {
                        findings.add(new ValidationFinding(
                                ValidationFindingCode.MISSING_REQUIRED_FIELD, fieldId,
                                "Field " + fieldId + " is required by an accepted rule and has no value."));
                    }
                }
            }
        }
        return findings;
    }

    /** {@code MaxTextLength}/{@code MaxItemCount}: the two accepted rule kinds that bound a field's own current value against a plain, observable fact. */
    public static List<ValidationFinding> checkContentRules(DocumentContent content, List<RuleRevision> acceptedRules) {
        List<ValidationFinding> findings = new ArrayList<>();
        for (RuleRevision rule : acceptedRules) {
            switch (rule.payload()) {
                case RulePayload.MaxTextLength(String fieldId, int maxCharacters) -> {
                    int actual = textLengthOf(content, fieldId);
                    if (actual > maxCharacters) {
                        findings.add(new ValidationFinding(
                                ValidationFindingCode.TEXT_LENGTH_EXCEEDED, fieldId,
                                "Field " + fieldId + " holds " + actual + " characters, exceeding the accepted limit of " + maxCharacters + "."));
                    }
                }
                case RulePayload.MaxItemCount(String fieldId, int maxItems) -> {
                    int actual = itemCountOf(content, fieldId);
                    if (actual > maxItems) {
                        findings.add(new ValidationFinding(
                                ValidationFindingCode.ITEM_COUNT_EXCEEDED, fieldId,
                                "Field " + fieldId + " holds " + actual + " items, exceeding the accepted limit of " + maxItems + "."));
                    }
                }
                default -> {
                    // Every other rule kind is either checked elsewhere (RequiredFields, ProtectedRegion)
                    // or is not a document-content-level check at all (DateDisplayFormat, AllowedSourceKinds,
                    // MissingValueBehavior, AllowedOverflowBehavior, RepeatableRegionEmptyBehavior,
                    // AllowedSectionOrder -- the last has no independent section concept to check against yet,
                    // the same honest gap RulePayloadValidator already names for it).
                }
            }
        }
        return findings;
    }

    /**
     * Whitespace-collapsed containment, the same tolerance {@link
     * io.github.vihuynh72.brownie.core.compile.IntegrityChecker} already
     * applies -- a wrap inserted by re-parsing is a layout detail, not a
     * missing-content defect.
     */
    public static List<ValidationFinding> checkFieldContentInOutput(Map<String, List<String>> intendedText, String reopenedDocxText) {
        String normalizedDocx = normalize(reopenedDocxText);
        List<ValidationFinding> findings = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : intendedText.entrySet()) {
            for (String text : entry.getValue()) {
                if (text.isBlank()) {
                    continue;
                }
                if (!normalizedDocx.contains(normalize(text))) {
                    findings.add(new ValidationFinding(
                            ValidationFindingCode.FIELD_CONTENT_NOT_IN_OUTPUT, entry.getKey(),
                            "Field " + entry.getKey() + "'s intended text did not survive into the filled document's own reopened body text."));
                }
            }
        }
        return findings;
    }

    /**
     * Compares the freshly filled DOCX's re-extraction with its template's
     * own findings: {@code template} is everything today's reader finds in
     * the template's file, refused and kept as it is. A refused finding in
     * the filled document blocks, one finding each, unless the template
     * itself has that kind: a template made when an earlier reader took its
     * file (a tracked paragraph mark, say) fills to a copy today's reader
     * refuses for the same reason, and the fill did not add it. A kind the
     * template keeps as it is may
     * appear in the filled document any number of times -- a repeated row
     * cloned per item legitimately repeats its shapes and fields -- so only
     * a kind the template does not have at all is reported, once. A field is
     * compared by its kind of field as well ({@code PAGE}, {@code DATE}): a
     * filled document with a field kind its template never had did not get
     * it from the template. An empty list means the fill pass introduced
     * nothing its template did not already have.
     */
    public static List<ValidationFinding> checkPackageIntegrity(DocxFeatureReport template, DocxExtractionOutcome filled) {
        List<ValidationFinding> findings = new ArrayList<>();
        switch (filled) {
            case DocxExtractionOutcome.Unsupported unsupported -> {
                Set<UnsupportedDocxFeature> templateRefuses = new HashSet<>();
                for (DocxFeatureFinding finding : template.refused()) {
                    templateRefuses.add(finding.feature());
                }
                List<DocxFeatureFinding> refused = unsupported.featureReport().refused();
                for (DocxFeatureFinding finding : refused) {
                    if (templateRefuses.contains(finding.feature())) {
                        continue;
                    }
                    findings.add(new ValidationFinding(
                            ValidationFindingCode.PACKAGE_INTEGRITY_FAILURE, null,
                            "Filled document introduced unsupported feature " + finding.feature() + " at " + finding.location()
                                    + " (" + finding.detail() + ")."));
                }
                if (refused.isEmpty()) {
                    findings.add(new ValidationFinding(
                            ValidationFindingCode.PACKAGE_INTEGRITY_FAILURE, null,
                            "Filled document could not be read as a supported document."));
                }
            }
            case DocxExtractionOutcome.Supported supported -> {
                Set<String> templateKinds = new HashSet<>();
                for (DocxFeatureFinding finding : template.keptAsIs()) {
                    templateKinds.add(keptAsIsKind(finding));
                }
                Set<String> reported = new HashSet<>();
                for (DocxFeatureFinding finding : supported.keptAsIs().keptAsIs()) {
                    String kind = keptAsIsKind(finding);
                    if (!templateKinds.contains(kind) && reported.add(kind)) {
                        findings.add(new ValidationFinding(
                                ValidationFindingCode.PACKAGE_INTEGRITY_FAILURE, null,
                                "Filled document has " + kind + " at " + finding.location() + " (" + finding.detail()
                                        + "), which its template does not have."));
                    }
                }
            }
        }
        return findings;
    }

    private static String keptAsIsKind(DocxFeatureFinding finding) {
        if (finding.feature() == UnsupportedDocxFeature.DYNAMIC_FIELD) {
            String keyword = finding.fieldKeyword();
            return finding.feature() + " " + (keyword == null ? "" : keyword);
        }
        return finding.feature().name();
    }

    /**
     * For every accepted {@code ProtectedRegion} rule, compares the target
     * node's own subtree text and directly-resolved style between the
     * template's own source graph and the freshly filled document's graph.
     * A {@link FieldBindingTarget.ContentControlTag} is matched by its
     * stable tag in both graphs. A {@link FieldBindingTarget.StructuralNode}
     * is matched by its literal node ID within the named part -- reliable
     * here because both graphs come from the same template lineage (a
     * blank source and its own fill, not two independently authored
     * documents), but still only exactly stable for a node that is not
     * itself positioned after a REPEATED field's cloned rows within the
     * same part, a named, honest limitation rather than a hidden one.
     */
    public static List<ValidationFinding> checkProtectedRegions(
            DocxStructuralGraph baselineGraph, DocxStructuralGraph filledGraph, List<RuleRevision> acceptedRules) {
        List<ValidationFinding> findings = new ArrayList<>();
        for (RuleRevision rule : acceptedRules) {
            if (!(rule.payload() instanceof RulePayload.ProtectedRegion(FieldBindingTarget target))) {
                continue;
            }
            StructuralNode baselineNode = findTarget(baselineGraph, target);
            StructuralNode filledNode = findTarget(filledGraph, target);
            String label = targetLabel(target);
            if (baselineNode == null || filledNode == null) {
                findings.add(new ValidationFinding(
                        ValidationFindingCode.PROTECTED_REGION_MODIFIED, null,
                        "Protected region " + label + " could not be located in " + (baselineNode == null ? "the template's own source" : "the filled document") + "."));
                continue;
            }
            String baselineText = normalize(collectText(baselineNode));
            String filledText = normalize(collectText(filledNode));
            if (!baselineText.equals(filledText)) {
                findings.add(new ValidationFinding(
                        ValidationFindingCode.PROTECTED_REGION_MODIFIED, null,
                        "Protected region " + label + "'s own text changed between the template's source and the filled document."));
                continue;
            }
            if (!collectStyles(baselineNode).equals(collectStyles(filledNode))) {
                findings.add(new ValidationFinding(
                        ValidationFindingCode.PROTECTED_REGION_MODIFIED, null,
                        "Protected region " + label + "'s own style changed between the template's source and the filled document."));
            }
        }
        return findings;
    }

    private static boolean hasValue(DocumentContent content, String fieldId) {
        FieldValue value = content.fields().get(fieldId);
        if (value == null) {
            return false;
        }
        return switch (value) {
            case FieldValue.TextValue(String v) -> !v.isBlank();
            case FieldValue.DateValue ignored -> true;
            case FieldValue.RepeatedTextValue(List<String> values) -> !values.isEmpty();
            case FieldValue.RepeatedDateValue(List<java.time.LocalDate> values) -> !values.isEmpty();
        };
    }

    private static int textLengthOf(DocumentContent content, String fieldId) {
        return switch (content.fields().get(fieldId)) {
            case null -> 0;
            case FieldValue.TextValue(String v) -> v.length();
            default -> 0;
        };
    }

    private static int itemCountOf(DocumentContent content, String fieldId) {
        return switch (content.fields().get(fieldId)) {
            case null -> 0;
            case FieldValue.RepeatedTextValue(List<String> values) -> values.size();
            case FieldValue.RepeatedDateValue(List<java.time.LocalDate> values) -> values.size();
            default -> 0;
        };
    }

    private static StructuralNode findTarget(DocxStructuralGraph graph, FieldBindingTarget target) {
        return switch (target) {
            case FieldBindingTarget.ContentControlTag(String tag) -> graph.parts().stream()
                    .map(DocumentPart::root)
                    .map(root -> findByTag(root, tag))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            case FieldBindingTarget.StructuralNode(var part, String nodeId) -> graph.parts().stream()
                    .filter(documentPart -> documentPart.kind() == part)
                    .map(DocumentPart::root)
                    .map(root -> findByNodeId(root, nodeId))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            // A protected region never names a place on a PDF (rule validation refuses one), so there is nothing to find.
            case FieldBindingTarget.AcroFormField ignored -> null;
            case FieldBindingTarget.PageBox ignored -> null;
        };
    }

    private static StructuralNode findByTag(StructuralNode node, String tag) {
        if (node.kind() == StructuralNodeKind.CONTENT_CONTROL && tag.equals(node.contentControlTag())) {
            return node;
        }
        for (StructuralNode child : node.children()) {
            StructuralNode found = findByTag(child, tag);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static StructuralNode findByNodeId(StructuralNode node, String nodeId) {
        if (nodeId.equals(node.nodeId())) {
            return node;
        }
        for (StructuralNode child : node.children()) {
            StructuralNode found = findByNodeId(child, nodeId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String collectText(StructuralNode node) {
        StringBuilder builder = new StringBuilder();
        collectText(node, builder);
        return builder.toString();
    }

    private static void collectText(StructuralNode node, StringBuilder builder) {
        if (node.text() != null) {
            builder.append(node.text());
        }
        for (StructuralNode child : node.children()) {
            collectText(child, builder);
        }
    }

    /**
     * {@link ResolvedStyle} is only ever populated on a PARAGRAPH or RUN
     * node, never on the CONTENT_CONTROL or plain STRUCTURAL node a {@link
     * FieldBindingTarget} actually addresses -- so comparing the target
     * node's own {@code style()} directly would always compare two nulls
     * and never catch a real style change on the run(s) underneath it.
     * This walks the whole subtree in document order and collects every
     * non-null style actually found.
     */
    private static List<ResolvedStyle> collectStyles(StructuralNode node) {
        List<ResolvedStyle> styles = new ArrayList<>();
        collectStyles(node, styles);
        return styles;
    }

    private static void collectStyles(StructuralNode node, List<ResolvedStyle> styles) {
        if (node.style() != null) {
            styles.add(node.style());
        }
        for (StructuralNode child : node.children()) {
            collectStyles(child, styles);
        }
    }

    private static String targetLabel(FieldBindingTarget target) {
        return switch (target) {
            case FieldBindingTarget.ContentControlTag(String tag) -> "content control \"" + tag + "\"";
            case FieldBindingTarget.StructuralNode(var part, String nodeId) -> part + " node " + nodeId;
            case FieldBindingTarget.AcroFormField(String name) -> "PDF form field \"" + name + "\"";
            case FieldBindingTarget.PageBox box -> "box on page " + box.page();
        };
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Maps what filling and checking a PDF template found into findings a
     * person can act on, one per finding, each naming its field: text too
     * long for its place (or for the form's field) and a character that
     * cannot be printed block export and name what to change; text made
     * smaller to fit is only reported, with the size used; anything the
     * check could not find where it belongs blocks, as does anything the
     * fill changed outside its places.
     */
    public static List<ValidationFinding> checkPdfFill(List<PdfFillFinding> fillFindings) {
        List<ValidationFinding> findings = new ArrayList<>();
        for (PdfFillFinding finding : fillFindings) {
            String detail = finding.detail();
            findings.add(switch (finding.code()) {
                case FIXED_FIELD_OVERFLOW -> new ValidationFinding(ValidationFindingCode.FIXED_FIELD_OVERFLOW, finding.fieldId(),
                        "This text is too long to fit here, even made smaller. Shorten it.");
                case MAX_LENGTH_EXCEEDED -> new ValidationFinding(ValidationFindingCode.FIXED_FIELD_OVERFLOW, finding.fieldId(),
                        "This text is longer than the form allows here" + (detail == null ? "" : " (" + detail + " characters at most)")
                                + ". Shorten it.");
                case FIELD_TEXT_SHRUNK -> new ValidationFinding(ValidationFindingCode.FIELD_TEXT_SHRUNK, finding.fieldId(),
                        "Made the text smaller" + (detail == null ? "" : " (" + detail + " pt)") + " to fit.");
                case UNSUPPORTED_CHARACTER -> new ValidationFinding(ValidationFindingCode.UNSUPPORTED_CHARACTER, finding.fieldId(),
                        "The character " + quoted(detail) + " cannot be printed in this PDF. Remove it or use another character.");
                case SCRIPT_NOT_SUPPORTED -> new ValidationFinding(ValidationFindingCode.UNSUPPORTED_CHARACTER, finding.fieldId(),
                        "Brownie cannot write this script into a PDF yet" + (detail == null ? "" : " (it starts at " + quoted(detail) + ")")
                                + ". Use another language for this answer.");
                case FIELD_NOT_FILLABLE -> new ValidationFinding(ValidationFindingCode.FIELD_CONTENT_NOT_IN_OUTPUT, finding.fieldId(),
                        "Brownie could not write into this place in the PDF.");
                case FIELD_VALUE_NOT_STORED, FIELD_APPEARANCE_MISSING, FIELD_NOT_VISIBLE_IN_OUTPUT -> new ValidationFinding(
                        ValidationFindingCode.FIELD_CONTENT_NOT_IN_OUTPUT, finding.fieldId(),
                        "The filled PDF does not show this answer where it belongs.");
                case TEXT_OUTSIDE_FILL_SPOT -> new ValidationFinding(ValidationFindingCode.CHANGE_OUTSIDE_FILL_SPOTS, finding.fieldId(),
                        "Some of this answer landed outside its place in the PDF.");
                case OTHER_FIELD_CHANGED -> new ValidationFinding(ValidationFindingCode.CHANGE_OUTSIDE_FILL_SPOTS, null,
                        "Filling the PDF changed one of its own fields that Brownie was not filling"
                                + (detail == null ? "" : " (" + detail + ")") + ".");
                case FIELD_COUNT_CHANGED -> new ValidationFinding(ValidationFindingCode.CHANGE_OUTSIDE_FILL_SPOTS, null,
                        "Filling the PDF changed how many fields it has.");
            });
        }
        return findings;
    }

    /**
     * Maps drawing the uploaded PDF and the filled one, compared everywhere
     * but the places filled: a page that changed anywhere else, or a
     * different number of pages, blocks export; a page that could not be
     * drawn, or a scanned picture that could not be, is a warning that says
     * which page was not fully checked.
     */
    public static List<ValidationFinding> checkMaskedRaster(MaskedRasterComparison comparison) {
        List<ValidationFinding> findings = new ArrayList<>();
        if (comparison.referencePageCount() != comparison.filledPageCount()) {
            findings.add(new ValidationFinding(ValidationFindingCode.CHANGE_OUTSIDE_FILL_SPOTS, null,
                    "The filled PDF has " + comparison.filledPageCount() + " page(s); the form has " + comparison.referencePageCount() + "."));
        }
        for (MaskedPageComparison page : comparison.pages()) {
            if (page.changed()) {
                findings.add(new ValidationFinding(ValidationFindingCode.CHANGE_OUTSIDE_FILL_SPOTS, null,
                        "Page " + page.pageNumber() + " changed outside the places Brownie filled."));
            } else if (page.outcome() != MaskedPageComparison.Outcome.COMPARED) {
                findings.add(new ValidationFinding(ValidationFindingCode.LAYOUT_COMPARISON_UNAVAILABLE, null,
                        "Brownie could not draw page " + page.pageNumber() + " to check it; it checked the text it added."));
            }
            if (page.pictureNotCompared()) {
                findings.add(new ValidationFinding(ValidationFindingCode.PICTURE_NOT_COMPARED, null,
                        "Brownie could not draw the scanned picture on page " + page.pageNumber()
                                + " to check it; it checked the text it added."));
            }
        }
        return findings;
    }

    /**
     * Where filling these fields is meant to change a PDF's pages: each box,
     * and each place a bound form field is shown. A masked comparison
     * ignores these and reports any other change.
     */
    public static List<RasterMask> fillSpotMasks(List<FieldDefinition> fields, PdfFormGraph graph) {
        List<RasterMask> masks = new ArrayList<>();
        for (FieldDefinition field : fields) {
            switch (field.binding()) {
                case FieldBindingTarget.PageBox box -> masks.add(new RasterMask(box.page(), box.x(), box.y(), box.width(), box.height()));
                case FieldBindingTarget.AcroFormField(String name) -> graph.acroForm().fields().stream()
                        .filter(formField -> formField.fullName().equals(name))
                        .flatMap(formField -> formField.widgets().stream())
                        .forEach(widget -> masks.add(new RasterMask(
                                widget.pageNumber(), widget.box().x(), widget.box().y(), widget.box().width(), widget.box().height())));
                case FieldBindingTarget.ContentControlTag ignored -> {
                }
                case FieldBindingTarget.StructuralNode ignored -> {
                }
            }
        }
        return masks;
    }

    /** A character named in a message, with its code point, so an invisible or look-alike one can still be found. */
    private static String quoted(String character) {
        if (character == null || character.isEmpty()) {
            return "(unknown)";
        }
        return "\u201c" + character + "\u201d (U+" + String.format(java.util.Locale.ROOT, "%04X", character.codePointAt(0)) + ")";
    }
}
