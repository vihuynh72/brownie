package io.github.vihuynh72.brownie.core.action;

/**
 * Each thing Brownie can do in a person's outside account, and only when
 * they approve that exact thing. They are separate kinds, not options of
 * one, because a person reads each preview differently and each ends in a
 * different record: a file kept byte for byte is not a file Google
 * converted, and neither is an event or a change to a document.
 */
public enum ActionType {

    /** A new file in the person's Drive holding the exact bytes of an export they approved. */
    DRIVE_SAVE_FILE,
    /** A new Google Doc that Google makes by converting an exported Word file; the result is a conversion, not a copy. */
    DRIVE_SAVE_AS_GOOGLE_DOC,
    /** One event on the person's own main calendar: not repeating, with no guests and nobody notified. */
    CALENDAR_CREATE_EVENT,
    /** Exact text added at the end of a Google Doc that Brownie itself made for this person. */
    GOOGLE_DOC_APPEND
}
