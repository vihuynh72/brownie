package io.github.vihuynh72.brownie.core.prepare;

import java.util.Optional;

/**
 * Makes a PDF upload ready to fill, the PDF counterpart of the Word path in
 * {@link FillableFormService}, which hands every PDF here.
 */
public interface PdfFormPreparer {

    FillableForm prepare(long workspaceId, long userId, long artifactId);

    /** The answer an earlier {@link #prepare} kept for this upload, if there is one; never prepares anything. */
    Optional<FillableForm> find(long workspaceId, long userId, long artifactId);
}
