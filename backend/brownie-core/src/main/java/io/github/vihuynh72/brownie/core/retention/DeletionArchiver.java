package io.github.vihuynh72.brownie.core.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Copies carried-out deletions out of the database, and applies copied-out
 * deletions to a database that may have forgotten them. Copying out writes
 * the entry first and records that it was written second, so a crash in
 * between only means the same entry is written again, which the archive
 * treats as nothing new.
 */
public final class DeletionArchiver {

    private static final Logger log = LoggerFactory.getLogger(DeletionArchiver.class);

    private final DeletionArchiveRepository archiveRepository;
    private final DeletionLedgerArchive archive;

    public DeletionArchiver(DeletionArchiveRepository archiveRepository, DeletionLedgerArchive archive) {
        this.archiveRepository = Objects.requireNonNull(archiveRepository, "archiveRepository");
        this.archive = Objects.requireNonNull(archive, "archive");
    }

    /** How many entries were copied out, and how many could not be this time. */
    public record ArchiveResult(int archived, int failed) {
    }

    /** What applying the archive to this database did. {@code stillStopping} entries need another run once their jobs have let go. */
    public record ReplayResult(int entries, int replayed, int absent, int stillStopping) {

        public boolean complete() {
            return stillStopping == 0;
        }
    }

    public ArchiveResult archiveOnce(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive.");
        }
        int archived = 0;
        int failed = 0;
        for (ArchivedDeletion entry : archiveRepository.collectUnarchived(limit)) {
            try {
                archive.add(entry);
                if (archiveRepository.markArchived(entry.requestId())) {
                    archived++;
                }
            } catch (IOException | RuntimeException failure) {
                failed++;
                log.warn("Could not copy deletion request {} out of the database; it will be tried again.", entry.requestId(), failure);
            }
        }
        return new ArchiveResult(archived, failed);
    }

    /**
     * Applies every archived deletion to this database. Meant for a database
     * that was just restored and that nobody has been let into yet; against
     * one that lost nothing, every entry answers that its target is already
     * gone and nothing changes.
     */
    public ReplayResult replayAll() throws IOException {
        List<ArchivedDeletion> entries = archive.readAll();
        int replayed = 0;
        int absent = 0;
        int stillStopping = 0;
        for (ArchivedDeletion entry : entries) {
            String outcome = archiveRepository.replay(entry);
            switch (outcome) {
                case "REPLAYED" -> {
                    replayed++;
                    log.info("Deletion of {} {} in workspace {} was applied again to this database.",
                            entry.scope(), entry.targetId(), entry.workspaceId());
                }
                case "ABSENT" -> absent++;
                default -> {
                    stillStopping++;
                    log.warn("Deletion of {} {} in workspace {} could not be applied yet ({}).",
                            entry.scope(), entry.targetId(), entry.workspaceId(), outcome);
                }
            }
        }
        return new ReplayResult(entries.size(), replayed, absent, stillStopping);
    }
}
