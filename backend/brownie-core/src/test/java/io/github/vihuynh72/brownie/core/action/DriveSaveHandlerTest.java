package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.connector.Connection;
import io.github.vihuynh72.brownie.core.connector.ConnectionState;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What saving to Drive sends and what it takes a readback to mean: the bytes
 * sent are the bytes approved or nothing is sent; a saved file counts only
 * when everything approved reads back; a conversion is counted as one; and
 * absence means "not made" only once there has been time for it to appear.
 */
class DriveSaveHandlerTest {

    private static final byte[] BYTES = "exact approved bytes".getBytes(StandardCharsets.UTF_8);
    private static final String DOCX = SupportedMediaType.DOCX.mimeType();
    private static final Instant SENT = Instant.parse("2026-09-28T12:00:00Z");

    private final FakeDrive drive = new FakeDrive();
    private final FakeDocs docs = new FakeDocs();
    private final StoredFile stored = new StoredFile();
    private final UsableConnection connection = new UsableConnection(new Connection(5, 7, 3, ConnectorAccess.DRIVE_SAVING, "acct", null,
            List.of("scope"), ConnectionState.ACTIVE, null, null, OffsetDateTime.now(), null, null), "fresh-token");

    @Test
    void theStoredBytesMustStillBeTheApprovedOnesOrNothingIsSent() {
        stored.bytes = "different bytes of the same length!".getBytes(StandardCharsets.UTF_8);
        ActionChangedException refused = assertThrows(ActionChangedException.class, () -> handler(ActionType.DRIVE_SAVE_FILE).prepare(file(), connection));
        assertEquals(ActionFailure.CONTENT_CHANGED, refused.failure());
        assertEquals(0, drive.created.size());
    }

    @Test
    void theExactFileIsSentUnderItsReservedIdAndCountsOnlyWhenEverythingApprovedReadsBack() {
        ActionRequest action = file();
        PreparedWrite prepared = handler(ActionType.DRIVE_SAVE_FILE).prepare(action, connection);
        WriteAnswer answer = prepared.send(connection);
        assertEquals(1, drive.created.size());
        NewDriveFile sent = drive.created.getFirst();
        assertEquals("reservedId12345", sent.reservedId());
        assertNull(sent.convertTo());
        assertArrayEquals(BYTES, sent.content());

        drive.saved = saved("reservedId12345", "Minutes.docx", DOCX, false, 1, false, (long) BYTES.length, null, sha256(BYTES));
        assertEquals(ActionVerification.MATCHED, ((ActionOutcome.Done) prepared.readBack(connection, answer)).verification());

        drive.saved = saved("reservedId12345", "Minutes.docx", DOCX, false, 1, false, (long) BYTES.length, md5(BYTES), null);
        assertInstanceOf(ActionOutcome.Done.class, prepared.readBack(connection, answer), "an md5 serves when Drive gives no sha256");

        drive.saved = saved("reservedId12345", "Minutes.docx", DOCX, false, 1, false, (long) BYTES.length, null, null);
        ActionOutcome notYet = prepared.readBack(connection, answer);
        assertInstanceOf(ActionOutcome.StillUnknown.class, notYet, "no checksum yet: the file is there and its bytes can be checked later");
        assertEquals("reservedId12345", ((ActionOutcome.StillUnknown) notYet).externalId());

        for (SavedDriveFile wrong : List.of(
                saved("reservedId12345", "Minutes.docx", DOCX, false, 1, true, (long) BYTES.length, null, sha256(BYTES)),
                saved("reservedId12345", "Minutes.docx", DOCX, true, 1, false, (long) BYTES.length, null, sha256(BYTES)),
                saved("reservedId12345", "Minutes.docx", DOCX, false, 2, false, (long) BYTES.length, null, sha256(BYTES)),
                saved("reservedId12345", "Other.docx", DOCX, false, 1, false, (long) BYTES.length, null, sha256(BYTES)),
                saved("reservedId12345", "Minutes.docx", "application/pdf", false, 1, false, (long) BYTES.length, null, sha256(BYTES)),
                saved("reservedId12345", "Minutes.docx", DOCX, false, 1, false, 1L, null, sha256(BYTES)),
                saved("reservedId12345", "Minutes.docx", DOCX, false, 1, false, (long) BYTES.length, null, "f".repeat(64)))) {
            drive.saved = wrong;
            assertInstanceOf(ActionOutcome.Mismatched.class, prepared.readBack(connection, answer), wrong.toString());
        }
    }

