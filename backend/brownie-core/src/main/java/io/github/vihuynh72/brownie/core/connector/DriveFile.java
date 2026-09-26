package io.github.vihuynh72.brownie.core.connector;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * What Google Drive says about one file, just before Brownie reads it: its
 * name and type, whether it is in the trash, whether this person may download
 * it, its size when Drive states one, the version Drive gives every change to
 * it, when it last changed, and where it can be opened.
 *
 * <p>{@code id}, {@code mimeType} and {@code version} are always there; the
 * rest may be null when Drive does not say. {@code size} says nothing
 * reliable about a Google Doc's exported text and is not used for one.
 */
public record DriveFile(
        String id,
        String name,
        String mimeType,
        boolean trashed,
        boolean canDownload,
        Long size,
        String version,
        OffsetDateTime modifiedTime,
        String webViewLink) {

    /** A native Google Doc: read as the plain text Google's own export produces. */
    public static final String GOOGLE_DOC = "application/vnd.google-apps.document";
    /** A file Drive stores as plain text: read as its stored bytes. */
    public static final String PLAIN_TEXT = "text/plain";

    public DriveFile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(version, "version");
    }

    public boolean isGoogleDoc() {
        return GOOGLE_DOC.equals(mimeType);
    }

    public boolean isPlainText() {
        return PLAIN_TEXT.equals(mimeType);
    }
}
