package io.github.vihuynh72.brownie.core.prepare;

import java.util.Optional;

/**
 * Makes a PDF upload ready to fill, the PDF counterpart of the Word path in
 * {@link FillableFormService}, which hands every PDF here.
 */
public interface PdfFormPreparer {

    default FillableForm prepare(long workspaceId, long userId, long artifactId) {
        return prepare(workspaceId, userId, artifactId, false);
    }

    /**
     * {@code shared} when the bytes are everyone's -- a library form's, never a
     * person's upload: only a shared file's prepared answer is kept in the
     * across-workspace cache.
     */
    FillableForm prepare(long workspaceId, long userId, long artifactId, boolean shared);

    /** The answer an earlier {@link #prepare} kept for this upload, if there is one; never prepares anything. */
    Optional<FillableForm> find(long workspaceId, long userId, long artifactId);
}
