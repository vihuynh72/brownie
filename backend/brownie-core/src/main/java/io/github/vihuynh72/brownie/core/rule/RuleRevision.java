package io.github.vihuynh72.brownie.core.rule;

import java.time.OffsetDateTime;

/**
 * One proposed rule against one template's own current draft version.
 * {@code category} is redundant with {@code payload}'s own {@link
 * RulePayload#category()} but stored directly so a caller can filter or
 * display by category without deserializing every payload -- the same
 * observed-vs-derived tradeoff {@code ExtractionVersion} already makes for
 * its own status. {@code schemaVersion} identifies the rule vocabulary
 * shape that validated this payload, the same role {@code parserVersion}
 * plays for an extraction.
 *
 * <p>No {@code proposalEvidence} or {@code approval} field yet -- nothing
 * in this codebase produces either: proposal evidence belongs to an
 * example-driven teaching flow that does not exist yet, and approval
 * belongs to the accept/reject decision this task does not implement (see
 * {@link RuleRevisionStatus}).
 */
public record RuleRevision(
        long id,
        long workspaceId,
        long templateId,
        long templateVersionId,
        RuleCategory category,
        RuleScope scope,
        RulePayload payload,
        String schemaVersion,
        RuleRevisionStatus status,
        String humanExplanation,
        long authorUserId,
        OffsetDateTime createdAt) {
}
