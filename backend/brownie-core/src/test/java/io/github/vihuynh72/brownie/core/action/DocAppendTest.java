package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.connector.Connection;
import io.github.vihuynh72.brownie.core.connector.ConnectionState;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adding a document's text to a Google Doc Brownie saved: the text is
 * cleaned as Docs cleans it and starts a paragraph of its own; it is sent
 * against the approved revision; a plain refusal is read again to tell a
 * changed Doc from a refused request; and only one reading of the Doc counts
 * as "added", while an unchanged revision is "not added" and anything else
 * is unknown.
 */
class DocAppendTest {

    private static final String BEFORE = "Spring Budget Planning\nMarch 5, 2026\n";
    private static final String TEXT = "\nSpring Budget Planning, revised\nMarch 6, 2026";
    private static final Instant SENT = Instant.parse("2026-09-28T12:00:00Z");

    private final FakeDocs docs = new FakeDocs();
    private final FakeDrive drive = new FakeDrive();
    private final DocAppendHandler handler = new DocAppendHandler(docs, drive);
    private final UsableConnection connection = new UsableConnection(new Connection(5, 7, 3, ConnectorAccess.DRIVE_SAVING, "acct", null,
            List.of("scope"), ConnectionState.ACTIVE, null, null, OffsetDateTime.now(), null, null), "fresh-token");

    static DocAppendPayload payload() {
        return new DocAppendPayload("nonce-1", 3, 7, 42, 9, "Minutes", 5, "owner@example.org", 12, 31, "Minutes",
                "rev-approved", DocAppendText.sha256(BEFORE), BEFORE.length(), false, TEXT);
    }

    @Test
    void theTextIsCleanedAsDocsCleansItAndStartsAParagraphOfItsOwn() {
        assertEquals("\nLine one\nLine two\tend", DocAppendText.cleaned("  Line one\r\nLine two\tend\u0007\n\n"));
        assertEquals("\nA\nB\nC", DocAppendText.cleaned("A\rB\u2028C"));
        assertEquals("\nChecked: yes", DocAppendText.cleaned("\uF0FEChecked: yes"), "a symbol font's private-use box, which Docs strips");
        assertEquals("", DocAppendText.cleaned(" \n\t "));
        assertEquals("", DocAppendText.cleaned(null));
        assertThrows(IllegalArgumentException.class, () -> new DocAppendPayload("n", 3, 7, 42, 9, "M", 5, null, 12, 31, "M", "r",
                "h", 1, false, "no leading line break"));
    }

    @Test
    void thePayloadReadsBackOnlyAsItselfAndNamesTheAdditionForRecognisingItTwice() {
        String canonical = payload().canonical();
        assertTrue(canonical.contains("\"place\":\"END_OF_FIRST_TAB\""));
        assertTrue(canonical.contains("\"onlyIfUnchanged\":true"));
        assertEquals(payload(), DocAppendPayload.parse(canonical));
        assertThrows(IllegalStateException.class, () -> DocAppendPayload.parse(canonical.replace("END_OF_FIRST_TAB", "START")));
        assertThrows(IllegalStateException.class, () -> DocAppendPayload.parse(canonical.replace("\"onlyIfUnchanged\":true", "\"onlyIfUnchanged\":false")));
        assertEquals(payload().siblingKey("docId12345"), payload().siblingKey("docId12345"));
        assertNotEquals(payload().siblingKey("docId12345"), payload().siblingKey("otherDoc12345"));
    }

    @Test
    void onlyTheDocMovedOnWithEverythingBeforeIntactAndExactlyTheTextAtTheEndCountsAsAdded() {
        String after = BEFORE.substring(0, BEFORE.length() - 1) + TEXT + "\n";
        assertEquals(DocAppendText.Reading.ADDED, DocAppendText.read(payload(), doc("rev-after", after)));
        assertEquals(DocAppendText.Reading.NOT_ADDED, DocAppendText.read(payload(), doc("rev-approved", BEFORE)),
                "an unchanged revision is an unchanged Doc");
        assertEquals(DocAppendText.Reading.UNKNOWN, DocAppendText.read(payload(), doc("rev-after", BEFORE)),
                "the Doc changed some other way");
        assertEquals(DocAppendText.Reading.UNKNOWN, DocAppendText.read(payload(), doc("rev-after", "Edited meanwhile\n" + after)),
                "the text is at the end, but what was before it changed");
        String twice = BEFORE.substring(0, BEFORE.length() - 1) + TEXT + TEXT + "\n";
        assertEquals(DocAppendText.Reading.UNKNOWN, DocAppendText.read(payload(), doc("rev-after", twice)),
                "the text twice is not the addition approved");
    }

