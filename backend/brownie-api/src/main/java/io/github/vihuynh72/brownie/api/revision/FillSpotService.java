package io.github.vihuynh72.brownie.api.revision;

import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentCommandType;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.DocumentRepository;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.DocumentRevisionConflictException;
import io.github.vihuynh72.brownie.core.revision.DocumentTemplateVersionUnavailableException;
import io.github.vihuynh72.brownie.core.revision.FieldItemRef;
import io.github.vihuynh72.brownie.core.revision.FieldState;
import io.github.vihuynh72.brownie.core.revision.FillSpotLockedException;
import io.github.vihuynh72.brownie.core.revision.LockState;
import io.github.vihuynh72.brownie.core.revision.RevisionRestoreResult;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.revision.TemplateVersionMove;
import io.github.vihuynh72.brownie.core.template.DocumentTemplateVersionMovedException;
import io.github.vihuynh72.brownie.core.template.FillSpotChange;
import io.github.vihuynh72.brownie.core.template.PreparedDerivation;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateBaselineRenderRepository;
import io.github.vihuynh72.brownie.core.template.TemplateDerivationService;
import io.github.vihuynh72.brownie.core.template.TemplateLineageRepository;
import io.github.vihuynh72.brownie.core.template.TemplateNotFoundException;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionMovedOnException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Corrects the fill spots of an open document: adds one where the person
 * points, renames one, or takes one away, including spots Brownie found on
 * upload; on a PDF form, also moves a box or changes how it shows its text. Each correction makes a new activated template version from the
 * one the document is on and moves the document to it with its values, so
 * the document keeps everything it holds and every form started from the
 * template afterwards has the correction too. Documents already on the
 * earlier version stay on it until they are moved.
 *
 * <p>The slow part (editing the Word file, reading it back, rendering the
 * sample; for a PDF form only the quick sample fill) is done first, outside any transaction, by {@link
 * TemplateDerivationService}. The write is one short transaction: lock the
 * template, reuse the version this same request already made or require the
 * template still to be on the version the document is on (versions stay one
 * straight line), write the version with its rules and proven render, move
 * the template to it, move the document, and record both in the audit
 * trail. Everything the repositories do joins it, each setting who it acts
 * for as it starts, as they always do.
 *
 * <p>A request is answered first from its idempotency record, before any
 * slow work, so a retried request answers with what it did the first time.
 */
@Service
public class FillSpotService {

    private final RevisionService revisionService;
    private final TemplateService templateService;
    private final TemplateDerivationService templateDerivationService;
    private final TemplateLineageRepository templateLineageRepository;
    private final TemplateBaselineRenderRepository templateBaselineRenderRepository;
    private final DocumentRepository documentRepository;
    private final TransactionTemplate transactionTemplate;

