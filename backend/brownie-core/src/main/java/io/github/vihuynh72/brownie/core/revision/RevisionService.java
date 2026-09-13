package io.github.vihuynh72.brownie.core.revision;

import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies bounded typed field edits and delegates the current-pointer
 * compare-and-swap to the document repository.
 */
public class RevisionService {

    private final DocumentRepository documentRepository;
    private final TemplateRepository templateRepository;

    public RevisionService(DocumentRepository documentRepository, TemplateRepository templateRepository) {
        this.documentRepository = documentRepository;
        this.templateRepository = templateRepository;
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
        DocumentContentValidator.validate(initialContent, templateVersion.fieldDefinitions());
        DocumentContentValidator.validateEvidence(initialContent, initialEvidence);
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
                initialRevisionReason);
    }

    public Optional<Document> findDocument(long workspaceId, long userId, long documentId) {
        return documentRepository.find(workspaceId, userId, documentId);
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
        DocumentRevision current = documentRepository.findCurrentRevision(workspaceId, userId, documentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Document " + documentId + " has no revision selected by its current pointer."));
        if (current.id() != expectedRevisionId) {
            throw new DocumentRevisionConflictException(documentId, expectedRevisionId, current.id());
        }
        TemplateVersion templateVersion = requireActiveTemplateVersion(
                workspaceId, userId, document.templateId(), document.templateVersionId());
        DocumentContent nextContent = DocumentContentValidator.applyEdits(
                current.content(), edits, templateVersion.fieldDefinitions());
        Map<String, List<Long>> nextEvidence = mergeEvidenceAfterEdits(current.evidence(), edits, editEvidence);
        DocumentContentValidator.validateEvidence(nextContent, nextEvidence);
        return documentRepository.appendRevisionIdempotently(
                workspaceId,
                userId,
                idempotencyKey,
                requestHash,
                documentId,
                expectedRevisionId,
                nextContent,
                nextEvidence,
                editReason);
    }

    /**
     * A field whose value did not change in this edit keeps whatever
     * evidence its previous revision recorded. A field this edit touches
     * -- set to a new value, or cleared -- starts from nothing: per the
     * plan's own rule that changed wording must be rechecked, not silently
     * inherit an old citation, the caller must explicitly re-assert
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
}
