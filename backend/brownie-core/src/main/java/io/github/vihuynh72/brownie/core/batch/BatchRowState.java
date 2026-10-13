package io.github.vihuynh72.brownie.core.batch;

/**
 * Where one spreadsheet row stands in its batch. A row is PENDING until a
 * step names its document (CREATED) or its failure (FAILED); a blank row is
 * skipped once, at creation, not per step; DOCUMENT_DELETED is written by
 * document purge for a row whose document is gone for good.
 */
public enum BatchRowState {
    PENDING,
    CREATED,
    SKIPPED_BLANK,
    FAILED,
    DOCUMENT_DELETED
}
