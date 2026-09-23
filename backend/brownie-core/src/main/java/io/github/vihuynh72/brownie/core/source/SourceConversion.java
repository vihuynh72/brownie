package io.github.vihuynh72.brownie.core.source;

/**
 * How Brownie changed a copied source's form on the way in, when it did. A
 * source copied as it was would have none.
 */
public enum SourceConversion {
    /** A calendar event, written out by Brownie as labelled paragraphs of text. */
    CALENDAR_EVENT_AS_TEXT
}
