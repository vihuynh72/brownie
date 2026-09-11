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
 */
public record Template(
        long id,
        long workspaceId,
        String displayName,
        TemplateStatus status,
        Long currentActiveVersionId,
        OffsetDateTime createdAt) {
}
