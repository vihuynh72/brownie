package io.github.vihuynh72.brownie.core.action;

import java.util.Objects;

/**
 * One file to save: its reserved id (absent for a conversion), its name, the
 * type of the bytes, the Google type it is to become when Google converts
 * it (absent otherwise), and the bytes themselves.
 */
public record NewDriveFile(String reservedId, String name, String mimeType, String convertTo, byte[] content) {

    public NewDriveFile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(content, "content");
        if ((reservedId == null) == (convertTo == null)) {
            throw new IllegalArgumentException("A file is either saved as it is under a reserved id, or converted without one.");
        }
    }

    /** The bytes are a person's document, and never belong in a log line. */
    @Override
    public String toString() {
        return "NewDriveFile[" + content.length + " bytes" + (convertTo == null ? "" : ", converted") + "]";
    }
}