    public FillSpotService(
            RevisionService revisionService,
            TemplateService templateService,
            TemplateDerivationService templateDerivationService,
            TemplateLineageRepository templateLineageRepository,
            TemplateBaselineRenderRepository templateBaselineRenderRepository,
            DocumentRepository documentRepository,
            TransactionTemplate transactionTemplate) {
        this.revisionService = revisionService;
        this.templateService = templateService;
        this.templateDerivationService = templateDerivationService;
        this.templateLineageRepository = templateLineageRepository;
        this.templateBaselineRenderRepository = templateBaselineRenderRepository;
        this.documentRepository = documentRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * What a correction did: the document's new revision (on {@code
     * templateVersion}), the revision it replaced, the IDs of the spots the
     * changes named in request order (a new spot's ID included), and how
     * many other documents are still on the version the document left.
     */
    public record FillSpotResult(
            DocumentMutationResult mutation,
            TemplateVersion templateVersion,
            Long templateLatestVersionId,
            long previousRevisionId,
            List<String> fieldIds,
            int otherDocumentsOnPreviousVersion) {

        public FillSpotResult {
            Objects.requireNonNull(mutation, "mutation");
            Objects.requireNonNull(templateVersion, "templateVersion");
            fieldIds = List.copyOf(fieldIds);
        }
    }

    /** What moving a document to another version did, with the template's current version for the page's banner. */
    public record VersionMoveResult(TemplateVersionMove move, Long templateLatestVersionId) {
    }

    /**
     * Refused before any slow work: 412 with {@link
     * DocumentRevisionConflictException} when {@code expectedRevisionId} is
     * not current; {@link DocumentTemplateVersionMovedException} when the
     * document is not on {@code templateVersionId} or the template has moved
     * past the document's version; {@code FillSpotChangeInvalidException}
     * for a change made for the other kind of form (a place in a Word form's
     * text on a PDF form, a box on a Word form); {@link
     * FillSpotLockedException} when a spot being taken away
     * holds a locked value. Refused at the write with
     * {@link TemplateVersionMovedOnException} when another correction of the
     * same template got there first.
     */
    public FillSpotResult changeFillSpots(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            long templateVersionId,
            List<FillSpotChange> changes) {
        Optional<DocumentMutationResult> replayed = documentRepository.findMutationResult(
                workspaceId, userId, DocumentCommandType.EDIT_CONTENT, idempotencyKey, requestHash);
        if (replayed.isPresent()) {
            return resultOf(workspaceId, userId, replayed.get());
        }
        Document document = revisionService.findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        DocumentRevision current = revisionService.findRevision(workspaceId, userId, documentId, document.currentRevisionId())
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (current.id() != expectedRevisionId) {
            throw new DocumentRevisionConflictException(documentId, expectedRevisionId, current.id());
        }
        Template template = templateService.find(workspaceId, userId, document.templateId())
                .orElseThrow(() -> new TemplateNotFoundException(document.templateId()));
        long latestVersionId = template.currentActiveVersionId() == null ? 0 : template.currentActiveVersionId();
        if (current.templateVersionId() != templateVersionId) {
            throw new DocumentTemplateVersionMovedException(documentId, current.templateVersionId(), latestVersionId);
        }
        TemplateVersion base = templateService.findVersion(workspaceId, userId, document.templateId(), current.templateVersionId())
                .orElseThrow(() -> new DocumentTemplateVersionUnavailableException(document.templateId(), current.templateVersionId()));
        TemplateDerivationService.requireChangesFit(base, changes);
        requireNoLockedValueIsRemoved(current, changes);

        String derivationKey = templateDerivationService.derivationKey(base.id(), changes);
        Optional<TemplateVersion> alreadyMade = templateLineageRepository.findDerived(
                workspaceId, userId, base.templateId(), base.id(), derivationKey);
        PreparedDerivation prepared = null;
        if (alreadyMade.isEmpty()) {
            if (latestVersionId != base.id()) {
                throw new DocumentTemplateVersionMovedException(documentId, base.id(), latestVersionId);
            }
            prepared = templateDerivationService.prepare(workspaceId, userId, base, changes);
        } else if (latestVersionId != base.id() && latestVersionId != alreadyMade.get().id()) {
            // The same correction was made before, but the form has since gone on from both: this is no longer its line.
            throw new DocumentTemplateVersionMovedException(documentId, base.id(), latestVersionId);
        }
        PreparedDerivation toWrite = prepared;
        String editReason = prepared != null ? prepared.editReason() : TemplateDerivationService.editReason(base, changes);
        return transactionTemplate.execute(status -> {
            TemplateVersion version = writeVersion(workspaceId, userId, base, derivationKey, toWrite);
            TemplateVersionMove move = revisionService.moveToTemplateVersion(
                    workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId, version.id(), editReason);
            if (!move.mutation().replayed()) {
                templateLineageRepository.recordDocumentVersionChanged(
                        workspaceId, userId, documentId, base.id(), version.id(), move.mutation().revision().id());
            }
            List<String> fieldIds = toWrite != null
                    ? toWrite.changedFieldIds()
                    : templateLineageRepository.findChangedFieldIds(workspaceId, userId, version.id());
            return new FillSpotResult(
                    move.mutation(), version, latestAfter(workspaceId, userId, base.templateId()), expectedRevisionId, fieldIds,
                    templateLineageRepository.countLiveDocumentsOn(workspaceId, userId, base.id(), documentId));
        });
    }

    /**
     * Moves the document to another activated version of its template,
     * carrying every value the other version has and dropping the rest (see
     * {@link RevisionService#moveToTemplateVersion}), and records the move in
     * the audit trail in the same transaction. The template's row is locked
     * first, as a correction and an Undo lock it, so a document moving onto
     * a version is seen by an Undo deciding whether any document is still
     * on it.
     */
    public VersionMoveResult moveToTemplateVersion(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            long templateVersionId) {
        Document document = revisionService.findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return transactionTemplate.execute(status -> {
            templateLineageRepository.lockTemplate(workspaceId, userId, document.templateId());
            String reason = templateService.findVersion(workspaceId, userId, document.templateId(), templateVersionId)
                    .map(version -> "Moved to version " + version.versionNumber() + " of the form.")
                    .orElse("Moved to another version of the form.");
            TemplateVersionMove move = revisionService.moveToTemplateVersion(
                    workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId, templateVersionId, reason);
            if (!move.mutation().replayed()) {
                templateLineageRepository.recordDocumentVersionChanged(
                        workspaceId, userId, documentId, move.previousTemplateVersionId(), templateVersionId,
                        move.mutation().revision().id());
            }
            return new VersionMoveResult(move, latestAfter(workspaceId, userId, document.templateId()));
        });
    }

    /**
     * Restores an earlier revision (see {@link RevisionService#restoreRevision}),
     * and when that takes the document back across a correction, takes the
     * template back with it: if the document was the only one on the version
     * it leaves, and the two versions are one correction apart, the template's
     * current version moves too, so undoing an added spot does not leave it in
     * every new document, and redoing it brings it back. That moves the form
     * every new document starts from, as a correction does, so it is done
     * only when {@code mayMoveTemplate} (the member may change templates);
     * otherwise the document alone goes back. The template's row is
     * locked before the document's, in the same order a correction takes
     * them, so an Undo and a correction at once wait for each other instead
     * of deadlocking, and a document that moves onto the version being left
     * is seen.
     */
    public RevisionRestoreResult restoreRevision(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            long targetRevisionId,
            String editReason,
            boolean mayMoveTemplate) {
        Document document = revisionService.findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return transactionTemplate.execute(status -> {
            templateLineageRepository.lockTemplate(workspaceId, userId, document.templateId());
            RevisionRestoreResult result = revisionService.restoreRevision(
                    workspaceId, userId, idempotencyKey, requestHash, documentId, expectedRevisionId, targetRevisionId, editReason);
            DocumentRevision restored = result.mutation().revision();
            if (result.mutation().replayed() || restored.parentRevisionId() == null) {
                return result;
            }
            DocumentRevision replaced = revisionService.findRevision(workspaceId, userId, documentId, restored.parentRevisionId())
                    .orElseThrow(() -> new DocumentNotFoundException(documentId));
            if (replaced.templateVersionId() != restored.templateVersionId()) {
                if (mayMoveTemplate) {
                    templateLineageRepository.stepCurrentVersion(
                            workspaceId, userId, result.mutation().document().templateId(), replaced.templateVersionId(),
                            restored.templateVersionId(), documentId);
                }
                templateLineageRepository.recordDocumentVersionChanged(
                        workspaceId, userId, documentId, replaced.templateVersionId(), restored.templateVersionId(), restored.id());
            }
            return result;
        });
    }

    /**
     * The version the same correction of the base already made, or the
     * prepared one, written now. Either way the template ends on it: a
     * version made before is used again only while the template is on it or
     * on the base (an Undo took it back), and the template then goes on to
     * it again, as when it was made; a new one needs the template still on
     * the base.
     */
    private TemplateVersion writeVersion(
            long workspaceId, long userId, TemplateVersion base, String derivationKey, PreparedDerivation prepared) {
        Long current = templateLineageRepository.lockTemplate(workspaceId, userId, base.templateId());
        Optional<TemplateVersion> alreadyMade = templateLineageRepository.findDerived(
                workspaceId, userId, base.templateId(), base.id(), derivationKey);
        if (alreadyMade.isPresent()) {
            TemplateVersion made = alreadyMade.get();
            if (current != null && current == base.id()) {
                templateLineageRepository.advanceToDerived(workspaceId, userId, base.templateId(), base.id(), made.id());
            } else if (current == null || current != made.id()) {
                throw new TemplateVersionMovedOnException(base.templateId(), base.id(), current);
            }
            return made;
        }
        if (prepared == null || current == null || current != base.id()) {
            throw new TemplateVersionMovedOnException(base.templateId(), base.id(), current);
        }
        TemplateVersion version = templateLineageRepository.insertDerived(workspaceId, userId, prepared);
        templateBaselineRenderRepository.recordBaselineRender(workspaceId, userId, version.id(), prepared.baseline());
        return version;
    }

    /** A replayed request answers from what it wrote the first time. */
    private FillSpotResult resultOf(long workspaceId, long userId, DocumentMutationResult mutation) {
        DocumentRevision revision = mutation.revision();
        long templateId = mutation.document().templateId();
        TemplateVersion version = templateService.findVersion(workspaceId, userId, templateId, revision.templateVersionId())
                .orElseThrow(() -> new DocumentTemplateVersionUnavailableException(templateId, revision.templateVersionId()));
        long previousRevisionId = revision.parentRevisionId() == null ? revision.id() : revision.parentRevisionId();
        long previousVersionId = revisionService.findRevision(workspaceId, userId, revision.documentId(), previousRevisionId)
                .map(DocumentRevision::templateVersionId)
                .orElse(revision.templateVersionId());
        return new FillSpotResult(
                mutation, version, latestAfter(workspaceId, userId, templateId), previousRevisionId,
                templateLineageRepository.findChangedFieldIds(workspaceId, userId, version.id()),
                templateLineageRepository.countLiveDocumentsOn(workspaceId, userId, previousVersionId, revision.documentId()));
    }

    private Long latestAfter(long workspaceId, long userId, long templateId) {
        return templateService.find(workspaceId, userId, templateId).map(Template::currentActiveVersionId).orElse(null);
    }

    /** Checked before any slow work; the move checks again under the write, where it counts. */
    private static void requireNoLockedValueIsRemoved(DocumentRevision current, List<FillSpotChange> changes) {
        for (FillSpotChange change : changes) {
            if (change instanceof FillSpotChange.Remove(String fieldId)) {
                for (Map.Entry<FieldItemRef, FieldState> state : current.fieldStates().entrySet()) {
                    if (state.getKey().fieldId().equals(fieldId) && state.getValue().lock() == LockState.EXPLICITLY_LOCKED
                            && current.content().fields().containsKey(fieldId)) {
                        throw new FillSpotLockedException(fieldId);
                    }
                }
            }
        }
    }
}
