package io.github.vihuynh72.brownie.core.form;

import java.util.Optional;

/**
 * The form library's kept copies of the agencies' own bytes: one row per
 * catalog form, so a start on a fresh copy skips the download entirely, and
 * {@code checkedAt} says when the bytes were last read from the source --
 * the three-day re-check re-reads them and replaces the row only when they
 * changed. The bytes are the public form's, never a person's data, so one
 * row serves every workspace.
 */
public interface CatalogReadyRepository {

    /** The kept agency bytes for a catalog form, or empty when it was never fetched. */
    record ReadyForm(
            String catalogId,
            byte[] sourceBytes,
            String sourceSha256,
            java.time.OffsetDateTime checkedAt) {}

    Optional<ReadyForm> find(String catalogId);

    /** Writes or replaces the kept bytes after a fetch that found them new or changed. */
    void remember(ReadyForm ready);

    /** Only stamps the check against unchanged bytes -- the copy itself is kept. */
    void restamp(String catalogId);
}