    @Test
    void theAdditionIsSentAgainstTheApprovedRevisionAndReadBackBeforeItCounts() {
        ActionRequest action = action();
        docs.content = doc("rev-approved", BEFORE);
        PreparedWrite prepared = handler.prepare(action, connection);
        WriteAnswer answer = prepared.send(connection);
        assertEquals(1, docs.appended.size());
        assertEquals(List.of("docId12345", TEXT, "rev-approved"), docs.appended.getFirst());

        docs.content = doc("rev-after", BEFORE.substring(0, BEFORE.length() - 1) + TEXT + "\n");
        ActionOutcome.Done done = assertInstanceOf(ActionOutcome.Done.class, prepared.readBack(connection, answer));
        assertEquals("docId12345", done.externalId());
        assertEquals("https://docs.google.com/document/d/docId12345/edit", done.link());

        docs.content = doc("rev-approved", BEFORE);
        assertInstanceOf(ActionOutcome.StillUnknown.class, prepared.readBack(connection, answer),
                "Google said it applied it: an unchanged Doc a moment later is Google being slow, not \"not added\"");
        List<ActionAttempt> sent = List.of(new ActionAttempt(1, 95, 1, AttemptKind.EXECUTE, SENT, SENT.plusSeconds(180), SENT, null,
                AttemptOutcome.UNKNOWN, null, null, null));
        assertInstanceOf(ActionOutcome.StillUnknown.class, handler.reconcile(action, connection, sent, SENT.plusSeconds(60)),
                "right after a send, a request whose answer was lost may still be applied");
        assertInstanceOf(ActionOutcome.NotApplied.class, handler.reconcile(action, connection, sent, SENT.plusSeconds(360)),
                "well after the send and still at the approved revision: nothing was added, and sending again is safe");
        docs.content = doc("rev-other", "Rewritten by the person\n");
        assertInstanceOf(ActionOutcome.StillUnknown.class, handler.reconcile(action, connection, List.of(), SENT));
        docs.content = null;
        assertInstanceOf(ActionOutcome.StillUnknown.class, handler.reconcile(action, connection, List.of(), SENT));
    }

    @Test
    void aPlainRefusalIsReadAgainToTellAChangedDocFromARefusedRequest() {
        ActionRequest action = action();
        docs.answer = new WriteAnswer.NotAppliedFinal(400, List.of("badRequest"), ActionFailure.PROVIDER_REFUSED);

        docs.content = doc("rev-approved", BEFORE);
        PreparedWrite racing = handler.prepare(action, connection);
        docs.content = doc("rev-moved", "Edited between the check and the send\n");
        WriteAnswer changed = racing.send(connection);
        assertEquals(ActionFailure.TARGET_CHANGED, assertInstanceOf(WriteAnswer.NotAppliedFinal.class, changed).failure());

        docs.content = doc("rev-approved", BEFORE);
        PreparedWrite overtaken = handler.prepare(action, connection);
        docs.content = doc("rev-after", BEFORE.substring(0, BEFORE.length() - 1) + TEXT + "\n");
        WriteAnswer landedLate = overtaken.send(connection);
        assertInstanceOf(WriteAnswer.Exists.class, landedLate,
                "an earlier request of this addition landed first: the refusal is this text already there, not a Doc that changed");
        assertInstanceOf(ActionOutcome.Done.class, overtaken.readBack(connection, landedLate));

        docs.content = doc("rev-approved", BEFORE);
        WriteAnswer refused = handler.prepare(action, connection).send(connection);
        assertEquals(ActionFailure.PROVIDER_REFUSED, assertInstanceOf(WriteAnswer.NotAppliedFinal.class, refused).failure());

        PreparedWrite unreadable = handler.prepare(action, connection);
        docs.failReads = true;
        WriteAnswer unread = unreadable.send(connection);
        assertEquals(ActionFailure.PROVIDER_REFUSED, assertInstanceOf(WriteAnswer.NotAppliedFinal.class, unread).failure());

        docs.answer = new WriteAnswer.NotAppliedFinal(403, List.of("forbidden"), ActionFailure.PERMISSION_REFUSED);
        docs.failReads = false;
        PreparedWrite forbiddenWrite = handler.prepare(action, connection);
        docs.reads = 0;
        WriteAnswer forbidden = forbiddenWrite.send(connection);
        assertEquals(ActionFailure.PERMISSION_REFUSED, assertInstanceOf(WriteAnswer.NotAppliedFinal.class, forbidden).failure());
        assertEquals(0, docs.reads, "only a plain refusal is read again");
    }

