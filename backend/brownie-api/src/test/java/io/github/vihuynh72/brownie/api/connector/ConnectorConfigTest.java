package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorNotConfiguredException;
import io.github.vihuynh72.brownie.core.connector.ConnectorTokenCipher;
import io.github.vihuynh72.brownie.core.connector.TokenBinding;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorConfigTest {

    private static final String ORIGIN = "http://localhost:8081";
    private final ConnectorConfig config = new ConnectorConfig();
    private final String key = Base64.getEncoder().encodeToString(randomBytes(32));

    @Test
    void withoutAClientIdGoogleIsSimplyNotSetUp() {
        GoogleConnectorSetup setup = setup(new MockEnvironment(), ORIGIN, "", "", "");

        assertThat(setup.configured()).isFalse();
    }

    @Test
    void aClientIdNeedsItsSecretAndATokenKeyOrStartUpSaysWhichIsMissing() {
        assertThatThrownBy(() -> setup(new MockEnvironment(), ORIGIN, "client", "", key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BROWNIE_GOOGLE_CLIENT_SECRET");
        assertThatThrownBy(() -> setup(new MockEnvironment(), ORIGIN, "client", "secret", " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BROWNIE_CONNECTOR_TOKEN_KEY")
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    void theCallbackIsBuiltFromThePublicOriginSoItMatchesWhatIsRegistered() {
        GoogleConnectorSetup setup = setup(new MockEnvironment(), ORIGIN, "client", "secret", key);

        assertThat(setup.configured()).isTrue();
        assertThat(setup.settings().redirectUri()).hasToString("http://localhost:8081/api/v1/connectors/google/callback");
        assertThat(setup.settings().tokenUri()).hasToString("https://oauth2.googleapis.com/token");
        assertThat(setup.settings().toString()).doesNotContain("secret");
    }

    @Test
    void aHostedDeploymentUsesGooglesOwnAddressesAndHttpsOnly() {
        MockEnvironment pilot = new MockEnvironment();
        pilot.setActiveProfiles("pilot");
        assertThat(setup(pilot, "https://brownie.example.org", "client", "secret", key).configured()).isTrue();

        MockEnvironment elsewhere = new MockEnvironment().withProperty("brownie.connectors.google.token-uri", "https://collector.example.org/token");
        elsewhere.setActiveProfiles("production");
        assertThatThrownBy(() -> setup(elsewhere, "https://brownie.example.org", "client", "secret", key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token-uri");

        MockEnvironment plainHttp = new MockEnvironment();
        plainHttp.setActiveProfiles("pilot");
        assertThatThrownBy(() -> setup(plainHttp, "http://brownie.example.org", "client", "secret", key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    void locallyTheAddressesCanPointAtAStandInButMustStillBeAddresses() {
        MockEnvironment local = new MockEnvironment().withProperty("brownie.connectors.google.token-uri", "http://127.0.0.1:9999/token");
        assertThat(setup(local, ORIGIN, "client", "secret", key).settings().tokenUri()).hasToString("http://127.0.0.1:9999/token");

        MockEnvironment nonsense = new MockEnvironment().withProperty("brownie.connectors.google.token-uri", "ftp://example.org/token");
        assertThatThrownBy(() -> setup(nonsense, ORIGIN, "client", "secret", key)).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> setup(new MockEnvironment(), "http://localhost:8081/path", "client", "secret", key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BROWNIE_PUBLIC_ORIGIN");
    }

    @Test
    void theTokenKeyMustBeThirtyTwoBytesOfBase64() {
        assertThatThrownBy(() -> config.connectorTokenCipher("not base64 at all!", "1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> config.connectorTokenCipher(Base64.getEncoder().encodeToString(randomBytes(16)), "1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("16");
        assertThatThrownBy(() -> config.connectorTokenCipher(key, "has spaces"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("KEY_ID");

        ConnectorTokenCipher cipher = config.connectorTokenCipher(key, "k1");
        TokenBinding binding = new TokenBinding(1, 2, ConnectorAccess.DRIVE_FILES, "a");
        assertThat(cipher.open(cipher.seal("token", binding), binding)).isEqualTo("token");
    }

    @Test
    void withoutAKeyNothingCanBeSealedOrOpened() {
        ConnectorTokenCipher none = config.connectorTokenCipher("", "1");
        TokenBinding binding = new TokenBinding(1, 2, ConnectorAccess.DRIVE_FILES, "a");

        assertThatThrownBy(() -> none.seal("token", binding)).isInstanceOf(ConnectorNotConfiguredException.class);
    }

    private GoogleConnectorSetup setup(MockEnvironment environment, String origin, String clientId, String secret, String tokenKey) {
        return config.googleConnectorSetup(environment, origin, clientId, secret, tokenKey, Duration.ofSeconds(5), Duration.ofSeconds(15));
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
