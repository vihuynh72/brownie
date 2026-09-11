package io.github.vihuynh72.brownie.core.template;

import java.util.List;
import java.util.Optional;

/**
 * Every method takes workspace and user context explicitly rather than a
 * bare template ID, the same tenant-scoped pattern {@code
 * ArtifactRepository} established.
 */
public interface TemplateRepository {

    /** Creates the template and its first draft version (version 1, empty field definitions) together. */
    Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId);

    Optional<Template> find(long workspaceId, long userId, long templateId);

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
