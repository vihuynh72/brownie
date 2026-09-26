package io.github.vihuynh72.brownie.core.connector;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.ArtifactTooLargeException;
import io.github.vihuynh72.brownie.core.artifact.UnsupportedArtifactTypeException;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.source.AttachedSource;
import io.github.vihuynh72.brownie.core.source.DocumentSourceService;
import io.github.vihuynh72.brownie.core.source.SourceKind;
import io.github.vihuynh72.brownie.core.source.SourceOrigin;
import io.github.vihuynh72.brownie.core.source.SourceSnapshot;
import io.github.vihuynh72.brownie.core.source.SourceSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything the Drive import decides before anything is stored: which picked
 * ids are ever sent to Drive, which files become choices, and, for a copy,
 * what is refused before the content is read, what is refused after, what is
 * asked of Google at all, and when a choice is taken off the person's list.
 * Drive is a scripted stand-in that records every call; Google's token
 * endpoint is one that counts refreshes; storage is not reached, because a
 * copy that passes every check stops at the upload, which is where these
 * tests end. Linking, the snapshot and races are proven against a real
 * database in the API's integration test.
 */
class DriveImportServiceTest {

    private static final long WORKSPACE = 7;
    private static final long PERSON = 3;
    private static final long DOCUMENT = 40;
    private static final long DRIVE_CONNECTION = 50;
    private static final int LIMIT = 64;
    private static final String DRIVE_SCOPE = "drive-files-scope";

    private final ScriptedDrive drive = new ScriptedDrive();
    private final CountingProvider provider = new CountingProvider();
    private final OneConnection connections = new OneConnection();
    private final InMemoryGrants grants = new InMemoryGrants();
    private final Snapshots snapshots = new Snapshots();
    private final StopAtUpload artifacts = new StopAtUpload();
    private final RecordingLinks links = new RecordingLinks();
    private final Documents documents = new Documents();
    private final DriveImportService service = new DriveImportService(
            new ConnectorService(connections, new PlainCipher(), provider),
            grants, drive, artifacts, null, snapshots, links, null, documents, LIMIT);

    // ---- picks

    @Test
    void onlyTheFirstTenDistinctWellFormedIdsEverReachDrive() {
        List<String> ids = new ArrayList<>(List.of("../other", "has space", "", "a".repeat(257), "id?x=1", "dup_1", "dup_1"));
        IntStream.range(0, 12).forEach(i -> ids.add("file-" + i));
        ids.forEach(id -> drive.put(doc(id, "Doc " + id)));
        ids.add(null);

        DriveImportService.PickOutcome outcome = service.recordPicks(WORKSPACE, PERSON, usableDrive(), ids);

        List<String> asked = drive.calls.stream().map(Call::fileId).toList();
        assertEquals(List.of("dup_1", "file-0", "file-1", "file-2", "file-3", "file-4", "file-5", "file-6", "file-7", "file-8"), asked,
                "malformed ids are never sent, a repeat is sent once, and nothing past the tenth");
        assertTrue(drive.calls.stream().allMatch(call -> call.method().equals("describeFile")), "a pick reads no content");
        assertEquals(new DriveImportService.PickOutcome(10, 0, 0, 0, 3), outcome);
        assertEquals(10, grants.rows.size());
        assertTrue(grants.rows.values().stream().allMatch(g -> g.type() == ResourceGrantType.DRIVE_FILE && g.connectionId() == DRIVE_CONNECTION));
        assertEquals(0, provider.refreshes, "the pick's own access token is used; nothing else is asked of Google");
    }

    @Test
    void onlyADocOrATextFileThatDriveDescribesAsTheOneAskedForBecomesAChoice() {
        drive.put(doc("docA", "Minutes"));
        drive.put(text("txtB", "notes.txt", 5L));
        drive.put(file("pdfC", "report.pdf", "application/pdf"));
        drive.put(file("shortcutD", "Minutes (shortcut)", "application/vnd.google-apps.shortcut"));
        drive.put(file("folderE", "Folder", "application/vnd.google-apps.folder"));
        drive.put("askedF", doc("otherG", "Someone else's"));
        drive.refuse("describeFile", "goneH", unavailable(ConnectorResourceUnavailableException.Reason.GONE));

        DriveImportService.PickOutcome outcome = service.recordPicks(WORKSPACE, PERSON, usableDrive(),
                List.of("docA", "txtB", "pdfC", "shortcutD", "folderE", "askedF", "goneH"));

        assertEquals(new DriveImportService.PickOutcome(2, 3, 2, 0, 0), outcome);
        assertEquals(Set.of("docA", "txtB"), grants.externalIds());
    }

    @Test
    void aDriveThatStopsAnsweringEndsThePickAndTheRestAreUnchecked() {
        List.of("f1", "f2", "f3", "f4").forEach(id -> drive.put(doc(id, id)));
        drive.refuse("describeFile", "f2", new ProviderUnavailableException("timeout"));

        DriveImportService.PickOutcome outcome = service.recordPicks(WORKSPACE, PERSON, usableDrive(), List.of("f1", "f2", "f3", "f4"));

        assertEquals(new DriveImportService.PickOutcome(1, 0, 0, 3, 0), outcome);
        assertEquals(List.of("f1", "f2"), drive.calls.stream().map(Call::fileId).toList(), "nothing is asked after Drive stopped answering");
    }

