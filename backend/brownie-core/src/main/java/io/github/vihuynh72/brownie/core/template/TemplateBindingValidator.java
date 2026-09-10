package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    /** Every problem found, across every field -- empty means every binding in {@code fields} is valid and unambiguous. */
    public static List<UnsupportedBinding> validate(DocxStructuralGraph graph, List<FieldDefinition> fields) {
        List<UnsupportedBinding> problems = new ArrayList<>();
        Set<String> seenFieldIds = new HashSet<>();
        for (FieldDefinition field : fields) {
            if (!seenFieldIds.add(field.fieldId())) {
                problems.add(new UnsupportedBinding(field.fieldId(), UnsupportedBindingReason.DUPLICATE_FIELD_ID));
                continue;
            }
            int matchCount = countMatches(graph, field.binding());
            if (matchCount == 0) {
                problems.add(new UnsupportedBinding(field.fieldId(), UnsupportedBindingReason.NOT_FOUND));
            } else if (matchCount > 1) {
                problems.add(new UnsupportedBinding(field.fieldId(), UnsupportedBindingReason.AMBIGUOUS));
            }
        }
        return problems;
    }

    private static int countMatches(DocxStructuralGraph graph, FieldBindingTarget target) {
        return switch (target) {
            case FieldBindingTarget.ContentControlTag(String tag) -> graph.parts().stream()
                    .mapToInt(part -> countContentControlTag(part.root(), tag))
                    .sum();
            case FieldBindingTarget.StructuralNode(var part, String nodeId) -> graph.parts().stream()
                    .filter(p -> p.kind() == part)
                    .mapToInt(p -> countNodeId(p.root(), nodeId))
                    .sum();
        };
    }

    private static int countContentControlTag(StructuralNode node, String tag) {
        int count = node.kind() == StructuralNodeKind.CONTENT_CONTROL && tag.equals(node.contentControlTag()) ? 1 : 0;
        for (StructuralNode child : node.children()) {
            count += countContentControlTag(child, tag);
        }
        return count;
    }

    private static int countNodeId(StructuralNode node, String nodeId) {
        int count = nodeId.equals(node.nodeId()) ? 1 : 0;
        for (StructuralNode child : node.children()) {
            count += countNodeId(child, nodeId);
        }
        return count;
    }
}
