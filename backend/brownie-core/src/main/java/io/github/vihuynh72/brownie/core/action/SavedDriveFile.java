package io.github.vihuynh72.brownie.core.action;

import java.util.Objects;

/**
 * What Drive says about a file Brownie saved: enough to compare with what was
 * approved. {@code size}, {@code md5} and {@code sha256} are absent when
 * Drive does not say (a Google Doc has none of them; a stored file may not
 * have its checksums yet). {@code link} is the page that opens it.
 */
public record SavedDriveFile(
        String id,
        String name,
        String mimeType,
        boolean trashed,
        int parentCount,
        boolean shared,
        Long size,
        String md5,
        String sha256,
        String link) {

    public SavedDriveFile {
        Objects.requireNonNull(id, "id");
    }
}
