package io.github.vihuynh72.brownie.core.retention;

import java.io.IOException;
import java.util.List;

/**
 * Where carried-out deletions are recorded outside the database, so that a
 * database restored from an older backup can be told what it has forgotten
 * before anyone is let in. It is written to and never edited: an entry is
 * added once and stays.
 */
public interface DeletionLedgerArchive {

    /** Adds the entry; adding one that is already there changes nothing and is not an error. */
    void add(ArchivedDeletion entry) throws IOException;

    /** Every entry there is, in no particular order. */
    List<ArchivedDeletion> readAll() throws IOException;
}
