package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a set of {@link FieldDefinition} bindings is actually
 * supported by one DOCX's extracted structure, or by one PDF's form
 * reading, kept separate from persistence so it can run identically
 * whether replacing draft bindings or re-checking them at activation. A
 * Word binding is supported only if it resolves to exactly one node; zero
 * or more than one is a real problem, not a warning, since either leaves
 * "which content is this field" undetermined. A PDF binding is supported
 * only if it names a place a value can really be written: a form field
 * that takes text, or a box wholly on its page, big enough to write in and
 * not covering another place. A binding of the other kind of template is
 * never supported.
 */
public final class TemplateBindingValidator {

    /** The smallest box, in points, a value can be written in: about one short word at the smallest size text shrinks to. */
    public static final double MIN_BOX_WIDTH = 8;
    public static final double MIN_BOX_HEIGHT = 6;

    /** The share of the smaller of two places they may have in common before one is said to cover the other. */
    static final double MAX_OVERLAP_SHARE = 0.2;

    /** How far past the page's edge, in points, a box may reach and still be on the page: rounding, not a real overhang. */
    private static final double PAGE_EDGE_TOLERANCE = 0.5;

    private TemplateBindingValidator() {
    }

    /** One physical location a {@link FieldBindingTarget} resolved to, identified the same way regardless of which binding kind found it -- so a {@code ContentControlTag} match and a {@code StructuralNode} match can be compared for whether they name the same real spot. */
    public record ResolvedLocation(DocumentPartKind part, String nodeId) {
    }

    /** Every problem found, across every field -- empty means every binding in {@code fields} is valid and unambiguous. */
    public static List<UnsupportedBinding> validate(DocxStructuralGraph graph, List<FieldDefinition> fields) {
        List<UnsupportedBinding> problems = new ArrayList<>();
        Set<String> seenFieldIds = new HashSet<>();
        for (FieldDefinition field : fields) {
            if (!seenFieldIds.add(field.fieldId())) {
                problems.add(new UnsupportedBinding(field.fieldId(), UnsupportedBindingReason.DUPLICATE_FIELD_ID));
                continue;
            }
            if (field.binding().templateKind() != TemplateKind.DOCX) {
                problems.add(new UnsupportedBinding(field.fieldId(), UnsupportedBindingReason.WRONG_FORMAT));
                continue;
            }
            int matchCount = matchCount(graph, field.binding());
            if (matchCount == 0) {
                problems.add(new UnsupportedBinding(field.fieldId(), UnsupportedBindingReason.NOT_FOUND));
            } else if (matchCount > 1) {
                problems.add(new UnsupportedBinding(field.fieldId(), UnsupportedBindingReason.AMBIGUOUS));
            }
        }
        return problems;
    }

    /**
     * How many nodes in {@code graph} match {@code target} -- exactly one
     * means the target unambiguously resolves; zero or more than one does
     * not. Exposed for reuse by anything else that needs to resolve a
     * {@link FieldBindingTarget} against a graph the same way {@link
     * #validate} does per field, for example a rule whose own target is a
     * binding rather than a named field.
     */
    public static int matchCount(DocxStructuralGraph graph, FieldBindingTarget target) {
        return resolve(graph, target).size();
    }

    /** {@link #resolve}, narrowed to the single location a target names when it is actually unambiguous -- empty otherwise, the same NOT_FOUND-or-AMBIGUOUS-collapsed-to-nothing shape {@link #matchCount} already treats as "not usable." Exposed so a caller comparing two different bindings' own real locations (not just their own {@link FieldBindingTarget} shape, which can differ while still naming the same node) does not have to re-walk the graph itself. */
    public static Optional<ResolvedLocation> resolveUnique(DocxStructuralGraph graph, FieldBindingTarget target) {
        List<ResolvedLocation> locations = resolve(graph, target);
        return locations.size() == 1 ? Optional.of(locations.get(0)) : Optional.empty();
    }

    /** A binding on a PDF page, or any binding without a Word graph to look in (a PDF template's), resolves to no node. */
    private static List<ResolvedLocation> resolve(DocxStructuralGraph graph, FieldBindingTarget target) {
        if (graph == null) {
            return List.of();
        }
        return switch (target) {
            case FieldBindingTarget.ContentControlTag(String tag) -> graph.parts().stream()
                    .flatMap(part -> collectContentControlTag(part.root(), tag).stream()
                            .map(nodeId -> new ResolvedLocation(part.kind(), nodeId)))
                    .toList();
            case FieldBindingTarget.StructuralNode(var part, String nodeId) -> graph.parts().stream()
                    .filter(p -> p.kind() == part)
                    .flatMap(p -> collectNodeId(p.root(), nodeId).stream().map(id -> new ResolvedLocation(p.kind(), id)))
                    .toList();
            case FieldBindingTarget.AcroFormField ignored -> List.of();
            case FieldBindingTarget.PageBox ignored -> List.of();
        };
    }

    private static List<String> collectContentControlTag(StructuralNode node, String tag) {
        List<String> found = new ArrayList<>();
        if (node.kind() == StructuralNodeKind.CONTENT_CONTROL && tag.equals(node.contentControlTag())) {
            found.add(node.nodeId());
        }
        for (StructuralNode child : node.children()) {
            found.addAll(collectContentControlTag(child, tag));
        }
        return found;
    }

