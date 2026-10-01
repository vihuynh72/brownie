package io.github.vihuynh72.brownie.core.prepare;

/**
 * Decides which of the places Brownie's rules found in a form are really
 * meant for filling in, and what to call each one. One path for Word and
 * PDF. An implementation never fails the upload it serves: when it cannot
 * do better, it answers with the rules' own decisions and says why.
 */
public interface SpotNamer {

    SpotNaming name(long workspaceId, long userId, SpotNamingInput input);
}
