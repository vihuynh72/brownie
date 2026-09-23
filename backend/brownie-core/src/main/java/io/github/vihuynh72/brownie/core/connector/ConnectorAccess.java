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
    CALENDAR_EVENTS
}
