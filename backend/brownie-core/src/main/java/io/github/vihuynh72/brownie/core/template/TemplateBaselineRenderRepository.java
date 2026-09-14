package io.github.vihuynh72.brownie.core.template;

import java.util.Optional;

/**
 * Persists the successful baseline render an activated version was proven
 * against -- a separate, sibling concept from {@link TemplateRepository}
 * itself, the same "small dedicated repository" shape {@code
 * TemplateExampleRepository} already uses rather than widening an
 * existing, already-heavily-depended-on interface for one new capability.
 * Only ever written once, for a version that is about to activate; never
 * written for a failed attempt.
 */
public interface TemplateBaselineRenderRepository {

    void recordBaselineRender(long workspaceId, long userId, long templateVersionId, BaselineRenderResult result);

    Optional<BaselineRenderResult> findBaselineRender(long workspaceId, long userId, long templateVersionId);
}
