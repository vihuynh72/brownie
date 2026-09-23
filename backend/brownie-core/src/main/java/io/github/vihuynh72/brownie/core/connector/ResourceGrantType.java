package io.github.vihuynh72.brownie.core.connector;

/** What a person chose for Brownie to read through a connection. */
public enum ResourceGrantType {
    /** One file they picked in Google Drive. */
    DRIVE_FILE,
    /** Their own calendar, whose events Brownie reads only in a window they ask for. */
    CALENDAR
}