    @Test
    void aDocChangedInAnyWayTheApprovalCoveredEndsTheAdditionBeforeAnythingIsSent() {
        ActionRequest action = action();
        docs.content = doc("rev-approved", BEFORE);

        drive.shared = true;
        assertEquals(ActionFailure.TARGET_CHANGED, assertThrows(ActionChangedException.class, () -> handler.prepare(action, connection)).failure(),
                "shared since it was shown: the revision does not cover who can see it");
        drive.shared = false;
        drive.trashed = true;
        assertEquals(ActionFailure.TARGET_CHANGED, assertThrows(ActionChangedException.class, () -> handler.prepare(action, connection)).failure());
        drive.trashed = false;
        drive.gone = true;
        assertEquals(ActionFailure.TARGET_CHANGED, assertThrows(ActionChangedException.class, () -> handler.prepare(action, connection)).failure());
        drive.gone = false;
        docs.content = doc("rev-moved", "Edited meanwhile\n");
        assertEquals(ActionFailure.TARGET_CHANGED, assertThrows(ActionChangedException.class, () -> handler.prepare(action, connection)).failure());
        assertEquals(0, docs.appended.size(), "nothing sent");

        ActionRequest elsewhere = new ActionRequest(95, 7, 3, 42, 5, ActionType.GOOGLE_DOC_APPEND, action.payloadCanonical(),
                action.payloadHash(), action.siblingKey(), 9L, 12L, 99L, "docId12345", null, ActionState.EXECUTING, SENT, SENT.plusSeconds(1800),
                SENT, SENT.plusSeconds(900), 1L, SENT.plusSeconds(180), null, null, null, null, null, null, null, null);
        assertThrows(IllegalStateException.class, () -> handler.prepare(elsewhere, connection), "a record naming another save than the payload");
    }

    // --- fixtures ---

    private static GoogleDocContent doc(String revision, String text) {
        return new GoogleDocContent("docId12345", revision, "Minutes", text, text.length() + 1);
    }

    private static ActionRequest action() {
        String canonical = payload().canonical();
        return new ActionRequest(95, 7, 3, 42, 5, ActionType.GOOGLE_DOC_APPEND, canonical, CanonicalJson.sha256Hex(canonical),
                payload().siblingKey("docId12345"), 9L, 12L, 31L, "docId12345", null, ActionState.EXECUTING, SENT, SENT.plusSeconds(1800),
                SENT, SENT.plusSeconds(900), 1L, SENT.plusSeconds(180), null, null, null, null, null, null, null, null);
    }

    private static final class FakeDrive implements DriveFileWriter {
        boolean shared;
        boolean trashed;
        boolean gone;

        @Override
        public String reserveFileId(String accessToken) {
            throw new AssertionError("An addition reserves nothing.");
        }

        @Override
        public WriteAnswer createFile(String accessToken, NewDriveFile file) {
            throw new AssertionError("An addition creates no file.");
        }

        @Override
        public Optional<SavedDriveFile> describeSavedFile(String accessToken, String fileId) {
            return gone ? Optional.empty() : Optional.of(new SavedDriveFile(fileId, "Minutes", "application/vnd.google-apps.document",
                    trashed, 1, shared, null, null, null, null));
        }
    }

    private static final class FakeDocs implements GoogleDocs {
        final List<List<String>> appended = new ArrayList<>();
        WriteAnswer answer = new WriteAnswer.Applied(200, List.of(), "docId12345", null, "rev-after");
        GoogleDocContent content;
        boolean failReads;
        int reads;

        @Override
        public Optional<GoogleDocContent> read(String accessToken, String documentId) {
            reads++;
            if (failReads) {
                throw new IllegalStateException("Google could not be reached.");
            }
            return Optional.ofNullable(content);
        }

        @Override
        public WriteAnswer append(String accessToken, String documentId, String text, String requiredRevisionId) {
            appended.add(List.of(documentId, text, requiredRevisionId));
            return answer;
        }
    }
}
