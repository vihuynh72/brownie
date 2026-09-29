package io.github.vihuynh72.brownie.core.action;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Drive payload has one written form, reads back only from exactly that form, and names nothing Brownie never proposes. */
class DriveSavePayloadTest {

    @Test
    void aPayloadIsWrittenOneWayAndReadsBackToItself() {
        DriveSavePayload payload = file("nonce-1");
        String canonical = payload.canonical();
        assertTrue(canonical.startsWith("{\"account\":{\"connection\":5,\"email\":\"a@example.org\"},\"content\":{"), canonical);
        assertTrue(canonical.contains("\"target\":{\"place\":\"MY_DRIVE_TOP\",\"sharing\":\"NOBODY\"}"), canonical);
        assertTrue(canonical.contains("\"effect\":{\"conversion\":\"NONE\",\"creates\":\"NEW_FILE\"}"), canonical);
        assertEquals(payload, DriveSavePayload.parse(canonical, ActionType.DRIVE_SAVE_FILE));

        DriveSavePayload conversion = new DriveSavePayload(ActionType.DRIVE_SAVE_AS_GOOGLE_DOC, "nonce-2", 3, 7, 40, 11, "Minutes", 5, null,
                12, 13, "DOCX", "Minutes", "application/x", 100, "a".repeat(64), "b".repeat(32), List.of("Spring Budget Planning", "March 5, 2026"));
        assertEquals(conversion, DriveSavePayload.parse(conversion.canonical(), ActionType.DRIVE_SAVE_AS_GOOGLE_DOC));
        assertTrue(conversion.canonical().contains("\"check\":{\"values\":[\"Spring Budget Planning\",\"March 5, 2026\"]}"));
    }

    @Test
    void everyProposalHasItsOwnHashButTheSameSaveHasTheSameKey() {
        assertNotEquals(CanonicalJson.sha256Hex(file("nonce-1").canonical()), CanonicalJson.sha256Hex(file("nonce-2").canonical()));
        assertEquals(file("nonce-1").siblingKey(), file("nonce-2").siblingKey());
    }

    @Test
    void aStoredPayloadThatWasChangedOrIsOfAnotherKindIsRefused() {
        String canonical = file("nonce-1").canonical();
        assertThrows(IllegalStateException.class, () -> DriveSavePayload.parse(canonical, ActionType.DRIVE_SAVE_AS_GOOGLE_DOC));
        assertThrows(IllegalStateException.class, () -> DriveSavePayload.parse(
                canonical.replace("\"sharing\":\"NOBODY\"", "\"sharing\":\"ANYONE\""), ActionType.DRIVE_SAVE_FILE));
        assertThrows(IllegalStateException.class, () -> DriveSavePayload.parse(
                canonical.replace("\"place\":\"MY_DRIVE_TOP\"", "\"place\":\"SHARED_FOLDER\""), ActionType.DRIVE_SAVE_FILE));
        assertThrows(IllegalStateException.class, () -> DriveSavePayload.parse(
                canonical.replace("\"bytes\":100", "\"bytes\":\"100\""), ActionType.DRIVE_SAVE_FILE));
        // A member Brownie never writes, even in canonical order, is not part of any payload.
        assertThrows(IllegalStateException.class, () -> DriveSavePayload.parse(
                canonical.replace("\"account\":{", "\"a\":1,\"account\":{"), ActionType.DRIVE_SAVE_FILE));
        assertThrows(IllegalStateException.class, () -> DriveSavePayload.parse(
                canonical.replace("\"schema\":\"brownie.action/1\"", "\"schema\":\"brownie.action/2\""), ActionType.DRIVE_SAVE_FILE));
    }

    private static DriveSavePayload file(String nonce) {
        return new DriveSavePayload(ActionType.DRIVE_SAVE_FILE, nonce, 3, 7, 40, 11, "Minutes", 5, "a@example.org",
                12, 13, "DOCX", "Minutes.docx", "application/x", 100, "a".repeat(64), "b".repeat(32), List.of());
    }
}
