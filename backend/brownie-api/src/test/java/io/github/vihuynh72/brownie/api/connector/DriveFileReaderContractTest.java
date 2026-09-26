package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorBlockedByOrganizationException;
import io.github.vihuynh72.brownie.core.connector.ConnectorResourceTooLargeException;
import io.github.vihuynh72.brownie.core.connector.ConnectorResourceUnavailableException;
import io.github.vihuynh72.brownie.core.connector.ConnectorResourceUnavailableException.Reason;
import io.github.vihuynh72.brownie.core.connector.DriveFile;
import io.github.vihuynh72.brownie.core.connector.DriveFileReader;
import io.github.vihuynh72.brownie.core.connector.ProviderMisconfiguredException;
import io.github.vihuynh72.brownie.core.connector.ProviderTokenRejectedException;
import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@link DriveFileReader} promises, case by case, shown on the pretend
 * Drive the rest of the tests use. It reads as a reference for anyone
 * writing a real reader: each test is one line of the interface's contract.
 */
class DriveFileReaderContractTest {

    private static final String TOKEN = "stand-in-access-token";
    private static final DriveFile DOC = new DriveFile("docId_1", "Minutes", DriveFile.GOOGLE_DOC, false, true, 4096L, "12",
            OffsetDateTime.parse("2026-09-20T10:00:00Z"), "https://docs.google.com/document/d/docId_1/edit");
    private static final DriveFile TEXT = new DriveFile("txtId-2", "notes.txt", DriveFile.PLAIN_TEXT, false, true, 5L, "3",
            null, null);

    private final InMemoryDriveFileReader drive = new InMemoryDriveFileReader();

    @Test
    void thereAreThreeReadsAndNothingElse() {
        assertThat(Arrays.stream(DriveFileReader.class.getDeclaredMethods()).map(Method::getName).sorted())
                .as("nothing that lists, searches or changes files")
                .containsExactly("describeFile", "readGoogleDocAsText", "readTextFile");
    }

    @Test
    void aDescriptionGivesEveryFactDriveStatesAndNullForWhatItDoesNot() {
        drive.put(DOC, bytes("text"));
        drive.put(TEXT, bytes("hello"));

        assertThat(drive.describeFile(TOKEN, "docId_1")).isEqualTo(DOC);
        DriveFile text = drive.describeFile(TOKEN, "txtId-2");
        assertThat(text.isPlainText()).isTrue();
        assertThat(text.modifiedTime()).isNull();
        assertThat(text.webViewLink()).isNull();
    }

    @Test
    void theIdTypeAndVersionAreAlwaysThere() {
        assertThatThrownBy(() -> new DriveFile(null, "x", DriveFile.PLAIN_TEXT, false, true, null, "1", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DriveFile("id", "x", null, false, true, null, "1", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DriveFile("id", "x", DriveFile.PLAIN_TEXT, false, true, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aTrashedOrUndownloadableFileIsDescribedNotRefused() {
        drive.put(new DriveFile("trashed1", "old.txt", DriveFile.PLAIN_TEXT, true, true, 1L, "1", null, null), bytes("x"));
        drive.put(new DriveFile("locked1", "locked.txt", DriveFile.PLAIN_TEXT, false, false, 1L, "1", null, null), bytes("x"));

        assertThat(drive.describeFile(TOKEN, "trashed1").trashed()).isTrue();
        assertThat(drive.describeFile(TOKEN, "locked1").canDownload()).isFalse();
    }

    @Test
    void aFileThatIsGoneOrNotShownToThisPersonIsGoneForDrive() {
        assertThatThrownBy(() -> drive.describeFile(TOKEN, "missing1"))
                .isInstanceOfSatisfying(ConnectorResourceUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.GONE);
                    assertThat(e.access()).isEqualTo(ConnectorAccess.DRIVE_FILES);
                });
    }

    @Test
    void contentComesBackByteForByteByteOrderMarkIncluded() {
        byte[] withMark = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i', '\r', '\n'};
        drive.put(DOC, withMark);
        drive.put(TEXT, withMark);

        assertThat(drive.readGoogleDocAsText(TOKEN, "docId_1", 100)).containsExactly(withMark);
        assertThat(drive.readTextFile(TOKEN, "txtId-2", 100)).containsExactly(withMark);
    }

    @Test
    void contentAtTheLimitIsReturnedAndOneByteMoreIsTooLargeWithNothingReturned() {
        drive.put(TEXT, bytes("hello"));

        assertThat(drive.readTextFile(TOKEN, "txtId-2", 5)).hasSize(5);
        assertThatThrownBy(() -> drive.readTextFile(TOKEN, "txtId-2", 4)).isInstanceOf(ConnectorResourceTooLargeException.class);
    }

    @Test
    void eachRefusalMeansWhatTheContractSays() {
        drive.put(DOC, bytes("text"));
        RuntimeException[] refusals = {
                new ConnectorResourceUnavailableException(Reason.ACCESS_LOST, ConnectorAccess.DRIVE_FILES),
                new ConnectorResourceUnavailableException(Reason.DOWNLOAD_RESTRICTED, ConnectorAccess.DRIVE_FILES),
                new ConnectorResourceTooLargeException(),
                new ProviderTokenRejectedException("refused"),
                new ProviderUnavailableException("away"),
                new ConnectorBlockedByOrganizationException(ConnectorAccess.DRIVE_FILES),
                new ProviderMisconfiguredException("setup"),
        };
        for (RuntimeException refusal : refusals) {
            drive.refuse("readGoogleDocAsText", "docId_1", refusal);
            assertThatThrownBy(() -> drive.readGoogleDocAsText(TOKEN, "docId_1", 100)).isSameAs(refusal);
        }
    }

    @Test
    void everyCallIsRecordedWithWhatItAskedFor() {
        drive.put(DOC, bytes("text"));
        drive.describeFile(TOKEN, "docId_1");
        drive.readGoogleDocAsText(TOKEN, "docId_1", 64);

        assertThat(drive.calls()).containsExactly(
                new InMemoryDriveFileReader.Call("describeFile", "docId_1", null),
                new InMemoryDriveFileReader.Call("readGoogleDocAsText", "docId_1", 64));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
