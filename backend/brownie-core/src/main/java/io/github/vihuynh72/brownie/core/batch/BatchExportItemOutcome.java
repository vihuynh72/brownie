package io.github.vihuynh72.brownie.core.batch;

/**
 * What an export run did, or will do, with one batch row. PENDING is the
 * only state an advance still works on; every other outcome is final for
 * that run. EXPORTED is the only one that names a receipt; CHANGED means
 * the document's content moved after the export was made, so shipping it
 * would betray what was approved.
 */
public enum BatchExportItemOutcome {
    PENDING,
    EXPORTED,
    BLOCKED,
    SUGGESTION_WAITING,
    NOT_CREATED,
    DOCUMENT_GONE,
    CHANGED,
    FAILED
}
