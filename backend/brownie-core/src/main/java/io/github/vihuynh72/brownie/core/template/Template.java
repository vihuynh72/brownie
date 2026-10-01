package io.github.vihuynh72.brownie.core.template;

import java.time.OffsetDateTime;

/**
 * A named template's identity and lifecycle pointer, kept separate from its
 * versions' own content the same way {@code Artifact} is kept separate from
 * its bytes. {@code currentActiveVersionId} is null until the first version
 * is activated and never regresses to null afterward -- an activated
 * version is permanent (see {@link TemplateVersion}). The current draft (if
 * any) is not pointed to from here; it is found by its own {@code DRAFT}
 * status, since at most one open draft ever exists per template.
 *
 * <p>{@code trashedAt} is when the template was moved to the Trash Bin, and
 * null while it is not there. A template in the Trash Bin is only hidden
 * from the list new documents are started from: its versions stay exactly
 * as they were, because every document already made from it still reads
 * them.
 */
public record Template(
        long id,
        long workspaceId,
        String displayName,
        TemplateStatus status,
        Long currentActiveVersionId,
        OffsetDateTime createdAt,
        OffsetDateTime trashedAt) {
}