    @Test
    void aConversionCarriesNoIdAndIsCheckedAsAConversion() {
        ActionRequest action = conversion();
        PreparedWrite prepared = handler(ActionType.DRIVE_SAVE_AS_GOOGLE_DOC).prepare(action, connection);
        prepared.send(connection);
        assertNull(drive.created.getFirst().reservedId());
        assertEquals(DriveSavePayload.GOOGLE_DOC_TYPE, drive.created.getFirst().convertTo());

        WriteAnswer made = new WriteAnswer.Applied(200, List.of(), "convertedDoc1", null, null);
        drive.saved = saved("convertedDoc1", "Minutes", DriveSavePayload.GOOGLE_DOC_TYPE, false, 1, false, null, null, null);
        docs.text = "Spring Budget Planning\nMarch 5, 2026\n";
        ActionOutcome.Done done = (ActionOutcome.Done) prepared.readBack(connection, made);
        assertEquals(ActionVerification.CONVERSION_CHECKED, done.verification());
        assertEquals(new ConversionCount(2, 2), done.conversionCount());

        docs.text = "Spring Budget Planning\n";
        done = (ActionOutcome.Done) prepared.readBack(connection, made);
        assertEquals(ActionVerification.CONVERSION_DIFFERS, done.verification());
        assertEquals(new ConversionCount(2, 1), done.conversionCount());

        assertInstanceOf(ActionOutcome.StillUnknown.class, prepared.readBack(connection, new WriteAnswer.Unknown(null, List.of())),
                "a conversion whose answer was lost has nothing to follow");
    }

    @Test
    void anAbsentFileMeansNotMadeOnlyOnceItHadTimeToAppear() {
        ActionRequest action = file();
        List<ActionAttempt> sent = List.of(new ActionAttempt(1, 90, 1, AttemptKind.EXECUTE, SENT, SENT.plusSeconds(180), SENT, SENT.plusSeconds(20),
                AttemptOutcome.UNKNOWN, 503, null, null));
        drive.saved = null;
        DriveSaveHandler handler = handler(ActionType.DRIVE_SAVE_FILE);
        assertInstanceOf(ActionOutcome.StillUnknown.class, handler.reconcile(action, connection, sent, SENT.plusSeconds(60)));
        assertInstanceOf(ActionOutcome.NotApplied.class, handler.reconcile(action, connection, sent, SENT.plus(ActionService.SETTLE_INTERVAL).plusSeconds(1)));

        drive.saved = saved("reservedId12345", "Minutes.docx", DOCX, false, 1, false, (long) BYTES.length, null, sha256(BYTES));
        assertInstanceOf(ActionOutcome.Done.class, handler.reconcile(action, connection, sent, SENT.plusSeconds(60)));
    }

    @Test
    void aRecordNamingAnythingElseThanItsPayloadSendsNothing() {
        String payload = file().payloadCanonical();
        List<ActionRequest> others = List.of(
                row(8, 3, 40, 5, 11L, 12L, payload),
                row(7, 4, 40, 5, 11L, 12L, payload),
                row(7, 3, 41, 5, 11L, 12L, payload),
                row(7, 3, 40, 6, 11L, 12L, payload),
                row(7, 3, 40, 5, 10L, 12L, payload),
                row(7, 3, 40, 5, 11L, 99L, payload),
                row(7, 3, 40, 5, null, 12L, payload));
        DriveSaveHandler handler = handler(ActionType.DRIVE_SAVE_FILE);
        for (ActionRequest other : others) {
            assertThrows(IllegalStateException.class, () -> handler.prepare(other, connection));
        }
        assertEquals(0, drive.created.size());
        handler.prepare(row(7, 3, 40, 5, 11L, 12L, payload), connection);
    }

