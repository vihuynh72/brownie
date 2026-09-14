package io.github.vihuynh72.brownie.core.example;

import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.TemplateBindingValidator;

import java.util.List;

/**
 * Decides whether one example's own extracted structure looks like the same
 * template family as the draft it was attached to, kept pure and separate
 * from persistence the same way {@link TemplateBindingValidator} is.
 *
 * <p>Only {@code ContentControlTag}-bound fields are checked. A {@code
 * StructuralNode} binding is a sibling-index path scoped to one exact
 * document's own extraction (see {@code FieldBindingTarget}'s own javadoc);
 * the same path in a genuinely different document's tree names an
 * unrelated node, or nothing at all, by pure coincidence of position, not
 * because the two documents are or are not the same template. A tag,
 * unlike a path, is a real identifier Word preserves whenever a template
 * file is copied and filled in, so it is the one binding kind this
 * comparison can honestly draw a conclusion from.
 */
public final class ExampleAligner {

    private ExampleAligner() {
    }

    /**
     * @throws NoComparableFieldBindingsException if {@code fieldDefinitions}
     *     has no {@code ContentControlTag}-bound field at all -- there is
     *     nothing this method can honestly check, so it refuses to guess
     *     rather than return a meaningless {@code ALIGNED}.
     */
    public static ExampleAlignmentStatus align(long templateId, List<FieldDefinition> fieldDefinitions, DocxStructuralGraph exampleGraph) {
        List<FieldBindingTarget.ContentControlTag> comparableTags = fieldDefinitions.stream()
                .map(FieldDefinition::binding)
                .filter(FieldBindingTarget.ContentControlTag.class::isInstance)
                .map(FieldBindingTarget.ContentControlTag.class::cast)
                .toList();
        if (comparableTags.isEmpty()) {
            throw new NoComparableFieldBindingsException(templateId);
        }
        for (FieldBindingTarget.ContentControlTag tag : comparableTags) {
            if (TemplateBindingValidator.matchCount(exampleGraph, tag) == 0) {
                return ExampleAlignmentStatus.MISMATCHED_FAMILY;
            }
        }
        return ExampleAlignmentStatus.ALIGNED;
    }
}
