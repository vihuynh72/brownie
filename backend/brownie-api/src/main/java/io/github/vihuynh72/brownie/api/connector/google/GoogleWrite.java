package io.github.vihuynh72.brownie.api.connector.google;

import java.util.Map;

/**
 * Every request that can change something in a person's Google account, and
 * nothing else: which API, which exact path, and the only query parameters it
 * carries. A writer names one of these and supplies the path's identifier
 * and the body; the address itself is built here, so no caller can send a
 * write anywhere else or with a parameter Brownie has not chosen (sharing a
 * file, inviting guests, notifying anyone).
 */
enum GoogleWrite {

    /**
     * One new file in the person's Drive, uploaded in one request with its
     * description first. No parent is named, so it lands at the top of My
     * Drive; {@code ignoreDefaultVisibility} because an organisation can make
     * uploads visible to everyone in it by default, and a file Brownie saves
     * is shared with nobody.
     */
    DRIVE_CREATE_FILE(Api.GOOGLE_APIS, "/upload/drive/v3/files", Map.of(
            "uploadType", "multipart",
            "ignoreDefaultVisibility", "true",
            "fields", "id,name,mimeType,webViewLink")),

    /** One new event on the person's own main calendar, telling nobody about it. */
    CALENDAR_INSERT_EVENT(Api.GOOGLE_APIS, "/calendar/v3/calendars/primary/events", Map.of(
            "sendUpdates", "none",
            "conferenceDataVersion", "0",
            "supportsAttachments", "false")),

    /** A change to one Google Doc, which Docs applies only if the Doc is still at the revision the change names. */
    DOCS_BATCH_UPDATE(Api.GOOGLE_DOCS, "/v1/documents/{documentId}:batchUpdate", Map.of());

    enum Api {
        GOOGLE_APIS,
        GOOGLE_DOCS
    }

    private final Api api;
    private final String pathTemplate;
    private final Map<String, String> query;

    GoogleWrite(Api api, String pathTemplate, Map<String, String> query) {
        this.api = api;
        this.pathTemplate = pathTemplate;
        this.query = Map.copyOf(query);
    }

    Api api() {
        return api;
    }

    String pathTemplate() {
        return pathTemplate;
    }

    Map<String, String> query() {
        return query;
    }
}
