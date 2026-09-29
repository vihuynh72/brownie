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
 * <p>Saving to Drive asks for the same {@code drive.file}: it lets Brownie
 * create files, and reach only the files it created or was given. It is a
 * consent of its own all the same, so that a person who let Brownie read
 * picked files has never, by that, let it write; and Brownie's rules (a new
 * file only on approval, text added only to a Google Doc it saved) are its
 * own, kept in its code and its database.
 *
 * <p>Calendar uses the narrowest scope that reads events on calendars the
 * person owns, which includes their main calendar, plus the sign-in scopes
 * that say which account agreed. Google reports {@code email} back under its
 * long name, so only {@code openid} is required of the sign-in pair.
 *
 * <p>Adding events asks for {@code calendar.events.owned}, the narrowest
 * scope that creates events on calendars the person owns, with the same
 * sign-in pair. Google's wording for it also covers changing and deleting
 * those events; Brownie only adds a new event the person approved, and reads
 * back only that event, which is its own rule, not the permission's.
 */
public final class GoogleScopes {

    public static final String DRIVE_FILE = "https://www.googleapis.com/auth/drive.file";
    public static final String CALENDAR_OWNED_EVENTS_READ = "https://www.googleapis.com/auth/calendar.events.owned.readonly";
    public static final String CALENDAR_OWNED_EVENTS = "https://www.googleapis.com/auth/calendar.events.owned";
    static final String OPENID = "openid";
    static final String EMAIL = "email";

    private GoogleScopes() {
    }

    /** What the consent screen asks for. */
    public static List<String> requested(ConnectorAccess access) {
        return switch (access) {
            case DRIVE_FILES, DRIVE_SAVING -> List.of(DRIVE_FILE);
            case CALENDAR_EVENTS -> List.of(OPENID, EMAIL, CALENDAR_OWNED_EVENTS_READ);
            case CALENDAR_EVENT_CREATION -> List.of(OPENID, EMAIL, CALENDAR_OWNED_EVENTS);
        };
    }

    /** What a completed consent must have granted for the access to be usable. */
    public static Set<String> required(ConnectorAccess access) {
        return switch (access) {
            case DRIVE_FILES, DRIVE_SAVING -> Set.of(DRIVE_FILE);
            case CALENDAR_EVENTS -> Set.of(OPENID, CALENDAR_OWNED_EVENTS_READ);
            case CALENDAR_EVENT_CREATION -> Set.of(OPENID, CALENDAR_OWNED_EVENTS);
        };
    }
}
