package io.github.vihuynh72.brownie.core.action;

import java.util.Optional;

/**
 * A Google Doc that Brownie saved: reading its text and the revision it is
 * at (to check what Google's conversion of a saved file kept, and what an
 * addition did), and adding text at the end of its first tab. The addition
 * names the revision the person approved it against, and Google applies it
 * only if the Doc is still at that revision.
 */
public interface GoogleDocs {

    /** The Doc's first tab as text, and where its body ends; empty when Google says no such Doc exists. */
    Optional<GoogleDocContent> read(String accessToken, String documentId);

    /**
     * Adds {@code text} at the end of the Doc's first tab in one request that
     * Google applies whole or not at all, and only while the Doc is at {@code
     * requiredRevisionId}. Never throws for what Google answered; an applied
     * answer carries the revision the Doc is at afterwards.
     */
    WriteAnswer append(String accessToken, String documentId, String text, String requiredRevisionId);
}
