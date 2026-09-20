package io.github.vihuynh72.brownie.worker.retention;

import io.github.vihuynh72.brownie.core.retention.DeletionArchiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * What the worker does when it is started with
 * {@code brownie.worker.mode=replay-deletions}: applies every deletion
 * recorded outside the database to the database it is pointed at, says what
 * that changed, and ends. This is the step between restoring a backup and
 * letting anyone in. Nothing else runs in this mode; stored files the
 * replay queues for removal are removed by the worker when it is next
 * started normally.
 *
 * <p>The process ends with status 0 only when every entry was applied or
 * had nothing to apply to. Anything else (the archive could not be read,
 * an entry could not be applied yet) ends with status 3, so a restore
 * script cannot mistake an incomplete replay for a finished one.
 */
@Component
@ConditionalOnProperty(name = "brownie.worker.mode", havingValue = "replay-deletions")
class DeletionReplayRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(DeletionReplayRunner.class);

    private final DeletionArchiver archiver;
    private int exitCode = 3;

    DeletionReplayRunner(DeletionArchiver archiver) {
        this.archiver = Objects.requireNonNull(archiver, "archiver must not be null");
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            DeletionArchiver.ReplayResult result = archiver.replayAll();
            log.info(
                    "Deletion replay: {} recorded deletions read, {} applied again to this database, {} already absent, {} could not be applied yet.",
                    result.entries(), result.replayed(), result.absent(), result.stillStopping());
            // One line a script can read without parsing structured logs.
            System.out.println("DELETION_REPLAY entries=" + result.entries() + " replayed=" + result.replayed()
                    + " absent=" + result.absent() + " pending=" + result.stillStopping());
            exitCode = result.complete() ? 0 : 3;
        } catch (Exception failure) {
            log.error("Deletion replay failed; this database must not be served until it has succeeded.", failure);
            exitCode = 3;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
