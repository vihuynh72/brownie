package io.github.vihuynh72.brownie.core.action;

import java.util.Optional;

/**
 * Saving one new file to a person's Drive, and reading back a file Brownie
 * saved. It never lists, searches, changes, moves, shares or deletes
 * anything, and it never reads a file's content: what it reads back is
 * Drive's description of a file Brownie itself made.
 *
 * <p>Answers to reads are refused in the usual ways ({@code
 * ProviderUnavailableException}, {@code ProviderTokenRejectedException},
 * {@code ProviderMisconfiguredException}, {@code
 * ConnectorBlockedByOrganizationException}); a write never throws for what
 * Google answered, and returns what the answer proves instead.
 */
public interface DriveFileWriter {

    /** Reserves one id for a new file, so that sending the same file again can be recognised and refused by Drive. */
    String reserveFileId(String accessToken);

    /**
     * Sends the file in one request, with no parent (so it lands at the top
     * of My Drive) and shared with nobody. A file with a reserved id is
     * created under that id; one to be converted names the Google type it is
     * to become and has none, since Drive refuses reserved ids for
     * conversions.
     */
    WriteAnswer createFile(String accessToken, NewDriveFile file);

    /** Drive's description of a file Brownie saved, or empty when Drive says no such file exists. */
    Optional<SavedDriveFile> describeSavedFile(String accessToken, String fileId);
}
