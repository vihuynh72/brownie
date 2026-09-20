package io.github.vihuynh72.brownie.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on everything the worker does on a timer: claiming jobs, the
 * deletion and housekeeping sweeps, ledger maintenance. It is one switch on
 * purpose. A worker started only to apply the deletion ledger to a restored
 * database must not claim a single job from it, because some of those jobs
 * belong to documents the ledger is about to remove.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "brownie.worker.mode", havingValue = "serve", matchIfMissing = true)
class WorkerSchedulingConfig {
}
