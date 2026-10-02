package io.github.vihuynh72.brownie.core.template;

import java.util.Collection;
import java.util.Set;

/** Tenant-scoped, like every repository: workspace and user are explicit on each call. */
public interface FillSpotReviewRepository {

    /** Records that {@code userId} kept each of the template's fields; a field kept before stays as it was. */
    void keep(long workspaceId, long userId, long templateId, Collection<String> fieldIds);

    /** Every field of the template a person has kept. */
    Set<String> keptFieldIds(long workspaceId, long userId, long templateId);
}
