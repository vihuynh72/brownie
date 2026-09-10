package io.github.vihuynh72.brownie.core.source;

/**
 * How a source's bytes/text actually arrived. Only {@link #ARTIFACT} is
 * produced today (an uploaded file already governed by the artifact
 * lifecycle) -- pasted text with no backing artifact and a connector-
 * fetched resource are real, plan-described possibilities, but nothing in
 * this codebase creates either kind yet, so neither is listed here until
 * something actually does.
 */
public enum SourceKind {
    ARTIFACT
}
