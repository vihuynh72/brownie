package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.api.connector.google.GoogleCalendarClient;
import io.github.vihuynh72.brownie.api.connector.google.GoogleClientSettings;
import io.github.vihuynh72.brownie.api.connector.google.GoogleHttp;
import io.github.vihuynh72.brownie.api.connector.google.GoogleOAuthClient;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.connector.CalendarEventReader;
import io.github.vihuynh72.brownie.core.connector.CalendarImportService;
import io.github.vihuynh72.brownie.core.connector.ConnectionRepository;
import io.github.vihuynh72.brownie.core.connector.ConnectorOAuthClient;
import io.github.vihuynh72.brownie.core.connector.ConnectorService;
import io.github.vihuynh72.brownie.core.connector.ConnectorTokenCipher;
import io.github.vihuynh72.brownie.core.connector.ResourceGrantRepository;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.source.DocumentSourceRepository;
import io.github.vihuynh72.brownie.core.source.DocumentSourceService;
import io.github.vihuynh72.brownie.core.source.SourceService;
import io.github.vihuynh72.brownie.core.source.SourceSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Wires the Google connection, and refuses at start-up anything that would
 * otherwise fail later, quietly or dangerously.
 *
 * <p>Google is set up exactly when {@code BROWNIE_GOOGLE_CLIENT_ID} is set.
 * Then the client secret and the token key are required too, because without
 * them the first person to connect would find out instead of whoever runs
 * Brownie. The key must be 32 bytes, base64: anything else is a typo, not a
 * key. On the hosted profiles Google's addresses cannot be changed at all:
 * they are settings only so that tests can point them at a stand-in, and on a
 * real deployment another address would be somewhere else receiving people's
 * Google tokens and Brownie's client secret.
 *
 * <p>The callback address is built from {@code BROWNIE_PUBLIC_ORIGIN} rather
 * than from whatever host a request arrived with, because Google compares it
 * with the registered one character for character.
 */
@Configuration
class ConnectorConfig {

    static final String CALLBACK_PATH = "/api/v1/connectors/google/callback";

    private static final Pattern ORIGIN_ONLY = Pattern.compile("^https?://[A-Za-z0-9.-]+(?::\\d{1,5})?$");
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /** Google's own addresses, the only ones a hosted deployment will use. */
    private static final Map<String, String> GOOGLE_ADDRESSES = Map.of(
            "authorization-uri", "https://accounts.google.com/o/oauth2/v2/auth",
            "token-uri", "https://oauth2.googleapis.com/token",
            "revocation-uri", "https://oauth2.googleapis.com/revoke",
            "user-info-uri", "https://openidconnect.googleapis.com/v1/userinfo",
            "api-base-uri", "https://www.googleapis.com",
            "issuer", "https://accounts.google.com");

    @Bean
    GoogleConnectorSetup googleConnectorSetup(
            Environment environment,
            @Value("${brownie.public-origin}") String publicOrigin,
            @Value("${brownie.connectors.google.client-id:}") String clientId,
            @Value("${brownie.connectors.google.client-secret:}") String clientSecret,
            @Value("${brownie.connectors.token-key:}") String tokenKey,
            @Value("${brownie.connectors.google.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${brownie.connectors.google.read-timeout:PT15S}") Duration readTimeout) {
        if (isBlank(clientId)) {
            return new GoogleConnectorSetup(null);
        }
        if (isBlank(clientSecret)) {
            throw new IllegalStateException(
                    "BROWNIE_GOOGLE_CLIENT_ID is set but BROWNIE_GOOGLE_CLIENT_SECRET is not: Google refuses a connection without it.");
        }
        if (isBlank(tokenKey)) {
            throw new IllegalStateException(
                    "BROWNIE_GOOGLE_CLIENT_ID is set but BROWNIE_CONNECTOR_TOKEN_KEY is not: Brownie will not store a Google token unencrypted."
                            + " Generate one with: openssl rand -base64 32");
        }
        boolean hosted = environment.matchesProfiles("pilot", "production");
        if (publicOrigin == null || !ORIGIN_ONLY.matcher(publicOrigin).matches()) {
            throw new IllegalStateException("BROWNIE_PUBLIC_ORIGIN must be an absolute http(s) origin with no path; Google's callback is built from it.");
        }
        if (hosted && !publicOrigin.startsWith("https://")) {
            throw new IllegalStateException("BROWNIE_PUBLIC_ORIGIN must be https on a hosted deployment: Google's callback carries a consent code.");
        }
        GoogleClientSettings settings = new GoogleClientSettings(
                clientId.trim(),
                clientSecret.trim(),
                URI.create(publicOrigin + CALLBACK_PATH),
                address(environment, "authorization-uri", hosted),
                address(environment, "token-uri", hosted),
                address(environment, "revocation-uri", hosted),
                address(environment, "user-info-uri", hosted),
                address(environment, "api-base-uri", hosted),
                address(environment, "issuer", hosted).toString(),
                connectTimeout,
                readTimeout);
        return new GoogleConnectorSetup(settings);
    }

