package io.github.vihuynh72.brownie.core.revision;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A scoped proposal to change some of a document's fields, frozen against
 * the exact revision it was generated from ({@code baseRevisionId}) --
 * never the whole document, and never applied blindly against whatever
 * revision happens to be current by the time someone acts on it. See
 * {@link PatchComparator} for how a stale base is detected and {@link
 * RevisionService#acceptPatch} for how only the still-uncontested subset
 * of {@code proposedValues} is ever committed.
 */
public record PatchProposal(
        long id,
        long workspaceId,
        long documentId,
        long baseRevisionId,
        Map<String, FieldValue> proposedValues,
        Map<String, List<Long>> proposedEvidence,
        PatchProposalStatus status,
        OffsetDateTime createdAt) {

    public PatchProposal {
        if (proposedValues == null || proposedValues.isEmpty()) {
            throw new IllegalArgumentException("A patch proposal must propose at least one field.");
        }
        Map<String, FieldValue> copiedValues = new LinkedHashMap<>();
        for (Map.Entry<String, FieldValue> entry : proposedValues.entrySet()) {
            String fieldId = entry.getKey();
            if (fieldId == null || fieldId.isBlank()) {
                throw new IllegalArgumentException("Proposed field IDs must not be blank.");
            }
            copiedValues.put(fieldId, Objects.requireNonNull(entry.getValue(), "proposed field value"));
        }
        proposedValues = Map.copyOf(copiedValues);

        Map<String, List<Long>> copiedEvidence = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : Objects.requireNonNullElse(proposedEvidence, Map.<String, List<Long>>of()).entrySet()) {
            copiedEvidence.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        proposedEvidence = Map.copyOf(copiedEvidence);

        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
