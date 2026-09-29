package io.github.vihuynh72.brownie.core.connector;

/**
 * One kind of thing a person can let Brownie do with their outside account.
 * Each is agreed to on its own and carries only the permission it needs, so
 * a connection made for one never quietly allows the other.
 */
public enum ConnectorAccess {
    /** Reading files the person picks in Google Drive, and nothing else in their Drive. */
    DRIVE_FILES,
    /** Reading events on calendars the person owns, within a window they choose. */
    CALENDAR_EVENTS,
    /**
     * Saving new files to the person's Drive, and adding text to Google Docs
     * Brownie saved there, each only when they approve that exact change. Its
     * own consent, apart from reading picked files, so that agreeing to let
     * Brownie read is never agreeing to let it write.
     */
    DRIVE_SAVING,
    /**
     * Adding new events to the person's main calendar, each only when they
     * approve that exact event, with no guests and no notifications. Its own
     * consent, asked for the first time an event is added, so that letting
     * Brownie read a calendar is never letting it write there.
     */
    CALENDAR_EVENT_CREATION
}
