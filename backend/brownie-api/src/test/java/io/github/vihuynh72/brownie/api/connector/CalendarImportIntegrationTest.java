package io.github.vihuynh72.brownie.api.connector;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.source.SourceConversion;
import io.github.vihuynh72.brownie.core.source.SourceKind;
import io.github.vihuynh72.brownie.core.source.SourceOrigin;
import io.github.vihuynh72.brownie.core.source.SourceSnapshotRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reading a person's own calendar into a document, end to end: Brownie's
 * real routes, sessions, database, policies, storage and malware scanner,
 * with Google stood in for by WireMock over real HTTP. What is checked is
 * what a person would meet and what is left behind: the text a copy is, that
 * it can be cited, that it says where it came from, that the same version is
 * copied once, what is refused and why, and that Google is only ever read.
 * Also what happens when Google takes the access back (a token that expires,
 * as it does after seven days while Brownie's Google project is in testing,
 * or a permission taken away), and the claims Brownie makes about what it
 * sends where: only reads reach Google's APIs, Drive is never listed, and no
 * token or secret reaches a log, a browser or a copy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "brownie.public-origin=http://localhost:8081",
        "brownie.web.origin=http://localhost:5173",
        "brownie.connectors.google.client-id=stand-in-client.apps.googleusercontent.com",
        "brownie.connectors.google.client-secret=" + CalendarImportIntegrationTest.CLIENT_SECRET,
        "brownie.connectors.token-key-id=test-key"})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class CalendarImportIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-calendar-import";
    private static final String CALLBACK = "/api/v1/connectors/google/callback";
    private static final String CALENDAR_SCOPES =
            "openid https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/calendar.events.owned.readonly";
    private static final String EVENTS = "/calendar/v3/calendars/primary/events";
    private static final String ACCESS_TOKEN = "ya29.calendar-access-stand-in";
    private static final String REFRESH_TOKEN = "1//calendar-refresh-stand-in";
    /** Not private: the properties above, outside the class body, name it too. */
    static final String CLIENT_SECRET = "stand-in-client-secret-value";
    private static final String DRIVE_ACCESS_TOKEN = "ya29.drive-access-stand-in";
    private static final String DRIVE_REFRESH_TOKEN = "1//drive-refresh-stand-in";
    /** Google's answer to a refresh token it has ended, the seven-day end of a testing project's tokens included. */
    private static final String EXPIRED_OR_REVOKED =
            "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}";
    /** Google's answer to a read the access token does not carry the permission for. */
    private static final String SCOPE_INSUFFICIENT = """
            {"error":{"code":403,"message":"Request had insufficient authentication scopes.",
              "errors":[{"message":"Insufficient Permission","domain":"global","reason":"insufficientPermissions"}],
              "status":"PERMISSION_DENIED",
              "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT",
                          "domain":"googleapis.com","metadata":{"service":"calendar-json.googleapis.com"}}]}}
            """;

    private static final WireMockServer GOOGLE = startedGoogle();
    private static final String TOKEN_KEY = randomKey();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("brownie")
            .withUsername("postgres")
            .withPassword("postgres_bootstrap_only")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptPath()), "/docker-entrypoint-initdb.d/01-app-roles.sql");

    @Container
    static final AzuriteContainer AZURITE = new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.37.0");

    @Container
    static final GenericContainer<?> CLAMAV = new GenericContainer<>(DockerImageName.parse("clamav/clamav-debian:1.4"))
            .withExposedPorts(3310)
            .waitingFor(Wait.forLogMessage(".*socket found, clamd started\\.\\n", 1))
            .withStartupTimeout(java.time.Duration.ofMinutes(3));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("brownie.storage.local-connection", AZURITE::getConnectionString);
        registry.add("brownie.security.clamav.host", CLAMAV::getHost);
        registry.add("brownie.security.clamav.port", () -> CLAMAV.getMappedPort(3310));
        registry.add("brownie.connectors.token-key", () -> TOKEN_KEY);
        registry.add("brownie.connectors.google.token-uri", () -> GOOGLE.baseUrl() + "/token");
        registry.add("brownie.connectors.google.revocation-uri", () -> GOOGLE.baseUrl() + "/revoke");
        registry.add("brownie.connectors.google.user-info-uri", () -> GOOGLE.baseUrl() + "/userinfo");
        registry.add("brownie.connectors.google.api-base-uri", GOOGLE::baseUrl);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private BuiltInTemplateProvisioningService builtInTemplateProvisioningService;

    @Autowired
    private SourceSnapshotRepository sourceSnapshotRepository;

    @BeforeEach
    void resetGoogle() {
        GOOGLE.resetAll();
        GOOGLE.stubFor(post(urlPathEqualTo("/token")).willReturn(okJson("""
                {"access_token":"%s","expires_in":3599,"refresh_token":"%s","token_type":"Bearer","scope":"%s"}
                """.formatted(ACCESS_TOKEN, REFRESH_TOKEN, CALENDAR_SCOPES))));
        GOOGLE.stubFor(post(urlPathEqualTo("/revoke")).willReturn(aResponse().withStatus(200)));
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS)).withQueryParam("maxResults", equalTo("1"))
                .willReturn(okJson("{\"timeZone\":\"America/Los_Angeles\"}")));
    }

    @AfterAll
    static void stopGoogle() {
        GOOGLE.stop();
    }

    @Test
    void aChosenEventBecomesACitableTextSourceThatSaysWhereItCameFromAndIsCopiedOnce(CapturedOutput output) throws Exception {
        Member member = signInAndConnectCalendar("subject-calendar-import");
        long documentId = createDocument(member);
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS)).withQueryParam("maxResults", equalTo("50")).willReturn(okJson("""
                {"timeZone":"America/Los_Angeles","items":[
                  {"id":"weekly1","summary":"Weekly sync","start":{"dateTime":"2026-09-24T10:00:00-07:00"},"end":{"dateTime":"2026-09-24T10:30:00-07:00"}},
                  {"id":"offsite1","summary":"Offsite","start":{"date":"2026-09-25"},"end":{"date":"2026-09-27"}},
                  {"id":"dropin1","summary":"Drop-in","endTimeUnspecified":true,
                   "start":{"dateTime":"2026-09-26T10:00:00-07:00"},"end":{"dateTime":"2026-09-26T10:00:00-07:00"}}]}
                """)));

        mockMvc.perform(MockMvcRequestBuilders.get(calendarPath(member) + "/events")
                        .param("from", "2026-09-01T00:00:00-07:00").param("to", "2026-09-30T00:00:00-07:00")
                        .cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.timeZone").value("America/Los_Angeles"))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.events[0].id").value("weekly1"))
                .andExpect(jsonPath("$.events[0].title").value("Weekly sync"))
                .andExpect(jsonPath("$.events[0].allDay").value(false))
                .andExpect(jsonPath("$.events[1].allDay").value(true))
                .andExpect(jsonPath("$.events[1].startDate").value("2026-09-25"))
                .andExpect(jsonPath("$.events[1].endDate").value("2026-09-26"))
                .andExpect(jsonPath("$.events[2].startsAt").exists())
                .andExpect(jsonPath("$.events[2].endsAt").value(org.hamcrest.Matchers.nullValue()));
        assertThat(GOOGLE.findAll(getRequestedFor(urlPathEqualTo(EVENTS)).withQueryParam("maxResults", equalTo("50"))).getFirst()
                .queryParameter("timeMin").firstValue()).isEqualTo("2026-09-01T07:00:00Z");
        assertThat(text("SELECT resource_type || ':' || external_id FROM connector_resource_grant WHERE workspace_id = ? AND revoked_at IS NULL",
                member.workspaceId())).as("reading the calendar is recorded as the person's choice").isEqualTo("CALENDAR:primary");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/workspaces/" + member.workspaceId() + "/connections").cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].grants.length()").value(1))
                .andExpect(jsonPath("$[0].grants[0].type").value("CALENDAR"))
                .andExpect(jsonPath("$[0].grants[0].displayName").value("Primary calendar"))
                .andExpect(jsonPath("$[0].grants[0].externalId").doesNotExist());

        stubEvent("weekly1", "1", "Weekly sync");
        JsonNode first = importEvent(member, documentId, "weekly1");
        assertThat(first.get("newCopy").asBoolean()).isTrue();
        JsonNode source = first.get("source");
        assertThat(source.get("kind").asString()).isEqualTo("GOOGLE_CALENDAR");
        assertThat(source.get("displayFilename").asString()).isEqualTo("Weekly sync.txt");
        assertThat(source.get("origin").get("provider").asString()).isEqualTo("GOOGLE");
        assertThat(source.get("origin").get("title").asString()).isEqualTo("Weekly sync");
        assertThat(source.get("origin").get("link").asString()).isEqualTo("https://www.google.com/calendar/event?eid=weekly1");
        assertThat(source.get("origin").get("conversion").asString()).isEqualTo("CALENDAR_EVENT_AS_TEXT");
        assertThat(source.get("origin").has("externalId")).as("the provider's identifiers are not shown").isFalse();
        long snapshotId = source.get("id").asLong();
        long artifactId = source.get("artifactId").asLong();

        assertThat(text("SELECT status || ':' || detected_media_type FROM artifact WHERE id = ?", artifactId)).isEqualTo("READY:PLAIN_TEXT");
        assertThat(text("SELECT origin_external_id || '|' || origin_revision FROM source_snapshot WHERE id = ?", snapshotId))
                .isEqualTo("weekly1|\"1\"");
        String stored = text("SELECT normalized_text FROM plain_text_extraction_version WHERE artifact_id = ?", artifactId);
        assertThat(stored).startsWith("Google Calendar event\n\nTitle: Weekly sync\n\n")
                .contains("Planned start: 2026-09-24 (Thursday) 10:00 (America/Los_Angeles, UTC-07:00)")
                .contains("They do not show when it actually started or ended")
                .contains("Location: Room 4")
                .as("an invitation is not attendance: who was invited is never copied")
                .doesNotContain("guest.one@example.org")
                .doesNotContain("Guest One");
        assertThat(text("SELECT details::text FROM audit_event WHERE action = 'SOURCE_IMPORTED' AND resource_id = ?", snapshotId))
                .contains("GOOGLE_CALENDAR").contains("CALENDAR_EVENT_AS_TEXT").doesNotContain("Weekly sync");

        // The copy is a source like any other: a paragraph of it can be cited, and the citation shown through the document.
        long spanId = JSON.readTree(mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/sources/" + snapshotId + "/spans")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"type\":\"PLAIN_TEXT\",\"startCodePoint\":23,\"endCodePointExclusive\":41}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(MockMvcRequestBuilders.get(documentPath(member, documentId) + "/evidence/" + spanId).cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excerptText").value("Title: Weekly sync"));

        JsonNode again = importEvent(member, documentId, "weekly1");
        assertThat(again.get("newCopy").asBoolean()).as("the same version is copied once").isFalse();
        assertThat(again.get("source").get("id").asLong()).isEqualTo(snapshotId);

        stubEvent("weekly1", "2", "Weekly sync (moved)");
        JsonNode changed = importEvent(member, documentId, "weekly1");
        assertThat(changed.get("newCopy").asBoolean()).as("a changed event is a new copy").isTrue();
        assertThat(changed.get("source").get("id").asLong()).isNotEqualTo(snapshotId);
        assertThat(text("SELECT origin_revision || '|' || origin_title FROM source_snapshot WHERE id = ?", snapshotId))
                .as("and the old copy is left exactly as it was").isEqualTo("\"1\"|Weekly sync");

        stubEvent("weekly1", "3", "Weekly sync (moved)");
        JsonNode replied = importEvent(member, documentId, "weekly1");
        assertThat(replied.get("newCopy").asBoolean()).as("a guest replying changes the version but not the text: no new copy").isFalse();
        assertThat(replied.get("source").get("id").asLong()).isEqualTo(changed.get("source").get("id").asLong());

        stubEvent("weekly1", "4", "Weekly sync");
        JsonNode movedBack = importEvent(member, documentId, "weekly1");
        assertThat(movedBack.get("newCopy").asBoolean())
                .as("text that changes back is a new copy, not the older one brought back as if it were the latest").isTrue();
        assertThat(movedBack.get("source").get("id").asLong()).isNotEqualTo(snapshotId);

        mockMvc.perform(MockMvcRequestBuilders.get(documentPath(member, documentId) + "/sources").cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].origin.title").value("Weekly sync"))
                .andExpect(jsonPath("$[1].origin.title").value("Weekly sync (moved)"))
                .andExpect(jsonPath("$[2].origin.title").value("Weekly sync"));

        assertOnlyReadsOfThePrimaryCalendar();
        assertThat(output.getAll()).doesNotContain(ACCESS_TOKEN).doesNotContain(REFRESH_TOKEN);
    }

    @Test
    void whatCannotBeCopiedSaysWhyAndLeavesNoSourceBehind() throws Exception {
        Member member = signInAndConnectCalendar("subject-calendar-refused");
        long documentId = createDocument(member);

        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/called-off")).willReturn(okJson("{\"id\":\"called-off\",\"status\":\"cancelled\"}")));
        importExpecting(member, documentId, "called-off", 409)
                .andExpect(jsonPath("$.code").value("CONNECTOR_RESOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value("CANCELLED"));
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/deleted1")).willReturn(aResponse().withStatus(404)));
        importExpecting(member, documentId, "deleted1", 409).andExpect(jsonPath("$.reason").value("GONE"));
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/api-off")).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"accessNotConfigured\"}],\"status\":\"PERMISSION_DENIED\"}}")));
        importExpecting(member, documentId, "api-off", 503).andExpect(jsonPath("$.code").value("CONNECTOR_MISCONFIGURED"));
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/series1")).willReturn(okJson("""
                {"id":"series1","summary":"Standup","recurrence":["RRULE:FREQ=WEEKLY;BYDAY=MO"],"eventType":"default",
                 "start":{"dateTime":"2026-09-07T17:00:00Z"},"end":{"dateTime":"2026-09-07T17:15:00Z"},"etag":"\\"1\\""}
                """)));
        importExpecting(member, documentId, "series1", 422).andExpect(jsonPath("$.code").value("CONNECTOR_RESOURCE_UNSUPPORTED"));
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/away1")).willReturn(okJson("""
                {"id":"away1","summary":"Out of office","eventType":"outOfOffice",
                 "start":{"dateTime":"2026-09-08T00:00:00Z"},"end":{"dateTime":"2026-09-09T00:00:00Z"},"etag":"\\"1\\""}
                """)));
        importExpecting(member, documentId, "away1", 422).andExpect(jsonPath("$.code").value("CONNECTOR_RESOURCE_UNSUPPORTED"));

        importExpecting(member, documentId, "../../users/me/calendarList", 400).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(MockMvcRequestBuilders.get(calendarPath(member) + "/events")
                        .param("from", "2026-09-01T00:00:00-07:00").param("to", "2026-10-03T00:00:00-07:00").cookie(member.session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(MockMvcRequestBuilders.get(calendarPath(member) + "/events")
                        .param("from", "2026-09-01").param("to", "2026-09-02").cookie(member.session()))
                .andExpect(status().isBadRequest());

        // Another person's document is not there for this person, and Google is not even asked.
        Member other = signInAndConnectCalendar("subject-calendar-other");
        long othersDocument = createDocument(other);
        int readsBefore = GOOGLE.findAll(getRequestedFor(urlMatching(EVENTS + "/.*"))).size();
        importExpecting(member, othersDocument, "weekly1", 404);
        assertThat(GOOGLE.findAll(getRequestedFor(urlMatching(EVENTS + "/.*")))).hasSize(readsBefore);

        assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ? AND kind = 'GOOGLE_CALENDAR'", member.workspaceId())).isZero();
    }

    @Test
    void aRefusedTokenAsksForReconnectionAndDisconnectingKeepsCopiesButEndsReading() throws Exception {
        Member member = signInAndConnectCalendar("subject-calendar-disconnect");
        long documentId = createDocument(member);
        stubEvent("weekly1", "1", "Weekly sync");
        long snapshotId = importEvent(member, documentId, "weekly1").get("source").get("id").asLong();

        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/taken-away")).willReturn(aResponse().withStatus(401)));
        importExpecting(member, documentId, "taken-away", 409)
                .andExpect(jsonPath("$.code").value("CONNECTION_RECONNECT_REQUIRED"))
                .andExpect(jsonPath("$.reason").value("TOKEN_REJECTED"));
        assertThat(text("SELECT state || ':' || reconnect_reason FROM connector_connection WHERE workspace_id = ? AND access = 'CALENDAR_EVENTS'",
                member.workspaceId())).isEqualTo("RECONNECT_REQUIRED:TOKEN_REJECTED");

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/connections/google/disconnect")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());
        assertThat(text("SELECT revoked_reason FROM connector_resource_grant WHERE workspace_id = ?", member.workspaceId())).isEqualTo("DISCONNECTED");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/workspaces/" + member.workspaceId() + "/connections").cookie(member.session()))
                .andExpect(jsonPath("$[0].state").value("DISCONNECTED"))
                .andExpect(jsonPath("$[0].grants").isEmpty());
        mockMvc.perform(MockMvcRequestBuilders.get(documentPath(member, documentId) + "/sources").cookie(member.session()))
                .andExpect(jsonPath("$[0].id").value(snapshotId))
                .andExpect(jsonPath("$[0].origin.title").value("Weekly sync"));
        importExpecting(member, documentId, "weekly1", 404).andExpect(jsonPath("$.code").value("CONNECTION_NOT_FOUND"));

        // A copy finishing after the choice was taken back is not kept: the database is asked with the choice locked.
        long grantId = count("SELECT id FROM connector_resource_grant WHERE workspace_id = ?", member.workspaceId());
        long connectionId = count("SELECT id FROM connector_connection WHERE workspace_id = ?", member.workspaceId());
        long lateArtifact = uploadPlainText(member, "Planned start: later.");
        assertThat(sourceSnapshotRepository.createImported(member.workspaceId(), member.userId(), documentId, lateArtifact, SourceKind.GOOGLE_CALENDAR,
                new SourceOrigin(connectionId, grantId, "late1", "\"9\"", null, null, null, SourceConversion.CALENDAR_EVENT_AS_TEXT),
                Instant.now()))
                .isEmpty();
        assertThat(count("SELECT count(*) FROM source_snapshot WHERE artifact_id = ?", lateArtifact)).isZero();
    }

    @Test
    void aDisconnectWhileACopyIsBeingMadeLeavesNoCopyBehind() throws Exception {
        Member member = signInAndConnectCalendar("subject-calendar-race");
        long documentId = createDocument(member);
        stubEvent("slow1", "1", "Slow sync");
        // Google takes its time over the calendar's time zone, which the import asks for after reading the event.
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS)).withQueryParam("maxResults", equalTo("1"))
                .willReturn(okJson("{\"timeZone\":\"America/Los_Angeles\"}").withFixedDelay(3000)));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        MvcResult result;
        try {
            Future<MvcResult> importing = executor.submit(() -> mockMvc.perform(MockMvcRequestBuilders.post(calendarPath(member) + "/imports")
                            .cookie(member.session()).with(csrf()).contentType("application/json")
                            .content("{\"documentId\":" + documentId + ",\"eventId\":\"slow1\"}"))
                    .andReturn());
            awaitTimeZoneRequest();
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/connections/google/disconnect")
                            .cookie(member.session()).with(csrf()))
                    .andExpect(status().isOk());
            result = importing.get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("code").asString()).isEqualTo("CONNECTION_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ? AND kind = 'GOOGLE_CALENDAR'", member.workspaceId()))
                .as("a copy finished after the disconnect is not kept").isZero();
        assertThat(count("SELECT count(*) FROM document_source WHERE workspace_id = ?", member.workspaceId())).isZero();
    }

    @Test
    void aCopyFollowsItsDocumentIntoTheTrashRemembersWhenGoogleWasReadAndGoesWithIt() throws Exception {
        Member member = signInAndConnectCalendar("subject-calendar-trash");
        long documentId = createDocument(member);
        stubEvent("late1", "1", "Late sync");
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS)).withQueryParam("maxResults", equalTo("1"))
                .willReturn(okJson("{\"timeZone\":\"America/Los_Angeles\"}").withFixedDelay(3000)));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        MvcResult result;
        long deletionId;
        try {
            Future<MvcResult> importing = executor.submit(() -> mockMvc.perform(MockMvcRequestBuilders.post(calendarPath(member) + "/imports")
                            .cookie(member.session()).with(csrf()).contentType("application/json")
                            .content("{\"documentId\":" + documentId + ",\"eventId\":\"late1\"}"))
                    .andReturn());
            awaitTimeZoneRequest();
            deletionId = JSON.readTree(mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/deletions")
                            .cookie(member.session()).with(csrf()).contentType("application/json")
                            .content("{\"scope\":\"DOCUMENT\",\"documentId\":" + documentId + "}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
            result = importing.get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(result.getResponse().getStatus()).as("a document moved to the trash meanwhile still takes the copy").isEqualTo(201);
        long snapshotId = JSON.readTree(result.getResponse().getContentAsString()).get("source").get("id").asLong();
        assertThat(count("SELECT count(*) FROM document_source WHERE source_snapshot_id = ?", snapshotId)).isEqualTo(1);
        assertThat(count("SELECT (extract(epoch FROM a.occurred_at - s.fetched_at) * 1000)::bigint FROM source_snapshot s"
                + " JOIN audit_event a ON a.resource_id = s.id AND a.action = 'SOURCE_IMPORTED' WHERE s.id = ?", snapshotId))
                .as("fetched_at is when Google was read, before the slow time-zone call, not when the row was written")
                .isGreaterThanOrEqualTo(2500);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/deletions/" + deletionId + "/purge")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM source_snapshot WHERE id = ?", snapshotId))
                .as("deleting the document for good takes the copy with it, instead of leaving it linked to nothing").isZero();
    }

    @Test
    void deletingTheWorkspaceWaitsForACopyBeingRecordedRatherThanDeadlocking() throws Exception {
        Member member = signInAndConnectCalendar("subject-calendar-purge-lock");
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS)).withQueryParam("maxResults", equalTo("50")).willReturn(okJson("{\"items\":[]}")));
        mockMvc.perform(MockMvcRequestBuilders.get(calendarPath(member) + "/events")
                        .param("from", "2026-09-01T00:00:00-07:00").param("to", "2026-09-02T00:00:00-07:00").cookie(member.session()))
                .andExpect(status().isOk());
        long connectionId = count("SELECT id FROM connector_connection WHERE workspace_id = ?", member.workspaceId());
        long grantId = count("SELECT id FROM connector_resource_grant WHERE workspace_id = ?", member.workspaceId());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<MvcResult> deleting;
        try (Connection api = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_api", API_PASSWORD)) {
            api.setAutoCommit(false);
            try (PreparedStatement context = api.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                context.setString(1, String.valueOf(member.userId()));
                context.executeQuery();
            }
            // Recording a copy holds the connection first, exactly as the repository does ...
            api.prepareStatement("SELECT id FROM connector_connection WHERE workspace_id = " + member.workspaceId()
                    + " AND id = " + connectionId + " AND state <> 'DISCONNECTED' FOR SHARE").executeQuery();
            deleting = executor.submit(() -> mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/deletions")
                            .cookie(member.session()).with(csrf()).contentType("application/json").content("{\"scope\":\"WORKSPACE\"}"))
                    .andReturn());
            awaitABackendWaitingForALock();
            // ... and then the choice. A deletion that had taken the choices before the connections would now deadlock with this.
            api.prepareStatement("SET LOCAL lock_timeout = '10s'").execute();
            api.prepareStatement("SELECT id FROM connector_resource_grant WHERE id = " + grantId + " FOR SHARE").executeQuery();
            api.commit();
        }
        MvcResult deleted;
        try {
            deleted = deleting.get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(deleted.getResponse().getStatus()).as("the deletion waited, then went through").isEqualTo(200);
        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", member.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM connector_resource_grant WHERE workspace_id = ?", member.workspaceId())).isZero();
    }

    @Test
    void theDatabaseKeepsACopysOriginWholeAndDeletingTheWorkspaceRemovesIt() throws Exception {
        Member member = signInAndConnectCalendar("subject-calendar-rows");
        long documentId = createDocument(member);
        stubEvent("weekly1", "1", "Weekly sync");
        long snapshotId = importEvent(member, documentId, "weekly1").get("source").get("id").asLong();
        long artifactId = count("SELECT artifact_id FROM source_snapshot WHERE id = ?", snapshotId);
        long connectionId = count("SELECT origin_connection_id FROM source_snapshot WHERE id = ?", snapshotId);
        long grantId = count("SELECT origin_grant_id FROM source_snapshot WHERE id = ?", snapshotId);
        long spare = uploadPlainText(member, "An upload.");

        try (Connection owner = ownerConnection()) {
            for (String[] refused : List.of(
                    new String[] {"'GOOGLE_CALENDAR', NULL, NULL, NULL, NULL, NULL", "source_snapshot_origin_shape"},
                    new String[] {"'GOOGLE_CALENDAR', " + connectionId + ", " + grantId + ", 'x', '\"2\"', NULL", "source_snapshot_origin_shape"},
                    new String[] {"'ARTIFACT', NULL, NULL, 'x', '\"1\"', 'CALENDAR_EVENT_AS_TEXT'", "source_snapshot_origin_shape"},
                    new String[] {"'SOMETHING_ELSE', NULL, NULL, NULL, NULL, NULL", "source_snapshot_kind_known"})) {
                assertThatThrownBy(() -> owner.prepareStatement("INSERT INTO source_snapshot (workspace_id, artifact_id, kind, origin_connection_id,"
                        + " origin_grant_id, origin_external_id, origin_revision, origin_conversion) VALUES (" + member.workspaceId() + ", " + spare
                        + ", " + refused[0] + ")").execute())
                        .isInstanceOf(SQLException.class).hasMessageContaining(refused[1]);
            }
            assertThatThrownBy(() -> owner.prepareStatement(
                    "UPDATE source_snapshot SET origin_link = 'javascript:alert(1)' WHERE id = " + snapshotId).execute())
                    .isInstanceOf(SQLException.class).hasMessageContaining("source_snapshot_origin_bounded");
        }
        try (Connection api = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_api", API_PASSWORD)) {
            api.setAutoCommit(false);
            try (PreparedStatement context = api.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                context.setString(1, String.valueOf(member.userId()));
                context.executeQuery();
            }
            assertThat(api.prepareStatement("UPDATE source_snapshot SET origin_title = 'Rewritten' WHERE id = " + snapshotId).executeUpdate())
                    .as("a copy's origin is never rewritten").isZero();
            api.rollback();
        }

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/deletions")
                        .cookie(member.session()).with(csrf()).contentType("application/json").content("{\"scope\":\"WORKSPACE\"}"))
                .andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ?", member.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM connector_resource_grant WHERE workspace_id = ?", member.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", member.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM artifact WHERE id = ?", artifactId)).isZero();
        assertThat(GOOGLE.findAll(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathEqualTo("/revoke")))).hasSize(1);
    }

    // ---- helpers

    @Test
    void theSevenDayEndOfATestingTokenAsksToConnectAgainOnceAndConnectingAgainCarriesOn(CapturedOutput output) throws Exception {
        String subject = "subject-calendar-seven-days";
        Member member = signInAndConnectCalendar(subject);
        long documentId = createDocument(member);
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS)).withQueryParam("maxResults", equalTo("50")).willReturn(okJson("{\"items\":[]}")));
        listEvents(member).andExpect(status().isOk());
        long connectionId = count("SELECT id FROM connector_connection WHERE workspace_id = ?", member.workspaceId());
        long grantId = count("SELECT id FROM connector_resource_grant WHERE workspace_id = ?", member.workspaceId());
        stubEvent("weekly1", "1", "Weekly sync");
        long copiedBefore = importEvent(member, documentId, "weekly1").get("source").get("id").asLong();

        GOOGLE.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody(EXPIRED_OR_REVOKED)));
        listEvents(member).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONNECTION_RECONNECT_REQUIRED"))
                .andExpect(jsonPath("$.reason").value("TOKEN_REJECTED"));
        assertThat(text("SELECT state || ':' || reconnect_reason || ':' || (token_ciphertext IS NULL) FROM connector_connection WHERE id = ?",
                connectionId)).as("the refused token is not kept").isEqualTo("RECONNECT_REQUIRED:TOKEN_REJECTED:true");

        int tokenRequests = GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/token"))).size();
        listEvents(member).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("TOKEN_REJECTED"));
        int eventReads = GOOGLE.findAll(getRequestedFor(urlMatching(EVENTS + "/.*"))).size();
        importExpecting(member, documentId, "weekly1", 409).andExpect(jsonPath("$.reason").value("TOKEN_REJECTED"));
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/token"))))
                .as("Google is not asked again with a token it already refused").hasSize(tokenRequests);
        assertThat(GOOGLE.findAll(getRequestedFor(urlMatching(EVENTS + "/.*")))).hasSize(eventReads);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/workspaces/" + member.workspaceId() + "/connections").cookie(member.session()))
                .andExpect(jsonPath("$[0].state").value("RECONNECT_REQUIRED"))
                .andExpect(jsonPath("$[0].reconnectReason").value("TOKEN_REJECTED"));

        // Connecting again from the same account carries on: the same connection, the same choice of calendar.
        resetGoogle();
        assertThat(consent(member, subject)).contains("google=connected");
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS)).withQueryParam("maxResults", equalTo("50")).willReturn(okJson("{\"items\":[]}")));
        listEvents(member).andExpect(status().isOk());
        assertThat(text("SELECT id || ':' || state FROM connector_connection WHERE workspace_id = ?", member.workspaceId()))
                .isEqualTo(connectionId + ":ACTIVE");
        assertThat(count("SELECT id FROM connector_resource_grant WHERE workspace_id = ? AND revoked_at IS NULL", member.workspaceId()))
                .as("the same choice of calendar, not a new one").isEqualTo(grantId);
        stubEvent("weekly1", "1", "Weekly sync");
        JsonNode again = importEvent(member, documentId, "weekly1");
        assertThat(again.get("newCopy").asBoolean()).as("so the copy made before is linked, not made again").isFalse();
        assertThat(again.get("source").get("id").asLong()).isEqualTo(copiedBefore);

        assertThat(output.getAll()).doesNotContain("Token has been expired or revoked").doesNotContain(REFRESH_TOKEN).doesNotContain(ACCESS_TOKEN);
    }

    @Test
    void aPermissionTakenBackAsksToConnectAgainAndAReadRefusedDespiteItIsSetupThatKeepsTheToken(CapturedOutput output) throws Exception {
        // A refresh that names what the access now carries, and the calendar is not among it.
        Member narrowed = signInAndConnectCalendar("subject-calendar-narrowed");
        GOOGLE.stubFor(post(urlPathEqualTo("/token")).willReturn(okJson("""
                {"access_token":"%s","expires_in":3599,"token_type":"Bearer","scope":"openid https://www.googleapis.com/auth/userinfo.email"}
                """.formatted(ACCESS_TOKEN))));
        listEvents(narrowed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONNECTION_RECONNECT_REQUIRED"))
                .andExpect(jsonPath("$.reason").value("PERMISSION_MISSING"));
        assertThat(text("SELECT state || ':' || reconnect_reason FROM connector_connection WHERE workspace_id = ?", narrowed.workspaceId()))
                .isEqualTo("RECONNECT_REQUIRED:PERMISSION_MISSING");

        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/revoke"))))
                .as("waiting to be connected again is not a disconnect: Google is not asked to revoke anything").isEmpty();

        // A refresh that names the permission, then a read refused for want of it: Brownie asked for more than the
        // permission covers. Connecting again would give the same permission and meet the same refusal, so this is setup,
        // said to whoever runs Brownie, and the person's connection and token are left as they were.
        resetGoogle();
        Member refusedRead = signInAndConnectCalendar("subject-calendar-read-refused");
        long documentId = createDocument(refusedRead);
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/weekly1")).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json").withBody(SCOPE_INSUFFICIENT)));
        importExpecting(refusedRead, documentId, "weekly1", 503).andExpect(jsonPath("$.code").value("CONNECTOR_MISCONFIGURED"));
        assertThat(text("SELECT state || ':' || (token_ciphertext IS NOT NULL) FROM connector_connection WHERE workspace_id = ?",
                refusedRead.workspaceId())).isEqualTo("ACTIVE:true");
        assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ?", refusedRead.workspaceId())).isZero();
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/revoke")))).isEmpty();
        assertThat(output.getAll()).contains("Google refused the request for the calendar event (HTTP 403, insufficientPermissions");

        // At consent the exchange has already named every permission asked for, so the account lookup refused for one is
        // setup too, and nothing is kept.
        Member refusedLookup = signIn("subject-calendar-consent-refused");
        GOOGLE.stubFor(get(urlPathEqualTo("/userinfo")).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json").withBody(SCOPE_INSUFFICIENT)));
        assertThat(startAndAnswerConsent(refusedLookup, "subject-calendar-consent-refused", "CALENDAR_EVENTS"))
                .endsWith("google=failed&access=calendar_events&reason=not_configured");
        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", refusedLookup.workspaceId())).isZero();
    }

    @Test
    void onlyReadsReachGooglesApisDriveIsNeverListedAndNoTokenOrSecretLeavesTheServer(CapturedOutput output) throws Exception {
        String subject = "subject-calendar-gate";
        Member member = signIn(subject);
        List<String> answers = new ArrayList<>();

        GOOGLE.stubFor(get(urlPathEqualTo("/userinfo")).willReturn(okJson("{\"sub\":\"google-" + subject + "\",\"email\":\"gate@example.org\"}")));
        answers.add(startAndAnswerConsent(member, subject, "CALENDAR_EVENTS"));
        GOOGLE.stubFor(post(urlPathEqualTo("/token")).withRequestBody(containing("code=code-drive")).willReturn(okJson("""
                {"access_token":"%s","expires_in":3599,"refresh_token":"%s","token_type":"Bearer","scope":"https://www.googleapis.com/auth/drive.file"}
                """.formatted(DRIVE_ACCESS_TOKEN, DRIVE_REFRESH_TOKEN))));
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/about"))
                .willReturn(okJson("{\"user\":{\"permissionId\":\"gate-permission\",\"emailAddress\":\"gate@example.org\"}}")));
        answers.add(startAndAnswerConsent(member, "drive", "DRIVE_FILES"));
        assertThat(answers).allSatisfy(location -> assertThat(location).contains("google=connected"));

        long documentId = createDocument(member);
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS)).withQueryParam("maxResults", equalTo("50")).willReturn(okJson("""
                {"items":[{"id":"weekly1","summary":"Weekly sync","start":{"dateTime":"2026-09-24T10:00:00-07:00"},"end":{"dateTime":"2026-09-24T10:30:00-07:00"}}]}
                """)));
        stubEvent("weekly1", "1", "Weekly sync");
        answers.add(answer(listEvents(member).andExpect(status().isOk()).andReturn()));
        answers.add(answer(importExpecting(member, documentId, "weekly1", 201).andReturn()));
        answers.add(answer(importExpecting(member, documentId, "weekly1", 201).andReturn()));
        answers.add(answer(mockMvc.perform(MockMvcRequestBuilders.get(documentPath(member, documentId) + "/sources").cookie(member.session()))
                .andExpect(status().isOk()).andReturn()));
        answers.add(answer(mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/workspaces/" + member.workspaceId() + "/connections")
                .cookie(member.session())).andExpect(status().isOk()).andReturn()));
        answers.add(answer(mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/connections/google/disconnect")
                .cookie(member.session()).with(csrf())).andExpect(status().isOk()).andReturn()));

        List<LoggedRequest> sent = GOOGLE.findAll(anyRequestedFor(urlMatching("/.*")));
        assertThat(sent).allSatisfy(request -> {
            String method = request.getMethod().getName();
            String path = request.getUrl().split("\\?")[0];
            assertThat(method.equals("GET") || (method.equals("POST") && (path.equals("/token") || path.equals("/revoke"))))
                    .as("%s %s: nothing but a read reaches Google's APIs; only the token and revocation endpoints are posted to", method, path)
                    .isTrue();
            assertThat(URLDecoder.decode(request.getUrl(), StandardCharsets.UTF_8))
                    .as("a token travels in a header or a form body, never in an address, encoded or not")
                    .doesNotContain(ACCESS_TOKEN).doesNotContain(DRIVE_ACCESS_TOKEN).doesNotContain(REFRESH_TOKEN).doesNotContain(DRIVE_REFRESH_TOKEN);
        });
        assertThat(sent.stream().map(request -> request.getUrl().split("\\?")[0]).filter(path -> path.startsWith("/drive/")))
                .as("Drive is asked whose account it is, and never for a list of files").containsOnly("/drive/v3/about");
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/revoke")))).as("both connections are revoked").hasSize(2);
        assertOnlyReadsOfThePrimaryCalendar();

        List<String> secrets = new ArrayList<>();
        for (String secret : List.of(ACCESS_TOKEN, REFRESH_TOKEN, DRIVE_ACCESS_TOKEN, DRIVE_REFRESH_TOKEN, CLIENT_SECRET, TOKEN_KEY,
                "code-" + subject, "code-drive")) {
            // As written, and as it would look had something put it in an address or a form: '/', '+' and '=' change.
            secrets.add(secret);
            secrets.add(URLEncoder.encode(secret, StandardCharsets.UTF_8));
        }
        assertThat(String.join("\n", answers)).as("nothing a browser is sent").doesNotContain(secrets.toArray(String[]::new));
        assertThat(output.getAll()).as("nothing logged").doesNotContain(secrets.toArray(String[]::new));
        long artifactId = count("SELECT artifact_id FROM source_snapshot WHERE workspace_id = ? AND kind = 'GOOGLE_CALENDAR'", member.workspaceId());
        assertThat(text("SELECT normalized_text FROM plain_text_extraction_version WHERE artifact_id = ?", artifactId))
                .as("nor the copy, which is all of Google that a request to the model can ever include").doesNotContain(secrets.toArray(String[]::new));
    }

    @Test
    void aDocumentInTheTrashTakesNoCopyAndGoogleIsNotAsked() throws Exception {
        Member member = signInAndConnectCalendar("subject-calendar-trashed-first");
        long documentId = createDocument(member);
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/deletions")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"scope\":\"DOCUMENT\",\"documentId\":" + documentId + "}"))
                .andExpect(status().isCreated());
        stubEvent("weekly1", "1", "Weekly sync");
        int sentBefore = GOOGLE.getAllServeEvents().size();

        importExpecting(member, documentId, "weekly1", 404).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(GOOGLE.getAllServeEvents()).as("nothing at all is sent to Google, not even a token refresh").hasSize(sentBefore);
        assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ?", member.workspaceId())).isZero();
    }

    private static void awaitTimeZoneRequest() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (GOOGLE.findAll(getRequestedFor(urlPathEqualTo(EVENTS)).withQueryParam("maxResults", equalTo("1"))).isEmpty()) {
            assertThat(System.nanoTime()).as("the import reached Google").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private static void awaitABackendWaitingForALock() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection superuser = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            while (true) {
                try (ResultSet rs = superuser.prepareStatement(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = 'brownie' AND wait_event_type = 'Lock'").executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return;
                    }
                }
                assertThat(System.nanoTime()).as("the deletion reached the connection's lock").isLessThan(deadline);
                Thread.sleep(50);
            }
        }
    }

    private void assertOnlyReadsOfThePrimaryCalendar() {
        List<LoggedRequest> calendar = GOOGLE.findAll(anyRequestedFor(urlMatching("/calendar/.*")));
        assertThat(calendar).isNotEmpty();
        assertThat(calendar).allSatisfy(request -> {
            assertThat(request.getMethod().getName()).isEqualTo("GET");
            assertThat(request.getUrl()).startsWith(EVENTS);
        });
    }

    private JsonNode importEvent(Member member, long documentId, String eventId) throws Exception {
        MvcResult result = importExpecting(member, documentId, eventId, 201)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions importExpecting(Member member, long documentId, String eventId, int expected)
            throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(calendarPath(member) + "/imports")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"documentId\":" + documentId + ",\"eventId\":\"" + eventId + "\"}"))
                .andExpect(status().is(expected));
    }

    private static void stubEvent(String id, String etag, String summary) {
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/" + id)).willReturn(okJson("""
                {"id":"%s","status":"confirmed","summary":"%s","description":"<p>Bring the Q3 numbers.</p>","location":"Room 4",
                 "start":{"dateTime":"2026-09-24T10:00:00-07:00","timeZone":"America/Los_Angeles"},
                 "end":{"dateTime":"2026-09-24T10:30:00-07:00","timeZone":"America/Los_Angeles"},
                 "attendees":[{"email":"guest.one@example.org","displayName":"Guest One","responseStatus":"accepted"}],
                 "htmlLink":"https://www.google.com/calendar/event?eid=%s","updated":"2026-09-20T18:03:04.356Z","etag":"\\"%s\\""}
                """.formatted(id, summary, id, etag))));
    }

    private Member signInAndConnectCalendar(String subject) throws Exception {
        Member member = signIn(subject);
        assertThat(consent(member, subject)).contains("google=connected");
        return member;
    }

    /** Connects this person's calendar as the Google account named after {@code subject}; the address Google's answer lands on. */
    private String consent(Member member, String subject) throws Exception {
        GOOGLE.stubFor(get(urlPathEqualTo("/userinfo")).willReturn(okJson("{\"sub\":\"google-" + subject + "\",\"email\":\"" + subject + "@example.org\"}")));
        return startAndAnswerConsent(member, subject, "CALENDAR_EVENTS");
    }

    /** Starts a consent and brings Google's answer back with the code {@code code-<subject>}; the address it lands on, with what the start answered. */
    private String startAndAnswerConsent(Member member, String subject, String access) throws Exception {
        MvcResult started = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/connections/google")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"access\":\"" + access + "\",\"returnTo\":\"/connections\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String state = queryOf(JSON.readTree(started.getResponse().getContentAsString()).get("authorizationUrl").asString()).get("state");
        MvcResult answered = mockMvc.perform(MockMvcRequestBuilders.get(CALLBACK).param("code", "code-" + subject).param("state", state)
                        .cookie(member.session()))
                .andExpect(status().isFound())
                .andReturn();
        return answer(started) + "\n" + answered.getResponse().getRedirectedUrl();
    }

    private org.springframework.test.web.servlet.ResultActions listEvents(Member member) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(calendarPath(member) + "/events")
                .param("from", "2026-09-01T00:00:00-07:00").param("to", "2026-09-30T00:00:00-07:00").cookie(member.session()));
    }

    /** Everything a browser is sent in reply: the headers, the body, and where it is sent next. */
    private static String answer(MvcResult result) throws Exception {
        StringBuilder all = new StringBuilder();
        for (String name : result.getResponse().getHeaderNames()) {
            all.append(name).append(": ").append(String.join(", ", result.getResponse().getHeaders(name))).append('\n');
        }
        return all.append(result.getResponse().getContentAsString()).toString();
    }

    private long createDocument(Member member) throws Exception {
        builtInTemplateProvisioningService.ensureBuiltInTemplates(member.workspaceId(), member.userId());
        JsonNode templates = JSON.readTree(mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/workspaces/" + member.workspaceId() + "/templates")
                        .cookie(member.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode template = null;
        for (JsonNode each : templates) {
            if ("Flowing meeting minutes".equals(each.get("displayName").asString())) {
                template = each;
            }
        }
        assertThat(template).isNotNull();
        String body = "{\"title\":\"Weekly Sync\",\"templateId\":" + template.get("id").asLong()
                + ",\"templateVersionId\":" + template.get("currentActiveVersionId").asLong()
                + ",\"fields\":{},\"initialRevisionReason\":\"Created for a calendar import test.\"}";
        return JSON.readTree(mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/documents")
                        .cookie(member.session()).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private long uploadPlainText(Member member, String text) throws Exception {
        String uploads = "/api/v1/workspaces/" + member.workspaceId() + "/uploads";
        long artifactId = JSON.readTree(mockMvc.perform(MockMvcRequestBuilders.post(uploads)
                        .cookie(member.session()).with(csrf()).contentType("application/json").content("{\"filename\":\"notes.txt\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(MockMvcRequestBuilders.put(uploads + "/" + artifactId + "/content")
                        .cookie(member.session()).with(csrf()).contentType("application/octet-stream")
                        .content(text.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.post(uploads + "/" + artifactId + "/complete").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());
        return artifactId;
    }

    private Member signIn(String subject) {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow().id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, ISSUER)
                .claim(IdTokenClaimNames.SUB, subject)
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra"));
        Session session = createAuthenticatedSession(sessionRepository, context);
        Cookie cookie = new Cookie("SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        return new Member(cookie, workspaceId, userId);
    }

    private static <S extends Session> S createAuthenticatedSession(FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    private record Member(Cookie session, long workspaceId, long userId) {
    }

    private static String calendarPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/connections/google/calendar";
    }

    private static String documentPath(Member member, long documentId) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/documents/" + documentId;
    }

    private static Map<String, String> queryOf(String url) {
        Map<String, String> values = new HashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams()
                .forEach((name, list) -> values.put(name, URLDecoder.decode(list.getFirst(), StandardCharsets.UTF_8)));
        return values;
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
    }

    private static long count(String sql, long parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String text(String sql, long parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    private static WireMockServer startedGoogle() {
        WireMockServer server = new WireMockServer(0);
        server.start();
        return server;
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
