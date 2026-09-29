package io.github.vihuynh72.brownie.core.action;

import java.util.Objects;

/**
 * A Google Doc as read: its revision (opaque, present only with edit access),
 * its title, the text of its first tab in document order (table cells
 * included), and the index at which that tab's body ends, in UTF-16 code
 * units as Docs counts.
 */
public record GoogleDocContent(String documentId, String revisionId, String title, String text, int endIndex) {

    public GoogleDocContent {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(text, "text");
    }

    /** The text is a person's document, and never belongs in a log line. */
    @Override
    public String toString() {
        return "GoogleDocContent[" + text.length() + " characters, ending at " + endIndex + "]";
    }
}