    @Test
    void aTokenRefusedDuringAPickAsksToConnectAgainKeepsWhatWasAddedAndSaysWhyTheRestWereNot() {
        List.of("f1", "f2", "f3").forEach(id -> drive.put(doc(id, id)));
        drive.refuse("describeFile", "f2", new ProviderTokenRejectedException("invalid"));

        DriveImportService.PickOutcome outcome = service.recordPicks(WORKSPACE, PERSON, usableDrive(), List.of("f1", "f2", "f3"));

        assertEquals(new DriveImportService.PickOutcome(1, 0, 0, 2, 0, DriveImportService.PickStop.TOKEN_REFUSED), outcome);
        assertEquals(List.of(ReconnectReason.TOKEN_REJECTED), connections.reconnects, "recorded once, so the next use asks to connect again");
        assertEquals(Set.of("f1"), grants.externalIds());
        assertEquals(List.of("f1", "f2"), drive.calls.stream().map(Call::fileId).toList(), "nothing is asked after the refusal");
    }

    @Test
    void aRefusalThatWouldRefuseEveryFileStopsThePickAndIsNamed() {
        List.of("f1", "f2", "f3").forEach(id -> drive.put(doc(id, id)));
        drive.refuse("describeFile", "f1", new ConnectorBlockedByOrganizationException(ConnectorAccess.DRIVE_FILES));
        assertEquals(new DriveImportService.PickOutcome(0, 0, 0, 3, 0, DriveImportService.PickStop.BLOCKED_BY_ORGANIZATION),
                service.recordPicks(WORKSPACE, PERSON, usableDrive(), List.of("f1", "f2", "f3")));

        drive.stopRefusing();
        drive.refuse("describeFile", "f2", new ProviderMisconfiguredException("Drive API not enabled"));
        assertEquals(new DriveImportService.PickOutcome(1, 0, 0, 2, 0, DriveImportService.PickStop.NOT_CONFIGURED),
                service.recordPicks(WORKSPACE, PERSON, usableDrive(), List.of("f1", "f2", "f3")));

        drive.stopRefusing();
        drive.refuse("describeFile", "f3", new ConnectorNotConfiguredException());
        assertEquals(new DriveImportService.PickOutcome(2, 0, 0, 1, 0, DriveImportService.PickStop.NOT_CONFIGURED),
                service.recordPicks(WORKSPACE, PERSON, usableDrive(), List.of("f1", "f2", "f3")));
        assertTrue(connections.reconnects.isEmpty(), "none of these is the person's doing: the connection stays as it is");
    }

    @Test
    void aNameIsKeptOnOneLineWithoutReorderingCharactersAndCut() {
        drive.put(doc("n1", "Plan\u202Etxt.exe\u202C\nfor\tQ3"));
        drive.put(doc("n2", "\u0000\u202E"));
        drive.put(doc("n3", "\uD83D\uDE00".repeat(300)));

        service.recordPicks(WORKSPACE, PERSON, usableDrive(), List.of("n1", "n2", "n3"));

        assertEquals("Plantxt.exe for\tQ3", grants.byExternalId("n1").displayName());
        assertNull(grants.byExternalId("n2").displayName(), "nothing printable is no name, not an empty one");
        String cut = grants.byExternalId("n3").displayName();
        assertEquals(255, cut.codePointCount(0, cut.length()));
    }

    @Test
    void picksGoOnlyThroughADriveConnection() {
        Connection calendar = connection(51, ConnectorAccess.CALENDAR_EVENTS, ConnectionState.ACTIVE);
        assertThrows(IllegalArgumentException.class,
                () -> service.recordPicks(WORKSPACE, PERSON, new UsableConnection(calendar, "token"), List.of("f1")));
        assertTrue(drive.calls.isEmpty());
    }

    // ---- what is refused before anything is asked of Google

    @Test
    void aDocumentThatIsGoneOrInTheTrashAsksNothingOfGoogle() {
        long grant = pickedDoc("docA");
        documents.present = false;

        assertThrows(DocumentNotFoundException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));

