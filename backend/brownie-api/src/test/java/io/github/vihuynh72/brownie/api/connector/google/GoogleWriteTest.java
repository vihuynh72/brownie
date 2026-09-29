package io.github.vihuynh72.brownie.api.connector.google;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole list of changes Brownie can send to Google, read as a claim: a
 * new Drive file shared with nobody, a new event on the person's own main
 * calendar telling nobody, and text added to a Google Doc only at the
 * revision approved. Nothing that sends mail, invites anyone, shares a file,
 * deletes anything or reaches an address chosen at run time.
 */
class GoogleWriteTest {

    @Test
    void theOnlyChangesAreTheThreeApprovedKindsEachWithOnlyItsOwnParameters() {
        assertThat(EnumSet.allOf(GoogleWrite.class))
                .containsExactlyInAnyOrder(GoogleWrite.DRIVE_CREATE_FILE, GoogleWrite.CALENDAR_INSERT_EVENT, GoogleWrite.DOCS_BATCH_UPDATE);

        assertThat(GoogleWrite.DRIVE_CREATE_FILE.pathTemplate()).isEqualTo("/upload/drive/v3/files");
        assertThat(GoogleWrite.DRIVE_CREATE_FILE.query()).containsEntry("ignoreDefaultVisibility", "true").doesNotContainKey("addParents");

        assertThat(GoogleWrite.CALENDAR_INSERT_EVENT.pathTemplate()).isEqualTo("/calendar/v3/calendars/primary/events");
        assertThat(GoogleWrite.CALENDAR_INSERT_EVENT.query()).isEqualTo(Map.of(
                "sendUpdates", "none", "conferenceDataVersion", "0", "supportsAttachments", "false"));

        assertThat(GoogleWrite.DOCS_BATCH_UPDATE.pathTemplate()).isEqualTo("/v1/documents/{documentId}:batchUpdate");
        assertThat(GoogleWrite.DOCS_BATCH_UPDATE.api()).isEqualTo(GoogleWrite.Api.GOOGLE_DOCS);

        for (GoogleWrite write : GoogleWrite.values()) {
            String path = write.pathTemplate().toLowerCase(java.util.Locale.ROOT);
            assertThat(path).as(write.name())
                    .doesNotContain("gmail").doesNotContain("/send").doesNotContain("permissions").doesNotContain("/acl")
                    .doesNotContain("delete").doesNotContain("/copy").doesNotContain("attendees");
        }
    }
}
