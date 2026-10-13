package io.github.vihuynh72.brownie.core.batch;

/**
 * Whether an export run is still working through its items or has an
 * outcome for every one of them. A RUNNING export is advanced in steps;
 * COMPLETE is written the moment no PENDING item remains, together with
 * the audit event that records the run's counts.
 */
public enum BatchExportState {
    RUNNING,
    COMPLETE
}
