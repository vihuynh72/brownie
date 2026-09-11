package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a set of {@link FieldDefinition} bindings is actually
 * supported by one DOCX's extracted structure -- the "supported bindings"
 * half of this phase's job, kept separate from persistence so it can run
 * identically whether replacing draft bindings or re-checking them at
 * activation. A binding is supported only if it resolves to exactly one
 * node; zero or more than one is a real problem, not a warning, since
 * either leaves "which content is this field" undetermined.
 */
public final class TemplateBindingValidator {

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

    private static List<ResolvedLocation> resolve(DocxStructuralGraph graph, FieldBindingTarget target) {
        return switch (target) {
            case FieldBindingTarget.ContentControlTag(String tag) -> graph.parts().stream()
                    .flatMap(part -> collectContentControlTag(part.root(), tag).stream()
                            .map(nodeId -> new ResolvedLocation(part.kind(), nodeId)))
                    .toList();
            case FieldBindingTarget.StructuralNode(var part, String nodeId) -> graph.parts().stream()
                    .filter(p -> p.kind() == part)
                    .flatMap(p -> collectNodeId(p.root(), nodeId).stream().map(id -> new ResolvedLocation(p.kind(), id)))
                    .toList();
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
}
