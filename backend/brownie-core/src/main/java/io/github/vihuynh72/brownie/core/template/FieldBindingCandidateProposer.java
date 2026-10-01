package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
 * words, exactly the kind of inference the product must never do
 * silently. That case is not a failure; a person still
 * recovers through an explicit {@link FieldBindingTarget.StructuralNode}
 * mapping, the same primary recovery path every other unsupported-inference
 * case in this codebase falls back to.
 *
 * <p>Cardinality is inferred from structural placement, not asserted, and
 * only where the filler can actually repeat: a tag in the last row of the
 * main document's first table is proposed as {@link FieldCardinality#REPEATED}
 * when that row is the table's one row holding content controls and at least
 * one row (a header) sits above it -- a single prototype row under its
 * headings is exactly how this product's own repeated regions look in a
 * blank template. Every other tag is proposed {@link FieldCardinality#SCALAR},
 * including the boxes of a form laid out as a table of labels and answers,
 * which would otherwise be learned as a list the export repeats or leaves
 * saying that nothing was recorded. A person reviewing the candidates can
 * still change either before submitting.
 *
 * <p>A tag is proposed as {@link FieldType#DATE} when "date" is one of its
 * words ("meeting.date", "DueDate"), never merely inside another word
 * ("candidate.name", "updatedBy").
 *
 * <p>A tag found at more than one location is never proposed as a
 * candidate at all -- {@link TemplateBindingValidator} would reject it as
 * {@link UnsupportedBindingReason#AMBIGUOUS} the moment it was submitted,
 * so proposing it here would only manufacture a candidate guaranteed to
 * fail. It is reported back separately in {@link
 * CandidateBindingReport#ambiguousContentControlTags()} instead, so a
 * person knows why a tag they can see in the document was not proposed.
 *
 * <p>A content control with no tag, or a tag of nothing but whitespace, is
 * never a candidate either: there is nothing a binding could name it by.
 * Those in the main document are counted in {@link
 * CandidateBindingReport#untaggedContentControlCount()}, so a person knows
 * the form holds boxes that stay as they are and that a tag would let
 * Brownie fill. Those in a header or footer are not counted, because a tag
 * would not help there: values are written only in the body.
 */
public final class FieldBindingCandidateProposer {

    private FieldBindingCandidateProposer() {
    }

    public static CandidateBindingReport propose(DocxStructuralGraph graph) {
        List<Found> found = new ArrayList<>();
        int untaggedContentControlCount = 0;
        for (DocumentPart part : graph.parts()) {
            boolean mainDocument = part.kind() == DocumentPartKind.MAIN_DOCUMENT;
            String repeatingRowId = mainDocument ? repeatingRowIdOf(part.root()) : null;
            walk(part.root(), repeatingRowId, false, found);
            if (mainDocument) {
                untaggedContentControlCount += countUntagged(part.root());
            }
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
        return new CandidateBindingReport(candidates, ambiguous, untaggedContentControlCount);
    }

    /**
     * Observable naming convention, not a content inspection -- the same
     * kind of surface-level signal a font family or a package relationship
     * already is elsewhere in this codebase, deliberately not an attempt to
     * read the field's actual meaning from surrounding label text.
     */
    private static FieldType inferType(String tag) {
        for (String word : WORD_BREAK.split(tag)) {
            if (word.toLowerCase(Locale.ROOT).equals("date")) {
                return FieldType.DATE;
            }
        }
        return FieldType.TEXT;
    }

    /**
     * Breaks a tag into its words: at anything not a letter or digit, where a lower-case letter meets an
     * upper-case one ("dueDate"), between letters and digits ("Date1"), and before the last capital of a
     * run of capitals that starts a word ("DOBDate").
     */
    private static final Pattern WORD_BREAK = Pattern.compile(
            "[^\\p{L}\\p{N}]+|(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})");

    private static FieldCardinality inferCardinality(Found found) {
        return found.insideRepeatingRow() ? FieldCardinality.REPEATED : FieldCardinality.SCALAR;
    }

    /**
     * The row the filler would repeat: the last row of the body's first table, when it is the only
     * row of that table with a content control and has at least one row above it. Null otherwise.
     */
    private static String repeatingRowIdOf(StructuralNode body) {
        StructuralNode table = body.children().stream()
                .filter(child -> child.kind() == StructuralNodeKind.TABLE)
                .findFirst()
                .orElse(null);
        if (table == null) {
            return null;
        }
        List<StructuralNode> rows = table.children().stream().filter(child -> child.kind() == StructuralNodeKind.TABLE_ROW).toList();
        if (rows.size() < 2) {
            return null;
        }
        StructuralNode last = rows.getLast();
        if (!holdsContentControl(last)) {
            return null;
        }
        for (StructuralNode row : rows.subList(0, rows.size() - 1)) {
            if (holdsContentControl(row)) {
                return null;
            }
        }
        return last.nodeId();
    }

    private static boolean holdsContentControl(StructuralNode node) {
        if (isTaggedContentControl(node)) {
            return true;
        }
        for (StructuralNode child : node.children()) {
            if (holdsContentControl(child)) {
                return true;
            }
        }
        return false;
    }

    private static int countUntagged(StructuralNode node) {
        int count = node.kind() == StructuralNodeKind.CONTENT_CONTROL && !isTaggedContentControl(node) ? 1 : 0;
        for (StructuralNode child : node.children()) {
            count += countUntagged(child);
        }
        return count;
    }

    /** A blank tag names nothing a binding could point at, the same as no tag. */
    private static boolean isTaggedContentControl(StructuralNode node) {
        return node.kind() == StructuralNodeKind.CONTENT_CONTROL && node.contentControlTag() != null && !node.contentControlTag().isBlank();
    }

    private static void walk(StructuralNode node, String repeatingRowId, boolean insideRepeatingRow, List<Found> found) {
        if (isTaggedContentControl(node)) {
            found.add(new Found(node.contentControlTag(), insideRepeatingRow));
        }
        boolean childInsideRepeatingRow = insideRepeatingRow || (repeatingRowId != null && repeatingRowId.equals(node.nodeId()));
        for (StructuralNode child : node.children()) {
            walk(child, repeatingRowId, childInsideRepeatingRow, found);
        }
    }

    private record Found(String tag, boolean insideRepeatingRow) {
    }
}
