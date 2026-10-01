package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.rule.RuleRevision;

import java.util.List;
import java.util.Objects;

/**
 * Everything a new template version made from {@code baseVersionId} needs,
 * worked out and proven before anything is written: its file and pinned
 * reading (a Word version's extraction or a PDF version's form reading,
 * whichever its {@code kind} has), its field list, the base's rules rewritten for it (each
 * keeping its status), and the sample render it was proven with. Writing it
 * is one short transaction ({@link TemplateLineageRepository#insertDerived}).
 *
 * <p>{@code derivationKey} identifies the request, not the result, so the
 * same request made again finds this version once it exists. {@code
 * derivationJson} is the change list as stored with the version, {@code
 * changedFieldIds} the IDs of the spots each change named, in request
 * order, {@code changeKinds} one kind per change (ADD, RENAME, REMOVE,
 * ADD_BOX, MOVE_BOX, RESTYLE_BOX) for the audit record, and {@code
 * editReason} what the document's history says about it.
 */
public record PreparedDerivation(
        long templateId,
        long baseVersionId,
        String derivationKey,
        String derivationJson,
        long sourceArtifactId,
        TemplateKind kind,
        Long extractionVersionId,
        Long pdfFormExtractionId,
        List<FieldDefinition> fieldDefinitions,
        List<RuleRevision> rules,
        BaselineRenderResult baseline,
        List<String> changedFieldIds,
        List<String> changeKinds,
        String editReason) {

    public PreparedDerivation {
        Objects.requireNonNull(derivationKey, "derivationKey");
        Objects.requireNonNull(derivationJson, "derivationJson");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(editReason, "editReason");
        Objects.requireNonNull(kind, "kind");
        if (kind == TemplateKind.DOCX ? extractionVersionId == null || pdfFormExtractionId != null
                : pdfFormExtractionId == null || extractionVersionId != null) {
            throw new IllegalArgumentException("A " + kind + " version is pinned to the reading of its own kind only.");
        }
        fieldDefinitions = List.copyOf(fieldDefinitions);
        rules = List.copyOf(rules);
        changedFieldIds = List.copyOf(changedFieldIds);
        changeKinds = List.copyOf(changeKinds);
    }
}
