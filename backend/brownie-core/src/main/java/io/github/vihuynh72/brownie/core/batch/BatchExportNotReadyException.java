package io.github.vihuynh72.brownie.core.batch;

/**
 * A download was asked for before there was anything to hand out: the
 * run is still RUNNING, or it completed without a single EXPORTED item.
 * Both are the same answer to a browser polling early, so one exception
 * covers both.
 */
public final class BatchExportNotReadyException extends RuntimeException {

    public BatchExportNotReadyException(BatchExport export) {
        super(export.state() == BatchExportState.RUNNING
                ? "Export " + export.id() + " is still running."
                : "Export " + export.id() + " has no exported files to download.");
    }
}