    private static List<String> collectNodeId(StructuralNode node, String nodeId) {
        List<String> found = new ArrayList<>();
        if (nodeId.equals(node.nodeId())) {
            found.add(node.nodeId());
        }
        for (StructuralNode child : node.children()) {
            found.addAll(collectNodeId(child, nodeId));
        }
        return found;
    }

    /**
     * Every problem found across every field against one PDF's form
     * reading -- empty means each binding names a place a value can be
     * written. Each field gets at most one reason, the first that applies:
     * the other kind of template's binding, a form field that is missing or
     * cannot take text, a box off its page or too small, and last a place
     * that covers another field's (only the later of the two is reported)
     * or one of the form's own fields.
     */
    public static List<UnsupportedBinding> validate(PdfFormGraph graph, List<FieldDefinition> fields) {
        List<UnsupportedBinding> problems = new ArrayList<>();
        Set<String> seenFieldIds = new HashSet<>();
        Set<String> boundFormFields = new HashSet<>();
        Map<Integer, List<PdfRect>> placesSoFar = new HashMap<>();
        for (FieldDefinition field : fields) {
            if (!seenFieldIds.add(field.fieldId())) {
                problems.add(new UnsupportedBinding(field.fieldId(), UnsupportedBindingReason.DUPLICATE_FIELD_ID));
                continue;
            }
            UnsupportedBindingReason reason = switch (field.binding()) {
                case FieldBindingTarget.ContentControlTag ignored -> UnsupportedBindingReason.WRONG_FORMAT;
                case FieldBindingTarget.StructuralNode ignored -> UnsupportedBindingReason.WRONG_FORMAT;
                case FieldBindingTarget.AcroFormField(String name) -> formFieldProblem(graph, name, boundFormFields);
                case FieldBindingTarget.PageBox box -> boxProblem(graph, box, placesSoFar);
            };
            if (reason != null) {
                problems.add(new UnsupportedBinding(field.fieldId(), reason));
            }
        }
        return problems;
    }

    /** The form's field with this full name, if the reading has one. */
    public static Optional<PdfFormGraph.Field> formField(PdfFormGraph graph, String fullName) {
        return graph.acroForm().fields().stream().filter(field -> field.fullName().equals(fullName)).findFirst();
    }

    /** Whether a form field can take a value Brownie writes: a text field, not read-only, shown on at least one page. */
    public static boolean fillable(PdfFormGraph.Field field) {
        return field.kind() == PdfFormGraph.FieldKind.TEXT && !field.readOnly() && !field.widgets().isEmpty();
    }

    private static UnsupportedBindingReason formFieldProblem(PdfFormGraph graph, String name, Set<String> boundFormFields) {
        Optional<PdfFormGraph.Field> field = formField(graph, name);
        if (field.isEmpty()) {
            return UnsupportedBindingReason.NOT_FOUND;
        }
        if (!fillable(field.get())) {
            return UnsupportedBindingReason.NOT_FILLABLE;
        }
        return boundFormFields.add(name) ? null : UnsupportedBindingReason.OVERLAPS;
    }

    private static UnsupportedBindingReason boxProblem(
            PdfFormGraph graph, FieldBindingTarget.PageBox box, Map<Integer, List<PdfRect>> placesSoFar) {
        Optional<PdfFormGraph.Page> page = graph.pages().stream().filter(candidate -> candidate.pageNumber() == box.page()).findFirst();
        if (page.isEmpty()) {
            return UnsupportedBindingReason.OFF_PAGE;
        }
        PdfRect onPage = new PdfRect(0, 0, page.get().cropBox().width(), page.get().cropBox().height());
        PdfRect rect = box.box();
        if (rect.width() <= 0 || rect.height() <= 0 || !onPage.encloses(rect, PAGE_EDGE_TOLERANCE)) {
            return UnsupportedBindingReason.OFF_PAGE;
        }
        if (page.get().userUnit() != 1) {
            return UnsupportedBindingReason.NOT_FILLABLE;
        }
        if (rect.width() < MIN_BOX_WIDTH || rect.height() < MIN_BOX_HEIGHT) {
            return UnsupportedBindingReason.TOO_SMALL;
        }
        List<PdfRect> others = new ArrayList<>(placesSoFar.getOrDefault(box.page(), List.of()));
        graph.acroForm().fields().forEach(field -> field.widgets().stream()
                .filter(widget -> widget.pageNumber() == box.page())
                .forEach(widget -> others.add(widget.box())));
        placesSoFar.computeIfAbsent(box.page(), ignored -> new ArrayList<>()).add(rect);
        return others.stream().anyMatch(other -> covers(rect, other)) ? UnsupportedBindingReason.OVERLAPS : null;
    }

    /** Whether two places share more than {@link #MAX_OVERLAP_SHARE} of the smaller one's area. */
    static boolean covers(PdfRect first, PdfRect second) {
        double smaller = Math.min(first.area(), second.area());
        return smaller > 0 && first.overlapArea(second) > MAX_OVERLAP_SHARE * smaller;
    }
}
