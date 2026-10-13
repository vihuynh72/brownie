package io.github.vihuynh72.brownie.api.batch;

/**
 * The body an export run is created from: the one format every item ships
 * in. Blank means malformed; a value that is not a format is refused the
 * same way any other bad body field is.
 */
record CreateBatchExportRequest(String format) {
}
