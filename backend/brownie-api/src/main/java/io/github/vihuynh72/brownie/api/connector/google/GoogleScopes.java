package io.github.vihuynh72.brownie.api.connector.google;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;

import java.util.List;
import java.util.Set;

/**
 * The permissions Brownie asks Google for, one kind of access at a time, and
 * the ones a consent must actually include for that access to work.
 *
 * <p>Drive uses {@code drive.file}, which reaches only files the person picks
 * for Brownie and nothing else in their Drive. Google has no read-only form of
 * it: the permission itself would allow changing those files. Brownie never
 * does, and that is Brownie's own rule, kept in its code and in its database,
 * not something the permission guarantees. Google's file picker allows no
 * other scope beside it, so the account a Drive connection belongs to is
 * learned from Drive itself rather than from a sign-in scope.
 *
 * <p>Calendar uses the narrowest scope that reads events on calendars the
 * person owns, which includes their main calendar, plus the sign-in scopes
 * that say which account agreed. Google reports {@code email} back under its
 * long name, so only {@code openid} is required of the sign-in pair.
 */
public final class GoogleScopes {

    public static final String DRIVE_FILE = "https://www.googleapis.com/auth/drive.file";
    public static final String CALENDAR_OWNED_EVENTS_READ = "https://www.googleapis.com/auth/calendar.events.owned.readonly";
    static final String OPENID = "openid";
    static final String EMAIL = "email";

    private GoogleScopes() {
    }

    /** What the consent screen asks for. */
    public static List<String> requested(ConnectorAccess access) {
        return switch (access) {
            case DRIVE_FILES -> List.of(DRIVE_FILE);
            case CALENDAR_EVENTS -> List.of(OPENID, EMAIL, CALENDAR_OWNED_EVENTS_READ);
        };
    }

    /** What a completed consent must have granted for the access to be usable. */
    public static Set<String> required(ConnectorAccess access) {
        return switch (access) {
            case DRIVE_FILES -> Set.of(DRIVE_FILE);
            case CALENDAR_EVENTS -> Set.of(OPENID, CALENDAR_OWNED_EVENTS_READ);
        };
    }
}