        assertEquals(0, provider.refreshes, "no token refresh either");
        assertTrue(drive.calls.isEmpty());
    }

    @Test
    void aChoiceThatIsNotTheirsOrForgottenOrNotADriveFileAsksNothingOfGoogle() {
        long forgotten = pickedDoc("docA");
        grants.revoke(WORKSPACE, PERSON, forgotten, GrantRevocationReason.REMOVED);
        long calendar = grants.grant(WORKSPACE, PERSON, DRIVE_CONNECTION, ResourceGrantType.CALENDAR, "primary", "Primary calendar").id();
        long someoneElses = grants.grantFor(99, DRIVE_CONNECTION, "docB");

        for (long grantId : new long[] {forgotten, calendar, someoneElses, 12345}) {
            assertThrows(ConnectorResourceNotFoundException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grantId),
                    String.valueOf(grantId));
        }
        assertEquals(0, provider.refreshes);
        assertTrue(drive.calls.isEmpty());
    }

    @Test
    void aChoiceMadeThroughAConnectionSinceReplacedIsNotRead() {
        long grant = grants.grant(WORKSPACE, PERSON, DRIVE_CONNECTION + 1, ResourceGrantType.DRIVE_FILE, "docA", "Minutes").id();
        drive.put(doc("docA", "Minutes"));

        assertThrows(ConnectorResourceNotFoundException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));
        assertTrue(drive.calls.isEmpty());
    }

    // ---- what is refused on Drive's description, before any content is read

    @Test
    void aFileThatIsTrashedUnsupportedRestrictedOrWithoutAUsableVersionIsRefusedBeforeItsContentIsRead() {
        record Case(DriveFile file, Class<? extends RuntimeException> refusal, Object reason) {
        }
        List<Case> cases = List.of(
                new Case(new DriveFile("f", "a", DriveFile.GOOGLE_DOC, true, true, null, "1", null, null),
                        ConnectorResourceUnavailableException.class, ConnectorResourceUnavailableException.Reason.TRASHED),
                new Case(new DriveFile("f", "a", "application/pdf", false, true, 10L, "1", null, null),
                        ConnectorResourceUnsupportedException.class, ConnectorResourceUnsupportedException.Reason.TYPE),
                new Case(new DriveFile("f", "a", "application/vnd.google-apps.shortcut", false, true, null, "1", null, null),
                        ConnectorResourceUnsupportedException.class, ConnectorResourceUnsupportedException.Reason.TYPE),
                new Case(new DriveFile("f", "a", DriveFile.PLAIN_TEXT, false, false, 10L, "1", null, null),
                        ConnectorResourceUnavailableException.class, ConnectorResourceUnavailableException.Reason.DOWNLOAD_RESTRICTED),
                new Case(new DriveFile("f", "a", DriveFile.PLAIN_TEXT, false, true, 10L, " ", null, null),
                        ProviderUnavailableException.class, null),
                new Case(new DriveFile("f", "a", DriveFile.PLAIN_TEXT, false, true, 10L, "9".repeat(256), null, null),
                        ProviderUnavailableException.class, null),
                new Case(new DriveFile("f", "a", DriveFile.PLAIN_TEXT, false, true, LIMIT + 1L, "1", null, null),
                        ConnectorResourceTooLargeException.class, null),
                new Case(new DriveFile("g", "a", DriveFile.PLAIN_TEXT, false, true, 1L, "1", null, null),
                        ProviderUnavailableException.class, null));
        long grant = pickedDoc("f");
        for (Case each : cases) {
            drive.calls.clear();
            drive.put("f", each.file());
            RuntimeException refused = assertThrows(each.refusal(), () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant), each.toString());
            if (each.reason() != null) {
                assertEquals(each.reason(), refused instanceof ConnectorResourceUnavailableException u ? u.reason()
                        : ((ConnectorResourceUnsupportedException) refused).reason());
            }
            assertEquals(List.of(new Call("describeFile", "f", null)), drive.calls, "nothing but the description: " + each);
        }
        assertTrue(artifacts.fileNames.isEmpty());
        assertTrue(grants.byExternalId("f").isOpen(), "none of these takes the file off the list");
    }

    @Test
    void aDocsStatedSizeIsNotItsTextsSoItIsReadAndOnlyTheReadIsBounded() {
        drive.put(new DriveFile("docA", "Minutes", DriveFile.GOOGLE_DOC, false, true, 10_000_000L, "3", null, null), bytes("short"));
        long grant = pickedDoc("docA");

        assertThrows(ReachedUpload.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));

        assertEquals(new Call("readGoogleDocAsText", "docA", LIMIT), drive.calls.get(1), "read with Brownie's upload limit");
    }

    // ---- what is refused after the content is read

    @Test
    void aFileThatChangedDuringTheReadIsNotKept() {
        long grant = pickedDoc("docA");
        drive.put("docA", doc("docA", "Minutes", "12"), bytes("text"));
        drive.nextDescriptions("docA", doc("docA", "Minutes", "12"), doc("docA", "Minutes", "13"));

        ConnectorResourceUnavailableException refused =
                assertThrows(ConnectorResourceUnavailableException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));

        assertEquals(ConnectorResourceUnavailableException.Reason.CHANGED_DURING_COPY, refused.reason());
        assertEquals(List.of("describeFile", "readGoogleDocAsText", "describeFile"), drive.calls.stream().map(Call::method).toList());
        assertTrue(artifacts.fileNames.isEmpty());
        assertTrue(grants.byExternalId("docA").isOpen());
    }

    @Test
    void aRestrictionOrTheTrashArrivingDuringTheReadIsRespected() {
        long grant = pickedDoc("docA");
        drive.put("docA", doc("docA", "Minutes", "12"), bytes("text"));
        drive.nextDescriptions("docA", doc("docA", "Minutes", "12"),
                new DriveFile("docA", "Minutes", DriveFile.GOOGLE_DOC, false, false, null, "12", null, null));
        assertThrows(ConnectorResourceUnavailableException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));

        drive.nextDescriptions("docA", doc("docA", "Minutes", "12"),
                new DriveFile("docA", "Minutes", DriveFile.GOOGLE_DOC, true, true, null, "12", null, null));
        assertThrows(ConnectorResourceUnavailableException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));
        assertTrue(artifacts.fileNames.isEmpty());
    }

    @Test
    void textThatIsNotUtf8IsRefusedAndAByteOrderMarkIsNot() {
        long grant = pickedText("txtA");
        byte[][] notUtf8 = {
                {(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0},
                {'c', 'a', 'f', (byte) 0xE9},
                {(byte) 0xC0, (byte) 0xAF},
                {(byte) 0xED, (byte) 0xA0, (byte) 0x80},
                {'a', (byte) 0xE2, (byte) 0x82},
        };
        for (byte[] content : notUtf8) {
            drive.put(text("txtA", "notes.txt", (long) content.length), content);
            ConnectorResourceUnsupportedException refused =
                    assertThrows(ConnectorResourceUnsupportedException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));
            assertEquals(ConnectorResourceUnsupportedException.Reason.NOT_UTF8, refused.reason());
        }
        assertTrue(artifacts.fileNames.isEmpty());

        byte[] withMark = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i', '\r', '\n'};
        drive.put(text("txtA", "notes.txt", (long) withMark.length), withMark);
        assertThrows(ReachedUpload.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));
        assertArrayEquals(withMark, artifacts.received, "the bytes go to the upload exactly as read");
    }

    @Test
    void contentLongerThanTheLimitIsRefusedEvenWhenTheReaderReturnsIt() {
        long grant = pickedText("txtA");
        drive.put(text("txtA", "notes.txt", 1L), new byte[LIMIT + 1]);
        drive.ignoreLimit = true;

        assertThrows(ConnectorResourceTooLargeException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));
        assertTrue(artifacts.fileNames.isEmpty());
    }

    // ---- what takes a file off the list, and what asks to connect again

    @Test
    void googleSayingTheFileIsGoneOrNoLongerBrowniesTakesItOffTheList() {
        for (ConnectorResourceUnavailableException.Reason reason
                : List.of(ConnectorResourceUnavailableException.Reason.GONE, ConnectorResourceUnavailableException.Reason.ACCESS_LOST)) {
            for (String method : List.of("describeFile", "readGoogleDocAsText")) {
                String id = reason + "-" + method;
                long grant = pickedDoc(id);
                drive.put(doc(id, "Minutes"));
                drive.refuse(method, id, unavailable(reason));

                ConnectorResourceUnavailableException refused =
                        assertThrows(ConnectorResourceUnavailableException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));

                assertEquals(reason, refused.reason());
                assertEquals(GrantRevocationReason.PROVIDER_ACCESS_LOST, grants.byExternalId(id).revokedReason(), id);
            }
        }
    }

    @Test
    void aRestrictedDownloadIsRefusedButTheFileStaysOnTheList() {
        long grant = pickedDoc("docA");
        drive.put(doc("docA", "Minutes"));
        drive.refuse("readGoogleDocAsText", "docA", unavailable(ConnectorResourceUnavailableException.Reason.DOWNLOAD_RESTRICTED));

        assertThrows(ConnectorResourceUnavailableException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));
        assertTrue(grants.byExternalId("docA").isOpen());
    }

    @Test
    void aTokenRefusedDuringACopyAsksToConnectAgainOnce() {
        for (String method : List.of("describeFile", "readGoogleDocAsText")) {
            connections.reset();
            long grant = pickedDoc("docA");
            drive.stopRefusing();
            drive.put(doc("docA", "Minutes"));
            drive.refuse(method, "docA", new ProviderTokenRejectedException("invalid"));

            ConnectionReconnectRequiredException refused =
                    assertThrows(ConnectionReconnectRequiredException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));

            assertEquals(ReconnectReason.TOKEN_REJECTED, refused.reason(), method);
            assertEquals(List.of(ReconnectReason.TOKEN_REJECTED), connections.reconnects, method);
        }
        int refreshesBefore = provider.refreshes;
        assertThrows(ConnectionReconnectRequiredException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grants.byExternalId("docA").id()));
        assertEquals(refreshesBefore, provider.refreshes, "once recorded, the next copy asks Google nothing");
    }

    // ---- what is linked instead of copied, and what reaches the upload

    @Test
    void theVersionAlreadyCopiedIsLinkedWithoutReadingIt() {
        long grant = pickedDoc("docA");
        drive.put(doc("docA", "Minutes", "12"));
        snapshots.sameVersion = Optional.of(snapshot(900));

        DriveImportService.ImportOutcome outcome = service.importFile(WORKSPACE, PERSON, DOCUMENT, grant);

        assertEquals(false, outcome.newCopy());
        assertEquals(List.of(900L), links.attached);
        assertEquals(List.of("describeFile"), drive.calls.stream().map(Call::method).toList());
        assertEquals(List.of("docA:12"), snapshots.versionLookups);
    }

    @Test
    void aNewerVersionWithTheSameTextLinksTheLatestCopy() {
        long grant = pickedDoc("docA");
        drive.put("docA", doc("docA", "Minutes", "13"), bytes("unchanged"));
        snapshots.sameText = Optional.of(snapshot(901));

        DriveImportService.ImportOutcome outcome = service.importFile(WORKSPACE, PERSON, DOCUMENT, grant);

        assertEquals(false, outcome.newCopy());
        assertEquals(List.of(901L), links.attached);
        assertTrue(artifacts.fileNames.isEmpty());
        assertEquals(List.of("docA:" + sha256Hex("unchanged")), snapshots.contentLookups, "compared by the SHA-256 of the text read");
    }

    @Test
    void aChoiceTakenBackWhileDriveIsReadLinksNoEarlierCopyAndIsNotAskedToConnectAgain() {
        long grant = pickedDoc("docA");
        drive.put("docA", doc("docA", "Minutes", "12"), bytes("unchanged"));
        snapshots.sameVersion = Optional.of(snapshot(900));
        drive.during("describeFile", () -> grants.revoke(WORKSPACE, PERSON, grant, GrantRevocationReason.REMOVED));
        assertThrows(ConnectorResourceNotFoundException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant),
                "the same version is not linked once the choice is gone");

        long again = pickedDoc("docA");
        snapshots.sameVersion = Optional.empty();
        snapshots.sameText = Optional.of(snapshot(901));
        drive.during("readGoogleDocAsText", () -> grants.revoke(WORKSPACE, PERSON, again, GrantRevocationReason.REMOVED));
        assertThrows(ConnectorResourceNotFoundException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, again),
                "nor the same text");
        assertTrue(links.attached.isEmpty());

        // Disconnecting takes the token back at Google before the choices here: that refusal means the file is off the list.
        long third = pickedDoc("docA");
        drive.refuse("describeFile", "docA", new ProviderTokenRejectedException("revoked"));
        drive.during("describeFile", () -> grants.revoke(WORKSPACE, PERSON, third, GrantRevocationReason.DISCONNECTED));
        assertThrows(ConnectorResourceNotFoundException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, third));
        assertTrue(connections.reconnects.isEmpty(), "not asked to connect again after disconnecting");
    }

    @Test
    void aFileThatPassesEveryCheckReachesTheUploadNamedAsText() {
        long doc = pickedDoc("docA");
        drive.put(doc("docA", "Minutes / Q3"), bytes("text"));
        assertThrows(ReachedUpload.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, doc));

        long txt = pickedText("txtB");
        drive.put(text("txtB", "notes.TXT", 4L), bytes("text"));
        assertThrows(ReachedUpload.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, txt));

        long unnamed = pickedText("txtC");
        drive.put(text("txtC", null, 4L), bytes("text"));
        assertThrows(ReachedUpload.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, unnamed));

        assertEquals(List.of("Minutes - Q3.txt", "notes.txt", "Google Drive file.txt"), artifacts.fileNames);
        assertEquals(List.of("describeFile", "readTextFile", "describeFile"),
                drive.calls.stream().filter(c -> c.fileId().equals("txtC")).map(Call::method).toList(),
                "a text file is downloaded, never exported, and described again after");
    }

    @Test
    void bytesTheUploadChecksCannotClassifyAreRefusedAsAnUploadWouldBe() {
        long grant = pickedText("txtA");
        drive.put(text("txtA", "notes.txt", 5L), new byte[] {'a', 0, 'b', 0, 'c'});

        artifacts.refusal = new UnsupportedArtifactTypeException("Content does not match any supported media type or package signature.");
        ConnectorResourceRefusedException unclassified =
                assertThrows(ConnectorResourceRefusedException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));
        assertEquals("UNSUPPORTED_MEDIA_TYPE", unclassified.reason());

        artifacts.refusal = new ArtifactTooLargeException("Package expands past its limit.");
        ConnectorResourceRefusedException expanded =
                assertThrows(ConnectorResourceRefusedException.class, () -> service.importFile(WORKSPACE, PERSON, DOCUMENT, grant));
        assertEquals("DECOMPRESSION_LIMIT_EXCEEDED", expanded.reason());
    }

    // ---- forgetting

    @Test
    void forgettingTakesOnlyAnOpenDriveChoiceOfTheirsOffTheListAndOnlyOnce() {
        long grant = pickedDoc("docA");
        long calendar = grants.grant(WORKSPACE, PERSON, DRIVE_CONNECTION, ResourceGrantType.CALENDAR, "primary", "Primary calendar").id();
        long someoneElses = grants.grantFor(99, DRIVE_CONNECTION, "docB");

        ResourceGrant forgotten = service.forget(WORKSPACE, PERSON, grant);

        assertEquals(GrantRevocationReason.REMOVED, forgotten.revokedReason());
        for (long other : new long[] {grant, calendar, someoneElses}) {
            assertThrows(ConnectorResourceNotFoundException.class, () -> service.forget(WORKSPACE, PERSON, other));
        }
        assertTrue(grants.rows.get(calendar).isOpen());
        assertTrue(grants.rows.get(someoneElses).isOpen());
        assertEquals(0, provider.refreshes, "forgetting asks nothing of Google");
        assertTrue(drive.calls.isEmpty());
    }

    // ---- the small rules

    @Test
    void aLinkIsKeptOnlyWhenItOpensOnGooglesDocsOrDriveSiteOverHttps() {
        assertEquals("https://docs.google.com/document/d/abc/edit", DriveImportService.link("https://docs.google.com/document/d/abc/edit"));
        assertEquals("https://drive.google.com/file/d/abc/view", DriveImportService.link("https://drive.google.com/file/d/abc/view"));
        for (String refused : new String[] {
                null, "http://docs.google.com/document/d/abc", "https://docs.google.com.evil.example/x", "https://evil.example/docs.google.com",
                "https://user@docs.google.com/x", "https://docs.google.com:8443/x", "javascript:alert(1)", "https://docs.google.com/" + "a".repeat(2048),
                "https://docs.google.com/a b"}) {
            assertNull(DriveImportService.link(refused), String.valueOf(refused));
        }
    }

    @Test
    void strictUtf8() {
        assertTrue(DriveImportService.isUtf8(new byte[0]));
        assertTrue(DriveImportService.isUtf8("h\u00E9llo \uD83D\uDE00".getBytes(StandardCharsets.UTF_8)));
        assertEquals(false, DriveImportService.isUtf8(new byte[] {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}), "past U+10FFFF");
    }

    // ---- helpers

    private long pickedDoc(String id) {
        return grants.grant(WORKSPACE, PERSON, DRIVE_CONNECTION, ResourceGrantType.DRIVE_FILE, id, "Minutes").id();
    }

    private long pickedText(String id) {
        return grants.grant(WORKSPACE, PERSON, DRIVE_CONNECTION, ResourceGrantType.DRIVE_FILE, id, "notes.txt").id();
    }

    private UsableConnection usableDrive() {
        return new UsableConnection(connection(DRIVE_CONNECTION, ConnectorAccess.DRIVE_FILES, ConnectionState.ACTIVE), "pick-access-token");
    }

    private static Connection connection(long id, ConnectorAccess access, ConnectionState state) {
        return new Connection(id, WORKSPACE, PERSON, access, "account-a", "a@example.org", List.of(DRIVE_SCOPE), state, null, null,
                OffsetDateTime.parse("2026-09-20T10:00:00Z"), null, null);
    }

    private static DriveFile doc(String id, String name) {
        return doc(id, name, "1");
    }

    private static DriveFile doc(String id, String name, String version) {
        return new DriveFile(id, name, DriveFile.GOOGLE_DOC, false, true, null, version, OffsetDateTime.parse("2026-09-20T10:00:00Z"),
                "https://docs.google.com/document/d/" + id + "/edit");
    }

    private static DriveFile text(String id, String name, Long size) {
        return new DriveFile(id, name, DriveFile.PLAIN_TEXT, false, true, size, "1", null, null);
    }

    private static DriveFile file(String id, String name, String mimeType) {
        return new DriveFile(id, name, mimeType, false, true, null, "1", null, null);
    }

    private static ConnectorResourceUnavailableException unavailable(ConnectorResourceUnavailableException.Reason reason) {
        return new ConnectorResourceUnavailableException(reason, ConnectorAccess.DRIVE_FILES);
    }

    private static SourceSnapshot snapshot(long artifactId) {
        return new SourceSnapshot(artifactId + 1000, WORKSPACE, artifactId, SourceKind.GOOGLE_DRIVE, OffsetDateTime.now(),
                new SourceOrigin(DRIVE_CONNECTION, 1, "docA", "12", null, "Minutes", null, null));
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(text)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    record Call(String method, String fileId, Integer maxBytes) {
    }

    /** Drive as the contract describes it, scripted per file, recording every call. */
    private static final class ScriptedDrive implements DriveFileReader {

        final List<Call> calls = new ArrayList<>();
        private final Map<String, DriveFile> files = new HashMap<>();
        private final Map<String, byte[]> contents = new HashMap<>();
        private final Map<String, Deque<DriveFile>> scripted = new HashMap<>();
        private final Map<String, RuntimeException> refusals = new HashMap<>();
        private final Map<String, Runnable> meanwhile = new HashMap<>();
        boolean ignoreLimit;

        /** Something that happens elsewhere while the next call of {@code method} is in progress, once. */
        void during(String method, Runnable action) {
            meanwhile.put(method, action);
        }

        void put(DriveFile file) {
            put(file.id(), file, bytes("content of " + file.id()));
        }

        void put(DriveFile file, byte[] content) {
            put(file.id(), file, content);
        }

        void put(String askedId, DriveFile answer) {
            put(askedId, answer, bytes("content"));
        }

        void put(String askedId, DriveFile answer, byte[] content) {
            files.put(askedId, answer);
            contents.put(askedId, content.clone());
            scripted.remove(askedId);
        }

        /** The next descriptions of this file, in order; after the last, the file as put. */
        void nextDescriptions(String fileId, DriveFile... answers) {
            scripted.put(fileId, new ArrayDeque<>(List.of(answers)));
        }

        void refuse(String method, String fileId, RuntimeException refusal) {
            refusals.put(method + ":" + fileId, refusal);
        }

        void stopRefusing() {
            refusals.clear();
        }

        @Override
        public DriveFile describeFile(String accessToken, String fileId) {
            calls.add(new Call("describeFile", fileId, null));
            happenMeanwhile("describeFile");
            refuseIfTold("describeFile", fileId);
            Deque<DriveFile> next = scripted.get(fileId);
            if (next != null && !next.isEmpty()) {
                return next.poll();
            }
            DriveFile file = files.get(fileId);
            if (file == null) {
                throw unavailable(ConnectorResourceUnavailableException.Reason.GONE);
            }
            return file;
        }

        @Override
        public byte[] readGoogleDocAsText(String accessToken, String fileId, int maxBytes) {
            return read("readGoogleDocAsText", fileId, maxBytes);
        }

        @Override
        public byte[] readTextFile(String accessToken, String fileId, int maxBytes) {
            return read("readTextFile", fileId, maxBytes);
        }

        private byte[] read(String method, String fileId, int maxBytes) {
            calls.add(new Call(method, fileId, maxBytes));
            happenMeanwhile(method);
            refuseIfTold(method, fileId);
            byte[] content = contents.get(fileId);
            if (!ignoreLimit && content.length > maxBytes) {
                throw new ConnectorResourceTooLargeException();
            }
            return content.clone();
        }

        private void happenMeanwhile(String method) {
            Runnable action = meanwhile.remove(method);
            if (action != null) {
                action.run();
            }
        }

        private void refuseIfTold(String method, String fileId) {
            RuntimeException refusal = refusals.get(method + ":" + fileId);
            if (refusal != null) {
                throw refusal;
            }
        }
    }

    /** Google's token endpoint: always a fresh token, counted. */
    private static final class CountingProvider implements ConnectorOAuthClient {

        int refreshes;

        @Override
        public Set<String> requiredScopes(ConnectorAccess access) {
            return Set.of(DRIVE_SCOPE);
        }

        @Override
        public ProviderTokens exchange(ConnectorAccess access, String authorizationCode, String codeVerifier) {
            throw new AssertionError("no consent is completed here");
        }

        @Override
        public ProviderTokens refresh(ConnectorAccess access, String refreshToken) {
            refreshes++;
            return new ProviderTokens("fresh-access-token", null, Set.of(DRIVE_SCOPE));
        }

        @Override
        public ProviderAccount describeAccount(ConnectorAccess access, String accessToken) {
            throw new AssertionError("no account is looked up here");
        }

        @Override
        public void revoke(String token) {
            throw new AssertionError("nothing is revoked at Google here");
        }
    }

    private static final class PlainCipher implements ConnectorTokenCipher {

        @Override
        public SealedToken seal(String token, TokenBinding binding) {
            return new SealedToken("k1", new byte[12], token.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String open(SealedToken sealed, TokenBinding binding) {
            return new String(sealed.ciphertext(), StandardCharsets.UTF_8);
        }
    }

    /** The person's one Drive connection, which becomes one that must be connected again once Google refuses its token. */
    private static final class OneConnection implements ConnectionRepository {

        final List<ReconnectReason> reconnects = new ArrayList<>();
        private ConnectionState state = ConnectionState.ACTIVE;
        private ReconnectReason reason;

        void reset() {
            reconnects.clear();
            state = ConnectionState.ACTIVE;
            reason = null;
        }

        private Connection current() {
            return new Connection(DRIVE_CONNECTION, WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES, "account-a", "a@example.org",
                    List.of(DRIVE_SCOPE), state, reason, null, OffsetDateTime.parse("2026-09-20T10:00:00Z"), null, null);
        }

        @Override
        public List<Connection> findForPerson(long workspaceId, long userId) {
            return List.of(current());
        }

        @Override
        public Optional<Connection> findOpen(long workspaceId, long userId, ConnectorAccess access) {
            return access == ConnectorAccess.DRIVE_FILES ? Optional.of(current()) : Optional.empty();
        }

        @Override
        public Optional<SealedToken> findToken(long workspaceId, long userId, long connectionId) {
            return state == ConnectionState.ACTIVE
                    ? Optional.of(new SealedToken("k1", new byte[12], "refresh".getBytes(StandardCharsets.UTF_8)))
                    : Optional.empty();
        }

        @Override
        public Connection saveActive(long workspaceId, long userId, ConnectorAccess access, ProviderAccount account, List<String> grantedScopes,
                SealedToken token) {
            throw new AssertionError("no consent is completed here");
        }

        @Override
        public Optional<Connection> requireReconnect(long workspaceId, long userId, long connectionId, ReconnectReason reason) {
            reconnects.add(reason);
            this.reason = reason;
            state = ConnectionState.RECONNECT_REQUIRED;
            return Optional.of(current());
        }

        @Override
        public Optional<Connection> disconnect(long workspaceId, long userId, long connectionId, ProviderRevocation revocation) {
            throw new AssertionError("nothing is disconnected here");
        }
    }

    /** Grants as the table keeps them: one open grant per file and connection, visible and revocable only by their person. */
    private static final class InMemoryGrants implements ResourceGrantRepository {

        final Map<Long, ResourceGrant> rows = new HashMap<>();
        private final Map<Long, Long> owners = new HashMap<>();
        private long nextId = 1;

        @Override
        public ResourceGrant grant(long workspaceId, long userId, long connectionId, ResourceGrantType type, String externalId, String displayName) {
            for (ResourceGrant row : rows.values()) {
                if (row.isOpen() && row.connectionId() == connectionId && row.type() == type && row.externalId().equals(externalId)) {
                    return row;
                }
            }
            ResourceGrant row = new ResourceGrant(nextId++, workspaceId, connectionId, type, externalId, displayName,
                    OffsetDateTime.parse("2026-09-23T10:00:00Z"), null, null);
            rows.put(row.id(), row);
            owners.put(row.id(), userId);
            return row;
        }

        long grantFor(long userId, long connectionId, String externalId) {
            return grant(WORKSPACE, userId, connectionId, ResourceGrantType.DRIVE_FILE, externalId, "Theirs").id();
        }

        @Override
        public List<ResourceGrant> findOpen(long workspaceId, long userId, long connectionId) {
            return rows.values().stream()
                    .filter(g -> g.isOpen() && g.connectionId() == connectionId && owners.get(g.id()) == userId)
                    .toList();
        }

        @Override
        public Optional<ResourceGrant> find(long workspaceId, long userId, long grantId) {
            return Optional.ofNullable(rows.get(grantId)).filter(g -> g.workspaceId() == workspaceId && owners.get(g.id()) == userId);
        }

        @Override
        public Optional<ResourceGrant> revoke(long workspaceId, long userId, long grantId, GrantRevocationReason reason) {
            Optional<ResourceGrant> open = find(workspaceId, userId, grantId).filter(ResourceGrant::isOpen);
            open.ifPresent(g -> rows.put(g.id(), new ResourceGrant(g.id(), g.workspaceId(), g.connectionId(), g.type(), g.externalId(),
                    g.displayName(), g.grantedAt(), OffsetDateTime.now(), reason)));
            return open.map(g -> rows.get(g.id()));
        }

        Set<String> externalIds() {
            return rows.values().stream().filter(ResourceGrant::isOpen).map(ResourceGrant::externalId).collect(java.util.stream.Collectors.toSet());
        }

        /** The most recent grant of this file. */
        ResourceGrant byExternalId(String externalId) {
            return rows.values().stream()
                    .filter(g -> g.externalId().equals(externalId))
                    .max((a, b) -> Long.compare(a.id(), b.id()))
                    .orElseThrow();
        }
    }

    /** Earlier copies, as a test sets them; nothing is ever recorded here, because these tests stop at the upload. */
    private static final class Snapshots implements SourceSnapshotRepository {

        Optional<SourceSnapshot> sameVersion = Optional.empty();
        Optional<SourceSnapshot> sameText = Optional.empty();
        final List<String> versionLookups = new ArrayList<>();
        final List<String> contentLookups = new ArrayList<>();

        @Override
        public Optional<SourceSnapshot> findImported(long workspaceId, long userId, long grantId, String externalId, String revision) {
            versionLookups.add(externalId + ":" + revision);
            return sameVersion;
        }

        @Override
        public Optional<SourceSnapshot> findLatestImportedWithContent(long workspaceId, long userId, long grantId, String externalId,
                String sha256Hex) {
            contentLookups.add(externalId + ":" + sha256Hex);
            return sameText;
        }

        @Override
        public Optional<SourceSnapshot> find(long workspaceId, long userId, long snapshotId) {
            throw new AssertionError("not reached");
        }

        @Override
        public Optional<SourceSnapshot> findByArtifact(long workspaceId, long userId, long artifactId) {
            throw new AssertionError("not reached");
        }

        @Override
        public SourceSnapshot create(long workspaceId, long userId, long artifactId, SourceKind kind) {
            throw new AssertionError("not reached");
        }

        @Override
        public Optional<SourceSnapshot> createImported(long workspaceId, long userId, long documentId, long artifactId, SourceKind kind,
                SourceOrigin origin, Instant fetchedAt) {
            throw new AssertionError("not reached");
        }
    }

    /** Where a copy that passed every check would be stored; records what it was given, then stops the test there. */
    private static final class StopAtUpload extends ArtifactService {

        final List<String> fileNames = new ArrayList<>();
        byte[] received;
        RuntimeException refusal;

        StopAtUpload() {
            super(null, null, null, LIMIT, null);
        }

        @Override
        public Artifact initiateUpload(long workspaceId, long userId, String rawFilename) {
            fileNames.add(rawFilename);
            return new Artifact(800, workspaceId, "blob", ArtifactStatus.UPLOADING, null, null, null, rawFilename, null,
                    OffsetDateTime.now(), null);
        }

        @Override
        public Artifact receiveContent(long workspaceId, long userId, long artifactId, InputStream content) {
            try {
                received = content.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            throw refusal != null ? refusal : new ReachedUpload();
        }
    }

    private static final class ReachedUpload extends RuntimeException {
    }

    private static final class RecordingLinks extends DocumentSourceService {

        final List<Long> attached = new ArrayList<>();

        RecordingLinks() {
            super(null, null, null, null, null, null);
        }

        @Override
        public AttachedSource attach(long workspaceId, long userId, long documentId, long artifactId) {
            attached.add(artifactId);
            return null;
        }
    }

    private static final class Documents extends RevisionService {

        boolean present = true;

        Documents() {
            super(null, null, null);
        }

        @Override
        public Optional<Document> findDocument(long workspaceId, long userId, long documentId) {
            return present && documentId == DOCUMENT
                    ? Optional.of(new Document(DOCUMENT, WORKSPACE, "Weekly notes", 1, 1, 1, OffsetDateTime.now()))
                    : Optional.empty();
        }
    }
}
