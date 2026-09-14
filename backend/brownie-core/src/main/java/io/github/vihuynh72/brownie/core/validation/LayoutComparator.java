package io.github.vihuynh72.brownie.core.validation;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Separates expected content insertions from prohibited layout changes by
 * comparing the template's own qualified baseline render (the synthetic
 * sample-filled DOCX proven at activation time, see {@code
 * TemplateBaselineRenderer}) against a real revision's own freshly filled
 * DOCX -- both are the same template, so a genuine structural difference
 * outside a field's own bound content is a real regression, not a
 * coincidence of two unrelated documents.
 *
 * <p>Comparison is by the ordered sequence of protected (non-field-bound)
 * leaf content -- every RUN's text and every IMAGE's own relationship ID,
 * visited in document order -- rather than by matching node IDs
 * position-for-position, so it survives a {@link FieldCardinality#REPEATED}
 * field's own row/paragraph count legitimately differing between the
 * sample baseline and a real revision. A {@code SCALAR} field's own bound
 * subtree is skipped (its content is expected to differ); a {@code
 * REPEATED} field's own bound tag additionally excludes its *whole
 * containing paragraph or table row* (whichever it is directly inside) on
 * each side independently -- not just the bound content control -- because
 * that repeat unit can carry static, non-field label content (a column
 * heading, a "Task:"/"Owner:" prefix) that legitimately appears once per
 * row or paragraph rather than exactly once in the document; comparing it
 * as ordinary fixed protected content would flag every row-count
 * difference as a false "protected content changed" finding. This is
 * exactly the mistake an early version of this class made against the
 * table-led built-in template's own table rows, and again -- in the
 * paragraph-based case specifically -- against the flowing built-in
 * template's own repeated action-item paragraphs, both caught only by a
 * real end-to-end test against the real fixtures, not by hand-built unit
 * graphs alone. Only a {@code ContentControlTag} binding is
 * handled specially this way -- a {@code REPEATED} field bound by {@code
 * StructuralNode} is not something {@code PoiTemplateFiller} can actually
 * fill today (a real, already-named limitation from the previous phase's
 * own adversarial sweep), so it cannot occur in practice yet. A cloned
 * row/paragraph's own content control tag is matched as either the bare
 * original tag or {@code "<tag>#<index>"} -- the exact rewrite {@code
 * PoiTemplateFiller.bindGroupControls} performs -- not just the bare tag;
 * an earlier version of this class matched only the bare tag and
 * therefore never actually excluded a single cloned row in practice, a
 * second real bug this task's own end-to-end test against a real
 * built-in template caught.
 *
 * <p>Even after repeated-row exclusion, the remaining two leaf sequences
 * are not required to be the same length: {@link #compareLeaves} aligns
 * them by longest common prefix and suffix rather than assuming equal
 * length outright, because an optional or repeated field's own empty-list
 * policy can legitimately insert or omit content the baseline's own
 * always-populated sample never triggers -- see that method's own javadoc
 * for the real, named limit of this approach (two independent
 * perturbations at once can merge into one ambiguous finding instead of
 * being separately detected).
 */
public final class LayoutComparator {

    private LayoutComparator() {
    }

    public static List<ValidationFinding> compareStructure(
            DocxStructuralGraph baselineGraph, DocxStructuralGraph filledGraph, List<FieldDefinition> fieldDefinitions) {
        Set<String> scalarBoundTags = new HashSet<>();
        Set<String> repeatedBoundTags = new HashSet<>();
        Set<PartNodeKey> boundNodes = new HashSet<>();
        for (FieldDefinition definition : fieldDefinitions) {
            switch (definition.binding()) {
                case FieldBindingTarget.ContentControlTag(String tag) -> {
                    if (definition.cardinality() == FieldCardinality.REPEATED) {
                        repeatedBoundTags.add(tag);
                    } else {
                        scalarBoundTags.add(tag);
                    }
                }
                case FieldBindingTarget.StructuralNode(var part, String nodeId) -> boundNodes.add(new PartNodeKey(part, nodeId));
            }
        }

        List<ProtectedLeaf> baselineLeaves = protectedLeaves(baselineGraph, scalarBoundTags, repeatedBoundTags, boundNodes);
        List<ProtectedLeaf> filledLeaves = protectedLeaves(filledGraph, scalarBoundTags, repeatedBoundTags, boundNodes);
        return compareLeaves(baselineLeaves, filledLeaves);
    }

    /**
     * Compares two leaf sequences by their longest common prefix and
     * longest common suffix rather than requiring equal length outright --
     * an optional repeated group's own empty-list policy can legitimately
     * insert a single explanatory line (or omit content) that the
     * baseline's own non-empty sample fill never triggers, a real,
     * permanent asymmetry between "a sample that always has content" and
     * "a real revision that may legitimately have none." Whatever sits
     * strictly before the common prefix and after the common suffix never
     * happened here (both sequences are identical there); what remains in
     * the middle is exactly the region that actually differs.
     *
     * <p>When the two middles are the same size, they are compared
     * position-for-position and any difference is a real, blocking
     * protected-content change -- the same length ruling out an
     * insertion/omission explanation. When the middles differ in size,
     * this check cannot safely align them further (which leaf corresponds
     * to which is genuinely ambiguous), so it names the region as an
     * expected insertion/removal rather than asserting a specific,
     * possibly-wrong defect -- §13.3's own "report findings it can
     * actually detect" permission, not a claim of full precision.
     */
    private static List<ValidationFinding> compareLeaves(List<ProtectedLeaf> baselineLeaves, List<ProtectedLeaf> filledLeaves) {
        int prefixLength = commonPrefixLength(baselineLeaves, filledLeaves);
        int suffixLength = commonSuffixLength(baselineLeaves, filledLeaves, prefixLength);
        int baselineMiddleSize = baselineLeaves.size() - prefixLength - suffixLength;
        int filledMiddleSize = filledLeaves.size() - prefixLength - suffixLength;

        if (baselineMiddleSize == 0 && filledMiddleSize == 0) {
            return List.of();
        }
        if (baselineMiddleSize != filledMiddleSize) {
            return List.of(new ValidationFinding(
                    ValidationFindingCode.LAYOUT_EXPECTED_INSERTION, null,
                    "Protected content outside a matched prefix/suffix changed count (baseline " + baselineMiddleSize
                            + ", filled " + filledMiddleSize + ") between the template's own qualified baseline and the filled document; "
                            + "likely an optional or repeated region's own content legitimately differing, not independently confirmable by this check."));
        }

        List<ValidationFinding> findings = new ArrayList<>();
        for (int offset = 0; offset < baselineMiddleSize; offset++) {
            ProtectedLeaf baseline = baselineLeaves.get(prefixLength + offset);
            ProtectedLeaf filled = filledLeaves.get(prefixLength + offset);
            if (!baseline.equals(filled)) {
                findings.add(new ValidationFinding(
                        ValidationFindingCode.LAYOUT_PROTECTED_REGION_CHANGE, null,
                        "Protected content in " + baseline.part() + " changed between the template's own qualified baseline"
                                + " (\"" + baseline.text() + "\") and the filled document (\"" + filled.text() + "\")."));
            }
        }
        return findings;
    }

    private static int commonPrefixLength(List<ProtectedLeaf> a, List<ProtectedLeaf> b) {
        int max = Math.min(a.size(), b.size());
        int length = 0;
        while (length < max && a.get(length).equals(b.get(length))) {
            length++;
        }
        return length;
    }

    private static int commonSuffixLength(List<ProtectedLeaf> a, List<ProtectedLeaf> b, int prefixLength) {
        int max = Math.min(a.size(), b.size()) - prefixLength;
        int length = 0;
        while (length < max
                && a.get(a.size() - 1 - length).equals(b.get(b.size() - 1 - length))) {
            length++;
        }
        return length;
    }

    private static List<ProtectedLeaf> protectedLeaves(
            DocxStructuralGraph graph, Set<String> scalarBoundTags, Set<String> repeatedBoundTags, Set<PartNodeKey> boundNodes) {
        List<ProtectedLeaf> leaves = new ArrayList<>();
        for (DocumentPart part : graph.parts()) {
            collect(part.kind(), part.root(), scalarBoundTags, repeatedBoundTags, boundNodes, leaves);
        }
        return leaves;
    }

    private static void collect(
            DocumentPartKind part,
            StructuralNode node,
            Set<String> scalarBoundTags,
            Set<String> repeatedBoundTags,
            Set<PartNodeKey> boundNodes,
            List<ProtectedLeaf> out) {
        if (node.kind() == StructuralNodeKind.CONTENT_CONTROL && scalarBoundTags.contains(node.contentControlTag())) {
            return;
        }
        if (boundNodes.contains(new PartNodeKey(part, node.nodeId()))) {
            return;
        }
        if (isRepeatUnit(node) && containsRepeatedTag(node, repeatedBoundTags)) {
            return;
        }
        if (node.kind() == StructuralNodeKind.RUN && node.text() != null && !node.text().isBlank()) {
            out.add(new ProtectedLeaf(part, node.text(), node.style()));
        }
        if (node.kind() == StructuralNodeKind.IMAGE) {
            out.add(new ProtectedLeaf(part, "image:" + node.imageRelationshipId(), null));
        }
        for (StructuralNode child : node.children()) {
            collect(part, child, scalarBoundTags, repeatedBoundTags, boundNodes, out);
        }
    }

    /** A paragraph is the flowing-layout repeat unit; a table row is the table-layout one -- see this class's own javadoc for why either can carry static, non-field content that legitimately repeats with the row/paragraph count. */
    private static boolean isRepeatUnit(StructuralNode node) {
        return node.kind() == StructuralNodeKind.PARAGRAPH || node.kind() == StructuralNodeKind.TABLE_ROW;
    }

    private static boolean containsRepeatedTag(StructuralNode node, Set<String> repeatedBoundTags) {
        if (node.kind() == StructuralNodeKind.CONTENT_CONTROL && matchesRepeatedTag(node.contentControlTag(), repeatedBoundTags)) {
            return true;
        }
        for (StructuralNode child : node.children()) {
            if (containsRepeatedTag(child, repeatedBoundTags)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code PoiTemplateFiller.bindGroupControls} rewrites each cloned
     * row/paragraph's own content control tag to {@code
     * "<originalTag>#<index>"} so multiple clones never collide -- the
     * unfilled prototype still carries the bare original tag (no clone has
     * happened yet, {@code itemCount == 0} short-circuits before any
     * rewrite), so both forms must match here. Confirmed directly against
     * that class's own real rewrite logic and a real sample-filled fixture
     * after this method's own first version, matching only the bare tag,
     * silently failed to exclude a single cloned row in practice.
     */
    private static boolean matchesRepeatedTag(String contentControlTag, Set<String> repeatedBoundTags) {
        if (contentControlTag == null) {
            return false;
        }
        for (String repeatedTag : repeatedBoundTags) {
            if (contentControlTag.equals(repeatedTag) || contentControlTag.startsWith(repeatedTag + "#")) {
                return true;
            }
        }
        return false;
    }

    private record PartNodeKey(DocumentPartKind part, String nodeId) {
    }

    private record ProtectedLeaf(DocumentPartKind part, String text, io.github.vihuynh72.brownie.core.document.ResolvedStyle style) {

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ProtectedLeaf that)) {
                return false;
            }
            return part == that.part && normalize(text).equals(normalize(that.text)) && Objects.equals(style, that.style);
        }

        @Override
        public int hashCode() {
            return Objects.hash(part, normalize(text), style);
        }

        private static String normalize(String value) {
            return value.replaceAll("\\s+", " ").trim();
        }
    }
}
