package io.github.vihuynh72.brownie.core.revision;

import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateTrashedException;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Applies bounded typed field edits and delegates the current-pointer
 * compare-and-swap to the document repository.
 */
public class RevisionService {

    private final DocumentRepository documentRepository;
    private final TemplateRepository templateRepository;
    private final PatchProposalRepository patchProposalRepository;

    public RevisionService(
            DocumentRepository documentRepository, TemplateRepository templateRepository, PatchProposalRepository patchProposalRepository) {
        this.documentRepository = documentRepository;
        this.templateRepository = templateRepository;
        this.patchProposalRepository = patchProposalRepository;
    }

    public DocumentMutationResult createDocument(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            String title,
            long templateId,
            long templateVersionId,
            DocumentContent initialContent,
            Map<String, List<Long>> initialEvidence,
            String initialRevisionReason) {
        Optional<DocumentMutationResult> existing = documentRepository.findMutationResult(
                workspaceId,
                userId,
                DocumentCommandType.CREATE,
                idempotencyKey,
                requestHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        TemplateVersion templateVersion = requireActiveTemplateVersion(workspaceId, userId, templateId, templateVersionId);
        // Only starting a document is refused: a document already made from a trashed template keeps reading and
        // editing against its version, which is why every other caller of the check above does not look at this.
        if (templateRepository.find(workspaceId, userId, templateId).map(template -> template.trashedAt() != null).orElse(false)) {
            throw new TemplateTrashedException();
        }
        DocumentContentValidator.validate(initialContent, templateVersion.fieldDefinitions());
        DocumentContentValidator.validateEvidence(initialContent, initialEvidence);
        Map<FieldItemRef, FieldState> initialFieldStates = computeFieldStates(
                Map.of(), initialContent, initialContent.fields().keySet(), initialEvidence, Authorship.USER_AUTHORED);
        return documentRepository.createIdempotently(
                workspaceId,
                userId,
                idempotencyKey,
                requestHash,
                title,
                templateId,
                templateVersionId,
                initialContent,
                initialEvidence,
                initialFieldStates,
                initialRevisionReason);
    }

    public Optional<Document> findDocument(long workspaceId, long userId, long documentId) {
        return documentRepository.find(workspaceId, userId, documentId);
    }

    public List<Document> findAllDocuments(long workspaceId, long userId) {
        return documentRepository.findAllForWorkspace(workspaceId, userId);
    }

    public Optional<DocumentRevision> findRevision(long workspaceId, long userId, long documentId, long revisionId) {
        return documentRepository.findRevision(workspaceId, userId, documentId, revisionId);
    }

    public List<DocumentRevision> findHistory(long workspaceId, long userId, long documentId) {
        return documentRepository.findHistory(workspaceId, userId, documentId);
    }

    public DocumentMutationResult applyUserEdits(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            List<DocumentFieldEdit> edits,
            Map<String, List<Long>> editEvidence,
            String editReason) {
        return applyTypedEdits(
                workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId, edits, editEvidence,
                Authorship.USER_AUTHORED, editReason);
    }

    /**
     * Compares a proposal's own base revision, the document's real current
     * revision, and the proposal's proposed values -- a field the current
     * revision still agrees with base is {@link PatchFieldStatus#CLEAN};
     * one current has changed since base is {@link PatchFieldStatus#CONFLICT};
     * one current has locked is {@link PatchFieldStatus#LOCKED} regardless
     * of whether it also conflicts. A field the document's current template
     * version no longer has (its fill spot was taken away after the proposal
     * was made) is {@link PatchFieldStatus#CONFLICT} too: there is nowhere
     * for its value to go. Read-only: never applies anything.
     */
    public PatchComparison compare(long workspaceId, long userId, long documentId, PatchProposal proposal) {
        Document document = documentRepository.find(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        DocumentRevision base = documentRepository.findRevision(workspaceId, userId, documentId, proposal.baseRevisionId())
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        DocumentRevision current = requireCurrentRevision(workspaceId, userId, documentId);
        PatchComparison comparison = PatchComparator.compare(base, current, proposal.proposedValues());
        Set<String> defined = definedFieldIds(
                requireActiveTemplateVersion(workspaceId, userId, document.templateId(), current.templateVersionId()));
        if (defined.containsAll(comparison.fieldStatuses().keySet())) {
            return comparison;
        }
        Map<String, PatchFieldStatus> statuses = new LinkedHashMap<>();
        comparison.fieldStatuses().forEach((fieldId, status) ->
                statuses.put(fieldId, defined.contains(fieldId) ? status : PatchFieldStatus.CONFLICT));
        return new PatchComparison(statuses);
    }

    /**
     * Records a scoped proposal against the exact revision it was
     * generated from. Validates each proposed field against the template
     * (existence, type, cardinality) the same way a direct edit already
     * is, but as a partial set -- a proposal is never required to touch
     * every field. Nothing is applied to the document yet; see {@link
     * #acceptPatch}.
     */
    public PatchProposal proposePatch(
            long workspaceId,
            long userId,
            long documentId,
            long baseRevisionId,
            Map<String, FieldValue> proposedValues,
            Map<String, List<Long>> proposedEvidence) {
        Document document = documentRepository.find(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        documentRepository.findRevision(workspaceId, userId, documentId, baseRevisionId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        TemplateVersion templateVersion = requireActiveTemplateVersion(
                workspaceId, userId, document.templateId(), document.templateVersionId());
        DocumentContentValidator.validatePartial(proposedValues, templateVersion.fieldDefinitions());
        DocumentContentValidator.validatePartialEvidence(proposedValues.keySet(), proposedEvidence);
        return patchProposalRepository.create(workspaceId, userId, documentId, baseRevisionId, proposedValues, proposedEvidence);
    }

    /**
     * Applies only the still-{@link PatchFieldStatus#CLEAN} subset of a
     * proposal against the document's real current revision -- a field
     * that conflicts or is locked is left exactly as it is, reported
     * rather than silently skipped. Nothing is appended, and the proposal
     * is not marked accepted, if every field is blocked. An applied
     * field's evidence is exactly what the proposal declared for it, and
     * it is recorded {@link Authorship#AI_COMPOSED}, the same "changed
     * wording must be rechecked, never inherit stale support" rule {@link
     * #mergeEvidenceAfterEdits} already enforces for a direct edit.
     */
    public PatchAcceptanceResult acceptPatch(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long proposalId,
            long expectedRevisionId,
            String editReason) {
        // A proposal is found by its own id, so the document is checked
        // first: one that is in the trash has no proposals to accept, and
        // must say so as "not found" rather than fail halfway through the
        // comparison below.
        documentRepository.find(workspaceId, userId, documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        PatchProposal proposal = patchProposalRepository.find(workspaceId, userId, documentId, proposalId)
                .orElseThrow(() -> new PatchProposalNotFoundException(proposalId));
        if (proposal.status() != PatchProposalStatus.PROPOSED) {
            // Checked explicitly, not left to the comparison alone: accepting once moves current away from
            // base for every applied field, which the comparison would naturally also read as a conflict on
            // a second attempt -- but a field a coincidental later edit reverts back to its base value would
            // otherwise look CLEAN again and silently re-apply, so an already-resolved proposal is refused
            // outright rather than relying on that as the only guard.
            throw new PatchProposalNotFoundException(proposalId);
        }
        PatchComparison comparison = compare(workspaceId, userId, documentId, proposal);
        Set<String> applicableFieldIds = comparison.applicableFields();
        if (applicableFieldIds.isEmpty()) {
            return new PatchAcceptanceResult(comparison, Optional.empty());
        }

        List<DocumentFieldEdit> edits = applicableFieldIds.stream()
                .<DocumentFieldEdit>map(fieldId -> new DocumentFieldEdit.SetValue(fieldId, proposal.proposedValues().get(fieldId)))
                .toList();
        Map<String, List<Long>> editEvidence = new LinkedHashMap<>();
        for (String fieldId : applicableFieldIds) {
            List<Long> spanIds = proposal.proposedEvidence().get(fieldId);
            if (spanIds != null && !spanIds.isEmpty()) {
                editEvidence.put(fieldId, spanIds);
            }
        }

        DocumentMutationResult mutation = applyTypedEdits(
                workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId, edits, editEvidence,
                Authorship.AI_COMPOSED, editReason);
        patchProposalRepository.markAccepted(workspaceId, userId, documentId, proposalId);
        return new PatchAcceptanceResult(comparison, Optional.of(mutation));
    }

    /**
     * Records a person's own {@link ReviewState} decision for one field or
     * item, leaving its value, evidence, authorship, validation, and lock
     * exactly as they were -- a review decision is independent of the
     * other four dimensions (see {@link FieldState}), never a side effect
     * of anything else. Like a direct edit, this is a real, idempotent
     * mutation that appends a new revision, since {@code
     * document_revision} is this codebase's only durable place any
     * per-field dimension lives.
     */
    public DocumentMutationResult recordReviewDecision(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            FieldItemRef ref,
            ReviewState decision,
            String editReason) {
        return applyFieldStateChange(
                workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId, ref,
                state -> new FieldState(state.authorship(), state.evidenceSupport(), state.validation(), decision, state.lock()),
                editReason);
    }

    /**
     * Records a whole validation run's own per-field {@link
     * ValidationState} results in one new revision -- unlike {@link
     * #recordReviewDecision}/{@link #setFieldLock}, which each change one
     * {@link FieldItemRef} at a time, a validation run always evaluates
     * every field together, so it must not fragment into one appended
     * revision per field. A ref this run did not evaluate keeps whatever
     * validation state it already had, the same carry-forward discipline
     * {@link #computeFieldStates} already applies to authorship/evidence
     * for an untouched field.
     */
    public DocumentMutationResult applyValidationResults(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            Map<FieldItemRef, ValidationState> results,
            String editReason) {
        Optional<DocumentMutationResult> existing = documentRepository.findMutationResult(
                workspaceId, userId, DocumentCommandType.EDIT_CONTENT, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        documentRepository.find(workspaceId, userId, documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        DocumentRevision current = requireCurrentRevision(workspaceId, userId, documentId);
        if (current.id() != expectedRevisionId) {
            throw new DocumentRevisionConflictException(documentId, expectedRevisionId, current.id());
        }
        Map<FieldItemRef, FieldState> nextFieldStates = new LinkedHashMap<>(current.fieldStates());
        for (Map.Entry<FieldItemRef, ValidationState> entry : results.entrySet()) {
            FieldItemRef ref = entry.getKey();
            FieldState previous = nextFieldStates.get(ref);
            if (previous == null) {
                throw new IllegalArgumentException("Document " + documentId + " revision " + current.id() + " has no state for " + ref + ".");
            }
            nextFieldStates.put(ref, new FieldState(
                    previous.authorship(), previous.evidenceSupport(), entry.getValue(), previous.review(), previous.lock()));
        }
        return documentRepository.appendRevisionIdempotently(
                workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId, null,
                current.content(), current.evidence(), nextFieldStates, editReason);
    }

    /**
     * Explicitly sets one field or item's {@link LockState}, the only
     * route that ever changes it deliberately -- a direct edit through
     * {@link #applyUserEdits} always preserves whatever lock a field
     * already had (see {@link #computeFieldStates}), precisely so this is
     * the one place a lock actually changes.
     */
    public DocumentMutationResult setFieldLock(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            FieldItemRef ref,
            LockState lock,
            String editReason) {
        return applyFieldStateChange(
                workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId, ref,
                state -> new FieldState(state.authorship(), state.evidenceSupport(), state.validation(), state.review(), lock),
                editReason);
    }

    /**
     * Appends a new revision whose content, evidence, and field states
     * are an exact copy of an earlier one -- undo never rewrites or
     * removes history (every revision, including the one being undone
     * away from, stays immutable and readable via {@link #findHistory}),
     * it only adds a new revision that happens to match an old one.
     */
    public DocumentMutationResult undoToRevision(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            long targetRevisionId,
            String editReason) {
        Optional<DocumentMutationResult> existing = documentRepository.findMutationResult(
                workspaceId, userId, DocumentCommandType.EDIT_CONTENT, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        documentRepository.find(workspaceId, userId, documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        DocumentRevision current = requireCurrentRevision(workspaceId, userId, documentId);
        if (current.id() != expectedRevisionId) {
            throw new DocumentRevisionConflictException(documentId, expectedRevisionId, current.id());
        }
        DocumentRevision target = documentRepository.findRevision(workspaceId, userId, documentId, targetRevisionId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return documentRepository.appendRevisionIdempotently(
                workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId, target.templateVersionId(),
                target.content(), target.evidence(), target.fieldStates(), editReason);
    }

    /**
     * Appends a new revision whose content is an earlier revision's, except
     * for what a person has locked since: a field whose value is {@link
     * LockState#EXPLICITLY_LOCKED} now keeps its current value, evidence and
     * state, and is named in the result. Every other field takes the earlier
     * revision's value, evidence and state, and a field the earlier revision
     * did not have is left without one. Repeated fields are one group whose
     * lists line up item by item, so a lock on any item of any of them keeps
     * the whole group as it is now rather than mixing current and earlier
     * rows. History is never rewritten: both revisions stay as they were.
     *
     * <p>The new revision is on the earlier revision's template version, so
     * restoring across a fill spot being added or taken away moves the
     * document back to the version it had then. A field that version does
     * not define has nowhere to go and is dropped, locked or not, and is
     * named in the result as dropped rather than kept.
     *
     * <p>{@code editReason} null or blank records "Restored version N.". A
     * replay of the same request recomputes the kept and dropped fields from
     * the same immutable revisions and version, so it answers exactly as the
     * first request did.
     */
    public RevisionRestoreResult restoreRevision(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            long targetRevisionId,
            String editReason) {
        Optional<DocumentMutationResult> existing = documentRepository.findMutationResult(
                workspaceId, userId, DocumentCommandType.EDIT_CONTENT, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            DocumentRevision restored = existing.get().revision();
            long replacedRevisionId = restored.parentRevisionId() == null ? expectedRevisionId : restored.parentRevisionId();
            DocumentRevision replaced = documentRepository.findRevision(workspaceId, userId, documentId, replacedRevisionId)
                    .orElseThrow(() -> new DocumentNotFoundException(documentId));
            DocumentRevision target = documentRepository.findRevision(workspaceId, userId, documentId, targetRevisionId)
                    .orElseThrow(() -> new DocumentNotFoundException(documentId));
            TemplateVersion targetVersion = requireActiveTemplateVersion(
                    workspaceId, userId, existing.get().document().templateId(), target.templateVersionId());
            RestorePlan plan = RestorePlan.between(replaced, target, definedFieldIds(targetVersion));
            return new RevisionRestoreResult(existing.get(), plan.keptLockedFieldIds(), plan.droppedFieldIds());
        }
        Document document = documentRepository.find(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        DocumentRevision current = requireCurrentRevision(workspaceId, userId, documentId);
        if (current.id() != expectedRevisionId) {
            throw new DocumentRevisionConflictException(documentId, expectedRevisionId, current.id());
        }
        DocumentRevision target = documentRepository.findRevision(workspaceId, userId, documentId, targetRevisionId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        TemplateVersion targetVersion = requireActiveTemplateVersion(workspaceId, userId, document.templateId(), target.templateVersionId());
        RestorePlan plan = RestorePlan.between(current, target, definedFieldIds(targetVersion));
        String reason = editReason == null || editReason.isBlank() ? "Restored version " + target.revisionNumber() + "." : editReason;
        DocumentMutationResult mutation = documentRepository.appendRevisionIdempotently(
                workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId,
                target.templateVersionId() == current.templateVersionId() ? null : target.templateVersionId(),
                plan.content(), plan.evidence(), plan.fieldStates(), reason);
        return new RevisionRestoreResult(mutation, plan.keptLockedFieldIds(), plan.droppedFieldIds());
    }

    /**
     * Moves the document to another activated version of its own template,
     * keeping everything it holds that the other version also has. A value
     * is carried by its field ID, with its evidence and its state (lock
     * included), because an ID always names the same field in every version
     * of a template; a field the other version does not define is dropped
     * and named in the result. A locked value is never dropped quietly: the
     * move is refused with {@link FillSpotLockedException} naming it, and
     * nothing is written.
     *
     * <p>Refused with {@link DocumentRevisionConflictException} unless
     * {@code expectedRevisionId} is still current, and with {@link
     * DocumentAlreadyOnTemplateVersionException} when the document is on
     * that version already. A replay of the same request answers with the
     * same revision and the same dropped fields.
     */
    public TemplateVersionMove moveToTemplateVersion(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            long targetTemplateVersionId,
            String editReason) {
        Optional<DocumentMutationResult> existing = documentRepository.findMutationResult(
                workspaceId, userId, DocumentCommandType.EDIT_CONTENT, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            DocumentRevision moved = existing.get().revision();
            long replacedRevisionId = moved.parentRevisionId() == null ? expectedRevisionId : moved.parentRevisionId();
            DocumentRevision replaced = documentRepository.findRevision(workspaceId, userId, documentId, replacedRevisionId)
                    .orElseThrow(() -> new DocumentNotFoundException(documentId));
            TemplateVersion target = requireActiveTemplateVersion(
                    workspaceId, userId, existing.get().document().templateId(), moved.templateVersionId());
            return new TemplateVersionMove(existing.get(), replaced.templateVersionId(), VersionCarry.of(replaced, target).droppedFieldIds());
        }
        Document document = documentRepository.find(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        DocumentRevision current = requireCurrentRevision(workspaceId, userId, documentId);
        if (current.id() != expectedRevisionId) {
            throw new DocumentRevisionConflictException(documentId, expectedRevisionId, current.id());
        }
        TemplateVersion target = requireActiveTemplateVersion(workspaceId, userId, document.templateId(), targetTemplateVersionId);
        if (current.templateVersionId() == target.id()) {
            throw new DocumentAlreadyOnTemplateVersionException(documentId, target.id());
        }
        VersionCarry carry = VersionCarry.of(current, target);
        for (String fieldId : carry.droppedFieldIds()) {
            if (RestorePlan.isExplicitlyLocked(current, fieldId)) {
                throw new FillSpotLockedException(fieldId);
            }
        }
        DocumentContentValidator.validate(carry.content(), target.fieldDefinitions());
        DocumentContentValidator.validateEvidence(carry.content(), carry.evidence());
        DocumentMutationResult mutation = documentRepository.appendRevisionIdempotently(
                workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId, target.id(),
                carry.content(), carry.evidence(), carry.fieldStates(), editReason);
        return new TemplateVersionMove(mutation, current.templateVersionId(), carry.droppedFieldIds());
    }

    /** Every field ID a version defines. */
    private static Set<String> definedFieldIds(TemplateVersion version) {
        Set<String> ids = new TreeSet<>();
        version.fieldDefinitions().forEach(field -> ids.add(field.fieldId()));
        return ids;
    }

    /**
     * What a revision's content becomes on another version: every value
     * whose field the version defines with the same type and cardinality,
     * with its evidence and states; every other field dropped, sorted by ID.
     */
    private record VersionCarry(
            DocumentContent content,
            Map<String, List<Long>> evidence,
            Map<FieldItemRef, FieldState> fieldStates,
            List<String> droppedFieldIds) {

        static VersionCarry of(DocumentRevision revision, TemplateVersion target) {
            Map<String, FieldDefinition> byId = new LinkedHashMap<>();
            target.fieldDefinitions().forEach(field -> byId.putIfAbsent(field.fieldId(), field));
            Map<String, FieldValue> content = new LinkedHashMap<>();
            Map<String, List<Long>> evidence = new LinkedHashMap<>();
            Map<FieldItemRef, FieldState> fieldStates = new LinkedHashMap<>();
            Set<String> dropped = new TreeSet<>();
            revision.content().fields().forEach((fieldId, value) -> {
                FieldDefinition field = byId.get(fieldId);
                if (field == null || !fits(field, value)) {
                    dropped.add(fieldId);
                    return;
                }
                RestorePlan.copyField(revision, fieldId, content, evidence, fieldStates);
            });
            return new VersionCarry(new DocumentContent(content), evidence, fieldStates, List.copyOf(dropped));
        }

        private static boolean fits(FieldDefinition field, FieldValue value) {
            return switch (value) {
                case FieldValue.TextValue ignored -> field.type() == FieldType.TEXT && field.cardinality() == FieldCardinality.SCALAR;
                case FieldValue.DateValue ignored -> field.type() == FieldType.DATE && field.cardinality() == FieldCardinality.SCALAR;
                case FieldValue.RepeatedTextValue ignored -> field.type() == FieldType.TEXT && field.cardinality() == FieldCardinality.REPEATED;
                case FieldValue.RepeatedDateValue ignored -> field.type() == FieldType.DATE && field.cardinality() == FieldCardinality.REPEATED;
            };
        }
    }

    /** The content, evidence and states a restore appends, worked out from the current revision and the one being restored. */
    private record RestorePlan(
            DocumentContent content,
            Map<String, List<Long>> evidence,
            Map<FieldItemRef, FieldState> fieldStates,
            List<String> keptLockedFieldIds,
            List<String> droppedFieldIds) {

        /** {@code definedFieldIds} are the fields of the restored revision's version: only those can hold a value afterwards. */
        static RestorePlan between(DocumentRevision current, DocumentRevision target, Set<String> definedFieldIds) {
            Set<String> fieldIds = new TreeSet<>(current.content().fields().keySet());
            fieldIds.addAll(target.content().fields().keySet());
            boolean repeatedGroupLocked = fieldIds.stream()
                    .filter(definedFieldIds::contains)
                    .anyMatch(fieldId -> isRepeated(current, fieldId) && isExplicitlyLocked(current, fieldId));

            Map<String, FieldValue> content = new LinkedHashMap<>();
            Map<String, List<Long>> evidence = new LinkedHashMap<>();
            Map<FieldItemRef, FieldState> fieldStates = new LinkedHashMap<>();
            List<String> kept = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (String fieldId : fieldIds) {
                if (!definedFieldIds.contains(fieldId)) {
                    if (current.content().fields().containsKey(fieldId)) {
                        dropped.add(fieldId);
                    }
                    continue;
                }
                boolean repeated = isRepeated(current, fieldId) || isRepeated(target, fieldId);
                boolean keep = repeated ? repeatedGroupLocked : isExplicitlyLocked(current, fieldId);
                if (keep) {
                    kept.add(fieldId);
                }
                copyField(keep ? current : target, fieldId, content, evidence, fieldStates);
            }
            return new RestorePlan(new DocumentContent(content), evidence, fieldStates, List.copyOf(kept), List.copyOf(dropped));
        }

        private static boolean isRepeated(DocumentRevision revision, String fieldId) {
            FieldValue value = revision.content().fields().get(fieldId);
            return value instanceof FieldValue.RepeatedTextValue || value instanceof FieldValue.RepeatedDateValue;
        }

        /** The same test a direct edit is refused by: the field, or any item of it, is explicitly locked. */
        static boolean isExplicitlyLocked(DocumentRevision revision, String fieldId) {
            FieldValue value = revision.content().fields().get(fieldId);
            if (value == null) {
                return false;
            }
            return FieldItemRef.allFor(fieldId, value).stream().anyMatch(ref -> {
                FieldState state = revision.fieldStates().get(ref);
                return state != null && state.lock() == LockState.EXPLICITLY_LOCKED;
            });
        }

        /**
         * A revision written before field states existed may lack one for an
         * item; that item gets the same state any field this codebase has
         * never recorded a state for gets.
         */
        static void copyField(
                DocumentRevision source,
                String fieldId,
                Map<String, FieldValue> content,
                Map<String, List<Long>> evidence,
                Map<FieldItemRef, FieldState> fieldStates) {
            FieldValue value = source.content().fields().get(fieldId);
            if (value == null) {
                return;
            }
            content.put(fieldId, value);
            List<Long> spanIds = source.evidence().get(fieldId);
            boolean hasEvidence = spanIds != null && !spanIds.isEmpty();
            if (hasEvidence) {
                evidence.put(fieldId, spanIds);
            }
            for (FieldItemRef ref : FieldItemRef.allFor(fieldId, value)) {
                FieldState state = source.fieldStates().get(ref);
                fieldStates.put(ref, state != null ? state : FieldState.freshlyUserAuthored(hasEvidence));
            }
        }
    }

    private DocumentMutationResult applyFieldStateChange(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            FieldItemRef ref,
            java.util.function.UnaryOperator<FieldState> transform,
            String editReason) {
        Optional<DocumentMutationResult> existing = documentRepository.findMutationResult(
                workspaceId, userId, DocumentCommandType.EDIT_CONTENT, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        documentRepository.find(workspaceId, userId, documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        DocumentRevision current = requireCurrentRevision(workspaceId, userId, documentId);
        if (current.id() != expectedRevisionId) {
            throw new DocumentRevisionConflictException(documentId, expectedRevisionId, current.id());
        }
        FieldState previous = current.fieldStates().get(ref);
        if (previous == null) {
            throw new IllegalArgumentException("Document " + documentId + " revision " + current.id() + " has no state for " + ref + ".");
        }
        Map<FieldItemRef, FieldState> nextFieldStates = new LinkedHashMap<>(current.fieldStates());
        nextFieldStates.put(ref, transform.apply(previous));
        return documentRepository.appendRevisionIdempotently(
                workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId, null,
                current.content(), current.evidence(), nextFieldStates, editReason);
    }

    /** Every field a direct edit touches must not currently be {@link LockState#EXPLICITLY_LOCKED} -- checked against the whole batch before anything is applied, the same "reject before appending anything" discipline {@link DocumentContentValidator} already applies to type/cardinality problems. */
    private static void requireNoExplicitlyLockedFieldIsTouched(DocumentRevision current, Set<String> touchedFieldIds) {
        for (String fieldId : touchedFieldIds) {
            FieldValue existingValue = current.content().fields().get(fieldId);
            if (existingValue == null) {
                continue;
            }
            for (FieldItemRef ref : FieldItemRef.allFor(fieldId, existingValue)) {
                FieldState state = current.fieldStates().get(ref);
                if (state != null && state.lock() == LockState.EXPLICITLY_LOCKED) {
                    throw new FieldLockedException(fieldId);
                }
            }
        }
    }

    private DocumentMutationResult applyTypedEdits(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            List<DocumentFieldEdit> edits,
            Map<String, List<Long>> editEvidence,
            Authorship authorshipForTouched,
            String editReason) {
        Optional<DocumentMutationResult> existing = documentRepository.findMutationResult(
                workspaceId,
                userId,
                DocumentCommandType.EDIT_CONTENT,
                idempotencyKey,
                requestHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        Document document = documentRepository.find(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        DocumentRevision current = requireCurrentRevision(workspaceId, userId, documentId);
        if (current.id() != expectedRevisionId) {
            throw new DocumentRevisionConflictException(documentId, expectedRevisionId, current.id());
        }
        Set<String> touchedFieldIds = edits.stream().map(DocumentFieldEdit::fieldId).collect(Collectors.toSet());
        requireNoExplicitlyLockedFieldIsTouched(current, touchedFieldIds);
        TemplateVersion templateVersion = requireActiveTemplateVersion(
                workspaceId, userId, document.templateId(), document.templateVersionId());
        DocumentContent nextContent = DocumentContentValidator.applyEdits(
                current.content(), edits, templateVersion.fieldDefinitions());
        Map<String, List<Long>> nextEvidence = mergeEvidenceAfterEdits(current.evidence(), edits, editEvidence);
        DocumentContentValidator.validateEvidence(nextContent, nextEvidence);
        Map<FieldItemRef, FieldState> nextFieldStates =
                computeFieldStates(current.fieldStates(), nextContent, touchedFieldIds, nextEvidence, authorshipForTouched);
        return documentRepository.appendRevisionIdempotently(
                workspaceId,
                userId,
                idempotencyKey,
                requestHash,
                documentId,
                expectedRevisionId,
                null,
                nextContent,
                nextEvidence,
                nextFieldStates,
                editReason);
    }

    /**
     * A field whose value did not change in this edit keeps whatever
     * evidence its previous revision recorded. A field this edit touches
     * -- set to a new value, or cleared -- starts from nothing: changed
     * wording must be rechecked rather than silently inheriting an old
     * citation, so the caller must explicitly re-assert
     * evidence for a field it is setting.
     */
    private static Map<String, List<Long>> mergeEvidenceAfterEdits(
            Map<String, List<Long>> previousEvidence,
            List<DocumentFieldEdit> edits,
            Map<String, List<Long>> editEvidence) {
        for (String fieldId : editEvidence.keySet()) {
            boolean setsThisField = edits.stream()
                    .anyMatch(edit -> edit instanceof DocumentFieldEdit.SetValue && edit.fieldId().equals(fieldId));
            if (!setsThisField) {
                throw new DocumentContentValidationException(List.of(new DocumentContentProblem(
                        fieldId,
                        DocumentContentProblemReason.INVALID_EVIDENCE_REFERENCE,
                        "Evidence can only be supplied for a field this same edit sets a new value for.")));
            }
        }
        Map<String, List<Long>> merged = new LinkedHashMap<>(previousEvidence);
        for (DocumentFieldEdit edit : edits) {
            merged.remove(edit.fieldId());
        }
        merged.putAll(editEvidence);
        return merged;
    }

    /**
     * A field/item this same call did not touch keeps whatever {@link
     * FieldState} its previous revision recorded -- the same "untouched
     * stays as it was" rule {@link #mergeEvidenceAfterEdits} already
     * applies to evidence. A field/item this call sets or replaces
     * invalidates authorship, evidence support, validation, and review
     * back to fresh defaults instead of inheriting stale ones, since the
     * value itself just changed: {@code authorshipForTouched} lets a
     * caller distinguish a direct human edit ({@link
     * Authorship#USER_AUTHORED}, via {@link #applyTypedEdits}) from an
     * accepted patch proposal ({@link Authorship#AI_COMPOSED}, via {@link
     * #acceptPatch}). {@code lock} is deliberately the one dimension a
     * value change never resets: a lock is a person's own separate,
     * deliberate decision (see {@link #setFieldLock}), not a comment on
     * the field's current wording, and must survive being edited the same
     * way it survives being read -- only an explicit lock change, or a
     * field this codebase has never recorded a state for at all, produces
     * anything other than the field's own prior lock value.
     */
    private static Map<FieldItemRef, FieldState> computeFieldStates(
            Map<FieldItemRef, FieldState> previousFieldStates,
            DocumentContent content,
            Set<String> touchedFieldIds,
            Map<String, List<Long>> evidence,
            Authorship authorshipForTouched) {
        Map<FieldItemRef, FieldState> next = new LinkedHashMap<>();
        content.fields().forEach((fieldId, value) -> {
            boolean touched = touchedFieldIds.contains(fieldId);
            boolean hasEvidence = evidence.containsKey(fieldId) && !evidence.get(fieldId).isEmpty();
            for (FieldItemRef ref : FieldItemRef.allFor(fieldId, value)) {
                FieldState previous = previousFieldStates.get(ref);
                if (touched) {
                    LockState preservedLock = previous != null ? previous.lock() : LockState.EDITABLE;
                    next.put(ref, new FieldState(
                            authorshipForTouched,
                            hasEvidence ? EvidenceSupport.DIRECT : EvidenceSupport.MISSING,
                            ValidationState.NOT_RUN,
                            ReviewState.UNREVIEWED,
                            preservedLock));
                } else {
                    next.put(ref, previous != null ? previous : FieldState.freshlyUserAuthored(hasEvidence));
                }
            }
        });
        return next;
    }

    private TemplateVersion requireActiveTemplateVersion(
            long workspaceId, long userId, long templateId, long templateVersionId) {
        TemplateVersion templateVersion = templateRepository
                .findVersion(workspaceId, userId, templateId, templateVersionId)
                .orElseThrow(() -> new DocumentTemplateVersionUnavailableException(templateId, templateVersionId));
        if (templateVersion.status() != TemplateVersionStatus.ACTIVATED) {
            throw new DocumentTemplateVersionUnavailableException(templateId, templateVersionId);
        }
        return templateVersion;
    }

    /**
     * A document always has a current revision, so the only way to find a
     * document and then not find its current revision is for the document
     * to have gone to the trash, or been deleted for good, between the two
     * reads. That is a document that is not there, and is answered as one;
     * it is not damaged data, which is what it used to be reported as.
     */
    private DocumentRevision requireCurrentRevision(long workspaceId, long userId, long documentId) {
        return documentRepository.findCurrentRevision(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }
}
