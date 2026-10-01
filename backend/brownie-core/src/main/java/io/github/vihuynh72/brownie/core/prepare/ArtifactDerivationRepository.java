package io.github.vihuynh72.brownie.core.prepare;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped, like every repository: workspace and user are explicit on each call. */
public interface ArtifactDerivationRepository {

    Optional<ArtifactDerivation> find(long workspaceId, long userId, long sourceArtifactId, String kind, String recipeVersion);

    /**
     * Records a derivation made by {@code userId}, or, when a request racing
     * this one recorded the same (source, kind, recipe version) first,
     * returns that one unchanged: the first answer stands, and the loser's
     * output is left unreferenced for the sweep.
     */
    ArtifactDerivation insertOrGet(
            long workspaceId,
            long userId,
            long sourceArtifactId,
            long outputArtifactId,
            String kind,
            String recipeVersion,
            String sourceFormat,
            String converter,
            NamingSource spotNaming,
            String rulesOnlyReason,
            List<FillableForm.Spot> spots,
            List<PreparationNotice> notices);
}
