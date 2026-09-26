package io.github.vihuynh72.brownie.core.connector;

import java.util.Objects;

/**
 * Something the person chose that Brownie cannot read now. {@code reason}
 * says which, and {@code access} whether it is a calendar event or a Drive
 * file, so that a page can say it plainly:
 * <ul>
 * <li>{@code GONE}: the provider says it no longer exists, or never did, or
 * (for a file) that this person cannot open it; it does not tell those
 * apart.</li>
 * <li>{@code CANCELLED}: an event that still exists but was called off.</li>
 * <li>{@code ACCESS_LOST}: a file this app has not been given access to.</li>
 * <li>{@code DOWNLOAD_RESTRICTED}: a file whose owner does not allow it to be
 * downloaded, copied or exported by this person.</li>
 * <li>{@code TRASHED}: a file in the trash.</li>
 * <li>{@code CHANGED_DURING_COPY}: a file that changed while it was being
 * copied, so the copy would match no version of it.</li>
 * </ul>
 */
public class ConnectorResourceUnavailableException extends RuntimeException {

    public enum Reason { GONE, CANCELLED, ACCESS_LOST, DOWNLOAD_RESTRICTED, TRASHED, CHANGED_DURING_COPY }

    private final Reason reason;
    private final ConnectorAccess access;

    public ConnectorResourceUnavailableException(Reason reason, ConnectorAccess access) {
        super("Brownie cannot read this from Google (" + reason + ").");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.access = Objects.requireNonNull(access, "access");
    }

    public Reason reason() {
        return reason;
    }

    public ConnectorAccess access() {
        return access;
    }
}
