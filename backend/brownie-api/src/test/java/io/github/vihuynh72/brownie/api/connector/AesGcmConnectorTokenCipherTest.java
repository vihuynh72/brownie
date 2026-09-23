package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.SealedToken;
import io.github.vihuynh72.brownie.core.connector.TokenBinding;
import io.github.vihuynh72.brownie.core.connector.UnreadableTokenException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmConnectorTokenCipherTest {

    private static final String TOKEN = "1//refresh-token-value-that-google-would-hand-back";
    private static final TokenBinding OWNER = new TokenBinding(7, 3, ConnectorAccess.DRIVE_FILES, "account-a");

    private final byte[] key = randomKey();
    private final AesGcmConnectorTokenCipher cipher = new AesGcmConnectorTokenCipher("k1", key);

    @Test
    void aSealedTokenOpensForItsOwnBindingAndNeverHoldsThePlaintext() {
        SealedToken sealed = cipher.seal(TOKEN, OWNER);

        assertThat(cipher.open(sealed, OWNER)).isEqualTo(TOKEN);
        assertThat(sealed.keyId()).isEqualTo("k1");
        assertThat(sealed.nonce()).hasSize(12);
        assertThat(new String(sealed.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("refresh-token");
        // The authentication tag is the last 16 bytes.
        assertThat(sealed.ciphertext()).hasSize(TOKEN.getBytes(StandardCharsets.UTF_8).length + 16);
        assertThat(sealed.toString()).doesNotContain("refresh");
    }

    @Test
    void theSameTokenSealedTwiceUsesTwoNonces() {
        SealedToken first = cipher.seal(TOKEN, OWNER);
        SealedToken second = cipher.seal(TOKEN, OWNER);

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    void aCiphertextMovedToAnyoneElsesConnectionDoesNotOpen() {
        SealedToken sealed = cipher.seal(TOKEN, OWNER);

        for (TokenBinding other : new TokenBinding[] {
                new TokenBinding(8, 3, ConnectorAccess.DRIVE_FILES, "account-a"),
                new TokenBinding(7, 4, ConnectorAccess.DRIVE_FILES, "account-a"),
                new TokenBinding(7, 3, ConnectorAccess.CALENDAR_EVENTS, "account-a"),
                new TokenBinding(7, 3, ConnectorAccess.DRIVE_FILES, "account-b")}) {
            assertThatThrownBy(() -> cipher.open(sealed, other)).isInstanceOf(UnreadableTokenException.class);
        }
    }

    @Test
    void aChangedCiphertextOrNonceDoesNotOpen() {
        SealedToken sealed = cipher.seal(TOKEN, OWNER);
        byte[] ciphertext = sealed.ciphertext();
        ciphertext[0] ^= 0x01;
        assertThatThrownBy(() -> cipher.open(new SealedToken("k1", sealed.nonce(), ciphertext), OWNER))
                .isInstanceOf(UnreadableTokenException.class);

        byte[] nonce = sealed.nonce();
        nonce[11] ^= 0x01;
        assertThatThrownBy(() -> cipher.open(new SealedToken("k1", nonce, sealed.ciphertext()), OWNER))
                .isInstanceOf(UnreadableTokenException.class);

        assertThatThrownBy(() -> cipher.open(new SealedToken("k1", new byte[5], sealed.ciphertext()), OWNER))
                .as("a nonce this cipher could never have written")
                .isInstanceOf(UnreadableTokenException.class);
    }

    @Test
    void aTokenFromAnotherKeyIsRefusedWhetherOrNotItsIdentifierMatches() {
        SealedToken sealed = cipher.seal(TOKEN, OWNER);

        AesGcmConnectorTokenCipher rotated = new AesGcmConnectorTokenCipher("k2", randomKey());
        assertThatThrownBy(() -> rotated.open(sealed, OWNER))
                .isInstanceOf(UnreadableTokenException.class)
                .hasMessageContaining("k1");

        AesGcmConnectorTokenCipher sameNameOtherKey = new AesGcmConnectorTokenCipher("k1", randomKey());
        assertThatThrownBy(() -> sameNameOtherKey.open(sealed, OWNER)).isInstanceOf(UnreadableTokenException.class);
    }

    @Test
    void onlyAThirtyTwoByteKeyIsAccepted() {
        assertThatThrownBy(() -> new AesGcmConnectorTokenCipher("k1", new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmConnectorTokenCipher("k1", null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