    private static ActionRequest row(long workspaceId, long userId, long documentId, long connectionId, Long revision, Long receipt,
            String payload) {
        return new ActionRequest(90, workspaceId, userId, documentId, connectionId, ActionType.DRIVE_SAVE_FILE, payload,
                CanonicalJson.sha256Hex(payload), "b".repeat(64), revision, receipt, null, null, "reservedId12345", ActionState.EXECUTING,
                SENT, SENT.plusSeconds(1800), SENT, SENT.plusSeconds(900), 1L, SENT.plusSeconds(180), null, null, null, null, null, null,
                null, null);
    }

    @Test
    void aFileAnAnswerNamedIsNeverNotMadeOnlyDeletedSince() {
        ActionRequest action = file();
        List<ActionAttempt> named = List.of(new ActionAttempt(1, 90, 1, AttemptKind.EXECUTE, SENT, SENT.plusSeconds(180), SENT,
                SENT.plusSeconds(20), AttemptOutcome.UNKNOWN, 200, "reservedId12345", null));
        drive.saved = null;
        DriveSaveHandler handler = handler(ActionType.DRIVE_SAVE_FILE);
        assertEquals("reservedId12345", assertInstanceOf(ActionOutcome.StillUnknown.class,
                handler.reconcile(action, connection, named, SENT.plusSeconds(60))).externalId(), "not settled yet");
        ActionOutcome.Done removed = assertInstanceOf(ActionOutcome.Done.class,
                handler.reconcile(action, connection, named, SENT.plus(ActionService.SETTLE_INTERVAL).plusSeconds(1)),
                "Drive answers alike for a file never made and one deleted for good: an answer that named it settles which");
        assertEquals(ActionVerification.REMOVED_AFTERWARDS, removed.verification());
        assertEquals("reservedId12345", removed.externalId());
    }

    @Test
    void aConversionIsReconciledOnlyThroughAnIdAnAnswerNamed() {
        ActionRequest action = conversion();
        DriveSaveHandler handler = handler(ActionType.DRIVE_SAVE_AS_GOOGLE_DOC);
        List<ActionAttempt> lost = List.of(new ActionAttempt(1, 90, 1, AttemptKind.EXECUTE, SENT, SENT.plusSeconds(180), SENT, SENT.plusSeconds(20),
                AttemptOutcome.UNKNOWN, null, null, null));
        assertInstanceOf(ActionOutcome.StillUnknown.class, handler.reconcile(action, connection, lost, SENT.plusSeconds(3600)),
                "however long it has been, absence proves nothing for a conversion");
        assertTrue(drive.described.isEmpty(), "and Drive is not asked about anything");

        List<ActionAttempt> named = List.of(new ActionAttempt(1, 90, 1, AttemptKind.EXECUTE, SENT, SENT.plusSeconds(180), SENT, SENT.plusSeconds(20),
                AttemptOutcome.UNKNOWN, null, "convertedDoc1", null));
        drive.saved = saved("convertedDoc1", "Minutes", DriveSavePayload.GOOGLE_DOC_TYPE, false, 1, false, null, null, null);
        docs.text = "Spring Budget Planning March 5, 2026";
        assertInstanceOf(ActionOutcome.Done.class, handler.reconcile(action, connection, named, SENT.plusSeconds(60)));
    }

    // --- fixtures ---

    private DriveSaveHandler handler(ActionType type) {
        return new DriveSaveHandler(type, drive, docs, stored);
    }

