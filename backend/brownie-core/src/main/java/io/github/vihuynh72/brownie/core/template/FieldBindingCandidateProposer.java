package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proposes candidate field bindings from a custom template's own extracted
 * structure, so a person is not left with only a blank binding form and the
 * raw structural graph to compare by eye -- the "candidate field bindings"
 * {@link TemplateBindingValidator} itself does not produce, since that
 * class only ever checks a binding it is handed, never suggests one.
 *
 * <p>Deliberately narrow, and honest about it: only a content control's own
 * tag is used, since {@link FieldBindingTarget.ContentControlTag} is this
 * product's one stable, tested binding convention (see the DOCX-binding
 * spike). A template using bare paragraph labels with no content controls
 * at all yields no candidates -- proposing a binding from label text alone
 * would be guessing the field's business meaning from its surrounding
 * words, exactly the kind of inference this plan's own product definition
 * warns against doing silently. That case is not a failure; a person still
 * recovers through an explicit {@link FieldBindingTarget.StructuralNode}
 * mapping, the same primary recovery path every other unsupported-inference
 * case in this codebase falls back to.
 *
 * <p>Cardinality is inferred from structural placement, not asserted:
 * a tag found inside a {@link StructuralNodeKind#TABLE_CELL} is proposed as
 * {@link FieldCardinality#REPEATED} (a single prototype row is exactly how
 * this product's own repeated regions are represented in a blank template --
 * see the compilation task's own "one shared prototype region" note), and
 * every other tag is proposed {@link FieldCardinality#SCALAR}. A table used
 * for pure layout rather than repetition will be mis-proposed by this
 * heuristic; a person reviewing the candidate can simply change it before
 * submitting, the same as any other candidate's type or field ID.
 *
 * <p>A tag found at more than one location is never proposed as a
 * candidate at all -- {@link TemplateBindingValidator} would reject it as
 * {@link UnsupportedBindingReason#AMBIGUOUS} the moment it was submitted,
 * so proposing it here would only manufacture a candidate guaranteed to
 * fail. It is reported back separately in {@link
 * CandidateBindingReport#ambiguousContentControlTags()} instead, so a
 * person knows why a tag they can see in the document was not proposed.
 */
public final class FieldBindingCandidateProposer {

    private FieldBindingCandidateProposer() {
    }

    public static CandidateBindingReport propose(DocxStructuralGraph graph) {
        List<Found> found = new ArrayList<>();
        for (DocumentPart part : graph.parts()) {
            walk(part.root(), false, found);
        }

        Map<String, List<Found>> byTag = new LinkedHashMap<>();
        for (Found candidate : found) {
            byTag.computeIfAbsent(candidate.tag(), key -> new ArrayList<>()).add(candidate);
        }

        List<CandidateFieldBinding> candidates = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        for (Map.Entry<String, List<Found>> entry : byTag.entrySet()) {
            if (entry.getValue().size() > 1) {
                ambiguous.add(entry.getKey());
                continue;
            }
            Found only = entry.getValue().get(0);
            candidates.add(new CandidateFieldBinding(
                    only.tag(), inferType(only.tag()), inferCardinality(only), new FieldBindingTarget.ContentControlTag(only.tag())));
        }
        return new CandidateBindingReport(candidates, ambiguous);
    }

    /**
     * Observable naming convention, not a content inspection -- the same
     * kind of surface-level signal a font family or a package relationship
     * already is elsewhere in this codebase, deliberately not an attempt to
     * read the field's actual meaning from surrounding label text.
     */
    private static FieldType inferType(String tag) {
        return tag.toLowerCase(Locale.ROOT).contains("date") ? FieldType.DATE : FieldType.TEXT;
    }

    private static FieldCardinality inferCardinality(Found found) {
        return found.insideTableCell() ? FieldCardinality.REPEATED : FieldCardinality.SCALAR;
    }

    private static void walk(StructuralNode node, boolean insideTableCell, List<Found> found) {
        if (node.kind() == StructuralNodeKind.CONTENT_CONTROL && node.contentControlTag() != null) {
            found.add(new Found(node.contentControlTag(), insideTableCell));
        }
        boolean childInsideTableCell = insideTableCell || node.kind() == StructuralNodeKind.TABLE_CELL;
        for (StructuralNode child : node.children()) {
            walk(child, childInsideTableCell, found);
        }
    }

    private record Found(String tag, boolean insideTableCell) {
    }
}
