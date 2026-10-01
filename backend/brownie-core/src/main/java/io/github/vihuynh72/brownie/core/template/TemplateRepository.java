package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;

import java.util.List;
import java.util.Optional;

/**
 * Every method takes workspace and user context explicitly rather than a
 * bare template ID, the same tenant-scoped pattern {@code
 * ArtifactRepository} established.
 */
public interface TemplateRepository {

    /**
     * Creates the template and its first draft version (version 1, empty
     * field definitions) together. {@code preparationNotices} is what the
     * person was told when the file was made ready to fill, kept with the
     * draft, or null when there is none to keep.
     */
    Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId,
                         List<PreparationNotice> preparationNotices);

    /** {@link #createDraft}, for a PDF template: its first draft is pinned to the PDF's form reading instead of a Word graph. */
    Template createPdfDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long pdfFormExtractionId,
                            List<PreparationNotice> preparationNotices);

    /** {@link #createDraft} with no notices kept. */
    default Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId) {
        return createDraft(workspaceId, userId, displayName, sourceArtifactId, extractionVersionId, null);
    }

    /** {@link #createPdfDraft} with no notices kept. */
    default Template createPdfDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long pdfFormExtractionId) {
        return createPdfDraft(workspaceId, userId, displayName, sourceArtifactId, pdfFormExtractionId, null);
    }

    Optional<Template> find(long workspaceId, long userId, long templateId);

    /**
     * Every template in the workspace, those in the Trash Bin included, in
     * creation order -- oldest first, matching a document's own history
     * ordering.
     */
    List<Template> findAll(long workspaceId, long userId);

    /** Only the templates in the Trash Bin, the most recently trashed first. */
    List<Template> findTrashed(long workspaceId, long userId);

    /**
     * Moves the template to the Trash Bin. A template already there is
     * returned as it is, keeping the time it was first trashed. Throws
     * {@link TemplateNotFoundException} when there is no such template in
     * the workspace.
     */
    Template trash(long workspaceId, long userId, long templateId);

    /** Takes the template back out of the Trash Bin; one that is not there is returned as it is. Throws {@link TemplateNotFoundException} the same way {@link #trash} does. */
    Template restore(long workspaceId, long userId, long templateId);

    /** The template's current open draft, if it has one -- at most one exists per template at a time. */
    Optional<TemplateVersion> findDraftVersion(long workspaceId, long userId, long templateId);

    Optional<TemplateVersion> findVersion(long workspaceId, long userId, long templateId, long versionId);

    /**
     * Replaces the current draft's field definitions in place and advances
     * its {@code versionNumber} by one, but only if the draft's current
     * number still equals {@code expectedVersionNumber} and it is still
     * DRAFT. Throws {@link TemplateVersionStateConflictException}
     * otherwise -- there is no template here, no open draft, or someone
     * already moved the draft to a different number.
     */
    TemplateVersion replaceDraftBindings(
            long workspaceId, long userId, long templateId, int expectedVersionNumber, List<FieldDefinition> fieldDefinitions);

    /**
     * Transitions the current draft to ACTIVATED and points the template's
     * {@code currentActiveVersionId} at it, but only if the draft's current
     * number still equals {@code expectedVersionNumber} and it is still
     * DRAFT. Throws {@link TemplateVersionStateConflictException}
     * otherwise, the same guard {@link #replaceDraftBindings} uses.
     */
    TemplateVersion activate(long workspaceId, long userId, long templateId, int expectedVersionNumber);
}
