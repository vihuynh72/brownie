package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class PendingConsentTest {

    /**
     * A pending consent exactly as sessions held it before it said what it was
     * for: Java-serialized, as the session store keeps it, from the record as it
     * then was (no purpose component).
     */
    private static final String STORED_BEFORE_PURPOSE = String.join("",
            "rO0ABXNyADhpby5naXRodWIudmlodXluaDcyLmJyb3duaWUuYXBpLmNvbm5lY3Rvci5QZW5kaW5nQ29uc2VudAAAAAAAAAAAAgAH",
            "SgAUY3JlYXRlZEF0RXBvY2hTZWNvbmRKAAZ1c2VySWRKAAt3b3Jrc3BhY2VJZEwABmFjY2Vzc3QAPExpby9naXRodWIvdmlodXlu",
            "aDcyL2Jyb3duaWUvY29yZS9jb25uZWN0b3IvQ29ubmVjdG9yQWNjZXNzO0wADGNvZGVWZXJpZmllcnQAEkxqYXZhL2xhbmcvU3Ry",
            "aW5nO0wACHJldHVyblRvcQB+AAJMAAVzdGF0ZXEAfgACeHAAAAAAarE7gAAAAAAAAAALAAAAAAAAAAd+cgA6aW8uZ2l0aHViLnZp",
            "aHV5bmg3Mi5icm93bmllLmNvcmUuY29ubmVjdG9yLkNvbm5lY3RvckFjY2VzcwAAAAAAAAAAEgAAeHIADmphdmEubGFuZy5FbnVt",
            "AAAAAAAAAAASAAB4cHQAD0NBTEVOREFSX0VWRU5UU3QAF3ZlcmlmaWVyLWJlZm9yZS1wdXJwb3NldAAML2Nvbm5lY3Rpb25zdAAU",
            "c3RhdGUtYmVmb3JlLXB1cnBvc2U=");

    @Test
    void aConsentStoredBeforePicksExistedIsReadBackAsAnOrdinaryConnection() throws Exception {
        PendingConsent stored = (PendingConsent) read(Base64.getDecoder().decode(STORED_BEFORE_PURPOSE));

        assertThat(stored.state()).isEqualTo("state-before-purpose");
        assertThat(stored.codeVerifier()).isEqualTo("verifier-before-purpose");
        assertThat(stored.workspaceId()).isEqualTo(7);
        assertThat(stored.userId()).isEqualTo(11);
        assertThat(stored.access()).isEqualTo(ConnectorAccess.CALENDAR_EVENTS);
        assertThat(stored.returnTo()).isEqualTo("/connections");
        assertThat(stored.pick()).as("read back as a connection, which is what every consent then was").isFalse();
    }

    @Test
    void aPickSurvivesTheSessionStoreAndSaysSo() throws Exception {
        PendingConsent pick = new PendingConsent("state-secret-value", "verifier-secret-value", 7, 11, ConnectorAccess.DRIVE_FILES,
                "/documents/42", 1790000000L, true);

        byte[] stored = write(pick);
        PendingConsent back = (PendingConsent) read(stored);

        assertThat(back).isEqualTo(pick);
        assertThat(back.pick()).isTrue();
        assertThat(back.toString()).as("neither secret").doesNotContain("state-secret-value").doesNotContain("verifier-secret-value")
                .contains("pick=true");
        // A build from before picks existed must still be able to read what this one stores: the stream may name no
        // class of Brownie's that it did not have. It had these two.
        assertThat(new String(stored, StandardCharsets.ISO_8859_1))
                .doesNotContain("PendingConsent$")
                .contains("io.github.vihuynh72.brownie.api.connector.PendingConsent")
                .contains("io.github.vihuynh72.brownie.core.connector.ConnectorAccess");
    }

    private static byte[] write(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static Object read(byte[] bytes) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }
}
