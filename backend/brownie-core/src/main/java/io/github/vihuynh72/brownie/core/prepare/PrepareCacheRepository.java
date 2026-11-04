package io.github.vihuynh72.brownie.core.prepare;

import java.util.List;
import java.util.Optional;

/**
 * The prepared answer for a file, kept by the file's own hash, across every
 * workspace: the spots, notices and naming one prepare produced, and for a
 * Word form the worked-copy bytes to hand back. A second take-in of the same
 * bytes -- a library form started again, a file uploaded twice -- replays it
 * instead of finding and naming the places again. Rows are keyed on the
 * recipe version, so a changed recipe reads nothing an older one left.
 */
public interface PrepareCacheRepository {

    /** One prepared answer. {@code outputBytes} is the worked copy for a Word form, null for a PDF. */
    record CachedPreparation(
            String sourceSha256,
            String recipeVersion,
            String sourceFormat,
            byte[] outputBytes,
            List<FillableForm.Spot> spots,
            List<PreparationNotice> notices,
            String converter,
            NamingSource spotNaming,
            String rulesOnlyReason) {}

    Optional<CachedPreparation> find(String sourceSha256, String recipeVersion);

    /** Remembers a preparation; a same-key row already there wins, like every kept answer. */
    void remember(CachedPreparation prepared);
}