    /**
     * The token key is independent of whether Google is set up now: tokens
     * stored while it was can still be opened, and so revoked, after the
     * client is removed. Without a key there is nothing to open them with.
     */
    @Bean
    ConnectorTokenCipher connectorTokenCipher(
            @Value("${brownie.connectors.token-key:}") String tokenKey,
            @Value("${brownie.connectors.token-key-id:1}") String tokenKeyId) {
        if (isBlank(tokenKey)) {
            return new NotConfiguredConnector();
        }
        if (tokenKeyId == null || !KEY_ID.matcher(tokenKeyId.trim()).matches()) {
            throw new IllegalStateException("BROWNIE_CONNECTOR_TOKEN_KEY_ID must be 1 to 64 letters, digits, '.', '_' or '-'.");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(tokenKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("BROWNIE_CONNECTOR_TOKEN_KEY is not base64. Generate one with: openssl rand -base64 32");
        }
        if (key.length != AesGcmConnectorTokenCipher.KEY_BYTES) {
            throw new IllegalStateException("BROWNIE_CONNECTOR_TOKEN_KEY must decode to exactly 32 bytes; it decodes to " + key.length + "."
                    + " Generate one with: openssl rand -base64 32");
        }
        return new AesGcmConnectorTokenCipher(tokenKeyId.trim(), key);
    }

    @Bean
    ConnectorOAuthClient connectorOAuthClient(GoogleConnectorSetup setup, ObjectMapper objectMapper) {
        if (!setup.configured()) {
            return new NotConfiguredConnector();
        }
        GoogleClientSettings settings = setup.settings();
        return new GoogleOAuthClient(settings, GoogleHttp.create(settings.connectTimeout(), settings.readTimeout(), objectMapper));
    }

    @Bean
    ConnectorService connectorService(
            ConnectionRepository connectionRepository, ConnectorTokenCipher connectorTokenCipher, ConnectorOAuthClient connectorOAuthClient) {
        return new ConnectorService(connectionRepository, connectorTokenCipher, connectorOAuthClient);
    }

    @Bean
    CalendarEventReader calendarEventReader(GoogleConnectorSetup setup, ObjectMapper objectMapper) {
        if (!setup.configured()) {
            return new NotConfiguredCalendar();
        }
        GoogleClientSettings settings = setup.settings();
        return new GoogleCalendarClient(settings, GoogleHttp.create(settings.connectTimeout(), settings.readTimeout(), objectMapper));
    }

    @Bean
    CalendarImportService calendarImportService(
            ConnectorService connectorService,
            ResourceGrantRepository resourceGrantRepository,
            CalendarEventReader calendarEventReader,
            ArtifactService artifactService,
            SourceService sourceService,
            SourceSnapshotRepository sourceSnapshotRepository,
            DocumentSourceService documentSourceService,
            DocumentSourceRepository documentSourceRepository,
            RevisionService revisionService) {
        return new CalendarImportService(
                connectorService,
                resourceGrantRepository,
                calendarEventReader,
                artifactService,
                sourceService,
                sourceSnapshotRepository,
                documentSourceService,
                documentSourceRepository,
                revisionService);
    }

    private static URI address(Environment environment, String name, boolean hosted) {
        String google = GOOGLE_ADDRESSES.get(name);
        String configured = environment.getProperty("brownie.connectors.google." + name, google).trim();
        if (hosted && !configured.equals(google)) {
            throw new IllegalStateException("brownie.connectors.google." + name + " cannot be changed on a hosted deployment; it must be " + google + ".");
        }
        URI uri;
        try {
            uri = URI.create(configured);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("brownie.connectors.google." + name + " is not an address.");
        }
        if (!uri.isAbsolute() || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new IllegalStateException("brownie.connectors.google." + name + " must be an absolute http(s) address.");
        }
        return uri;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
