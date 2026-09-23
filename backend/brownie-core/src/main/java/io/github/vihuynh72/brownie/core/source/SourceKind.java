package io.github.vihuynh72.brownie.core.source;

/**
 * How a source's bytes or text actually arrived. Whatever the kind, they
 * arrived as an artifact that went through the same checks as an upload; the
 * kind says what that artifact was copied from, which is what a rule about
 * allowed sources, and a person reading a citation, need to know. Pasted text
 * with no backing artifact is a real possibility, but nothing in this codebase
 * creates it, so it is not listed here until something actually does.
 */
public enum SourceKind {
    /** A file the person uploaded. */
    ARTIFACT,
    /** An event on the person's own Google calendar, written out as text when it was copied. */
    GOOGLE_CALENDAR
}
