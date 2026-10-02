package io.github.vihuynh72.brownie.core.template;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence for template versions made from other versions, kept apart
 * from {@link TemplateRepository} (which owns the draft-then-activate life
 * of a version made from a file) the same way {@link
 * TemplateBaselineRenderRepository} is. Every method takes workspace and
 * user context explicitly. The writing methods join the caller's
 * transaction: a derived version, its rules, the template's pointer and the
 * document's move are one unit.
 */
public interface TemplateLineageRepository {

    /**
     * Every field ID any version of the template has ever defined, so a new
     * spot's ID never reuses one (see {@link FieldIds#fromLabel}).
     */
    Set<String> findFieldIdsEverUsed(long workspaceId, long userId, long templateId);

    /** The version already made from {@code baseVersionId} for the same request, if one exists. */
    Optional<TemplateVersion> findDerived(long workspaceId, long userId, long templateId, long baseVersionId, String derivationKey);

    /**
     * Locks the template's row for the rest of the transaction, so two
     * corrections of the same template are made one after the other, and
     * answers its current active version (null when it has none). Throws
     * {@link TemplateNotFoundException} when there is no such template.
     */
    Long lockTemplate(long workspaceId, long userId, long templateId);

    /**
     * Writes the prepared version as activated, with the next version
     * number, copies its rewritten rules keeping their statuses, points the
     * template at it, and records the derivation in the audit trail with
     * IDs and counts only. The caller holds {@link #lockTemplate} and has
     * checked the template is still on the base version.
     */
    TemplateVersion insertDerived(long workspaceId, long userId, PreparedDerivation prepared);

    /**
     * Moves the template's current version from {@code baseVersionId} on to
     * {@code derivedVersionId}, a version made from it, as making that
     * version did. Used when the same correction is asked for again after an
     * Undo took the template back to the base: the version made the first
     * time is used again, and the template goes on to it with the document.
     * The caller holds {@link #lockTemplate} and has checked the template is
     * on the base.
     */
    void advanceToDerived(long workspaceId, long userId, long templateId, long baseVersionId, long derivedVersionId);

    /** The field IDs a derived version's change list names, in the order it names them; empty for a version made from a file. */
    List<String> findChangedFieldIds(long workspaceId, long userId, long templateVersionId);

    /** How many documents in the workspace, other than {@code exceptDocumentId} and those in the trash, are on the version. */
    int countLiveDocumentsOn(long workspaceId, long userId, long templateVersionId, long exceptDocumentId);

    /**
     * After a document is taken back across one derivation step (Undo), moves
     * the template's current version from {@code fromVersionId} to {@code
     * toVersionId} too, so new documents do not get a spot that was just
     * undone; and forward again on Redo. Only when the template is on {@code
     * fromVersionId} now, one of the two versions was made directly from the
     * other, and no document other than {@code documentId} is still on
     * {@code fromVersionId}. Answers whether it moved. The caller holds
     * {@link #lockTemplate}, and every path that puts a document on a
     * version also locks the template's row (creating, moving, restoring,
     * correcting), so a document still landing on {@code fromVersionId} is
     * waited for, not missed.
     */
    boolean stepCurrentVersion(long workspaceId, long userId, long templateId, long fromVersionId, long toVersionId, long documentId);

    /** Records in the audit trail that a document moved from one version to another, with IDs only. */
    void recordDocumentVersionChanged(
            long workspaceId, long userId, long documentId, long fromVersionId, long toVersionId, long revisionId);
}
