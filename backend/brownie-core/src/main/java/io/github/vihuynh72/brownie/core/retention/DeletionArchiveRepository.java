package io.github.vihuynh72.brownie.core.retention;

import java.util.List;

/** The worker's side of copying deletions out of the database and applying copied-out ones to it. */
public interface DeletionArchiveRepository {

    /** Carried-out deletions that have not been copied out yet, oldest first. */
    List<ArchivedDeletion> collectUnarchived(int limit);

    /** Records that the entry now exists outside the database; false when it was already recorded. */
    boolean markArchived(long requestId);

    /**
     * Applies one archived deletion to this database: {@code REPLAYED} when
     * its target was here and is now gone, {@code ABSENT} when there was
     * nothing to do, anything else when it could not be done yet.
     */
    String replay(ArchivedDeletion entry);
}
