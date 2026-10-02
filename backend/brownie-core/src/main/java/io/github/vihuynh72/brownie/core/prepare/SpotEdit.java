package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;

/** One change {@link FillSpotEditor} makes to a Word file. */
public sealed interface SpotEdit {

    /**
     * Makes the place {@code anchor} names a spot tagged {@code tag} and
     * titled {@code alias}. {@code blankText} is the form's own blank the spot
     * replaced (underscores, a bracketed prompt), recorded so an empty spot can
     * still print it; null when the spot was added where there was no blank.
     */
    record Insert(DocxAnchor anchor, String tag, String alias, String blankText) implements SpotEdit {
    }

    /** Gives an existing control, found at {@code controlNodeId} in {@code part}, the tag and title of a spot. */
    record Retag(DocumentPartKind part, String controlNodeId, String tag, String alias) implements SpotEdit {
    }

    /** Takes away the spot tagged {@code tag} and puts its runs back in the paragraph, as they were before it was made. */
    record Unwrap(String tag) implements SpotEdit {
    }

    /** Takes the tag and title off the control tagged {@code tag}, leaving the control as the form had it. */
    record Untag(String tag) implements SpotEdit {
    }
}