    private static ActionRequest file() {
        DriveSavePayload payload = new DriveSavePayload(ActionType.DRIVE_SAVE_FILE, "n", 3, 7, 40, 11, "Minutes", 5, null, 12, 13, "DOCX",
                "Minutes.docx", DOCX, BYTES.length, sha256(BYTES), md5(BYTES), List.of());
        return action(ActionType.DRIVE_SAVE_FILE, payload.canonical(), "reservedId12345");
    }

    private static ActionRequest conversion() {
        DriveSavePayload payload = new DriveSavePayload(ActionType.DRIVE_SAVE_AS_GOOGLE_DOC, "n", 3, 7, 40, 11, "Minutes", 5, null, 12, 13,
                "DOCX", "Minutes", DOCX, BYTES.length, sha256(BYTES), md5(BYTES), List.of("Spring Budget Planning", "March 5, 2026"));
        return action(ActionType.DRIVE_SAVE_AS_GOOGLE_DOC, payload.canonical(), null);
    }

    private static ActionRequest action(ActionType type, String payload, String providerKey) {
        return new ActionRequest(90, 7, 3, 40, 5, type, payload, CanonicalJson.sha256Hex(payload), "b".repeat(64), 11L, 12L, null, null,
                providerKey, ActionState.EXECUTING, SENT, SENT.plusSeconds(1800), SENT, SENT.plusSeconds(900), 1L, SENT.plusSeconds(180),
                null, null, null, null, null, null, null, null);
    }

    private static SavedDriveFile saved(String id, String name, String mimeType, boolean trashed, int parents, boolean shared, Long size,
            String md5, String sha256) {
        return new SavedDriveFile(id, name, mimeType, trashed, parents, shared, size, md5, sha256, "https://drive.google.com/file/d/" + id);
    }

    private static String sha256(byte[] bytes) {
        return digest("SHA-256", bytes);
    }

    private static String md5(byte[] bytes) {
        return digest("MD5", bytes);
    }

    private static String digest(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class FakeDrive implements DriveFileWriter {

        final List<NewDriveFile> created = new ArrayList<>();
        final List<String> described = new ArrayList<>();
        SavedDriveFile saved;

        @Override
        public String reserveFileId(String accessToken) {
            throw new AssertionError("not reserved here");
        }

        @Override
        public WriteAnswer createFile(String accessToken, NewDriveFile file) {
            created.add(file);
            return new WriteAnswer.Applied(200, List.of(), file.reservedId(), null, null);
        }

        @Override
        public Optional<SavedDriveFile> describeSavedFile(String accessToken, String fileId) {
            described.add(fileId);
            return saved != null && saved.id().equals(fileId) ? Optional.of(saved) : Optional.empty();
        }
    }

    private static final class FakeDocs implements GoogleDocs {

        String text = "";

        @Override
        public Optional<GoogleDocContent> read(String accessToken, String documentId) {
            return Optional.of(new GoogleDocContent(documentId, "rev", "Minutes", text, text.length() + 1));
        }

        @Override
        public WriteAnswer append(String accessToken, String documentId, String text, String requiredRevisionId) {
            throw new AssertionError("Saving a file never adds to a Google Doc.");
        }
    }

    /** Serves one stored file, whatever bytes a test puts in it. */
    private static final class StoredFile extends ArtifactService {

        byte[] bytes = BYTES;

        StoredFile() {
            super(null, null, null, 10_000_000, null);
        }

        @Override
        public ReadableArtifact openContent(long workspaceId, long userId, long artifactId) {
            Artifact artifact = new Artifact(artifactId, workspaceId, "blob", ArtifactStatus.READY, (long) bytes.length, "x",
                    SupportedMediaType.DOCX, "Minutes.docx", null, OffsetDateTime.now(), OffsetDateTime.now());
            return new ReadableArtifact(artifact, new ByteArrayInputStream(bytes));
        }
    }
}
