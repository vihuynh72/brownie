package io.github.vihuynh72.brownie.api.action;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adding an approved event to a person's own calendar, end to end: Brownie's
 * real routes, sessions, database and policies, with Google stood in for by
 * WireMock over real HTTP. What is proved is what the person would meet and
 * what Google would receive: a connection of its own that asks for adding
 * events and nothing it does not need, one event sent once under Brownie's
 * id to the main calendar telling nobody, read back before it counts; a lost
 * answer that is never sent again and is settled only by Google holding the
 * event or saying it was deleted; times checked as a person means them; and
 * no token or content anywhere it should not be.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "brownie.public-origin=http://localhost:8081",
        "brownie.web.origin=http://localhost:5173",
        "brownie.connectors.google.client-id=stand-in-client.apps.googleusercontent.com",
        "brownie.connectors.google.client-secret=" + CalendarEventIntegrationTest.CLIENT_SECRET,
        "brownie.connectors.token-key-id=test-key",
        "brownie.connectors.google.actions-offered=true"})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class CalendarEventIntegrationTest {

    static final String CLIENT_SECRET = "stand-in-client-secret-value";
    private static final String ISSUER = "https://issuer-calendar-event";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String CALLBACK = "/api/v1/connectors/google/callback";
    private static final String ACCESS_TOKEN = "ya29.calendar-writing-access-stand-in";
    private static final String REFRESH_TOKEN = "1//calendar-writing-refresh-stand-in";
    private static final String WRITE_SCOPE = "https://www.googleapis.com/auth/calendar.events.owned";
    private static final String EVENTS = "/calendar/v3/calendars/primary/events";

    private static final WireMockServer GOOGLE = startedGoogle();
    private static final String TOKEN_KEY = randomKey();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("brownie")
            .withUsername("postgres")
            .withPassword("postgres_bootstrap_only")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("").toAbsolutePath().getParent().getParent()
                            .resolve("infra/local/postgres/init/01-app-roles.sql")), "/docker-entrypoint-initdb.d/01-app-roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> "brownie_api_local_only");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("brownie.connectors.token-key", () -> TOKEN_KEY);
        registry.add("brownie.connectors.google.token-uri", () -> GOOGLE.baseUrl() + "/token");
        registry.add("brownie.connectors.google.revocation-uri", () -> GOOGLE.baseUrl() + "/revoke");
        registry.add("brownie.connectors.google.user-info-uri", () -> GOOGLE.baseUrl() + "/userinfo");
        registry.add("brownie.connectors.google.api-base-uri", GOOGLE::baseUrl);
        registry.add("brownie.connectors.google.docs-api-base-uri", GOOGLE::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private ExtractionVersionRepository extractionVersionRepository;

    @Autowired
    private RevisionService revisionService;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void resetGoogle() {
        GOOGLE.resetAll();
        GOOGLE.stubFor(post(urlPathEqualTo("/token")).willReturn(okJson("""
                {"access_token":"%s","expires_in":3599,"refresh_token":"%s","token_type":"Bearer",
                 "scope":"openid https://www.googleapis.com/auth/userinfo.email %s"}
                """.formatted(ACCESS_TOKEN, REFRESH_TOKEN, WRITE_SCOPE))));
        GOOGLE.stubFor(post(urlPathEqualTo("/revoke")).willReturn(aResponse().withStatus(200)));
        GOOGLE.stubFor(get(urlPathEqualTo("/userinfo")).willReturn(okJson("{\"sub\":\"google-owner\",\"email\":\"owner@example.org\"}")));
    }

    @AfterAll
    static void stopGoogle() {
        GOOGLE.stop();
    }

    @Test
    void anApprovedEventIsAddedOnceToTheMainCalendarTellingNobodyAndCountsOnlyAfterReadingItBack(CapturedOutput output) throws Exception {
        Member member = signInAndConnect("subject-calendar-event");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/capabilities").cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleActions[?(@ == 'CALENDAR_CREATE_EVENT')]").exists())
                .andExpect(jsonPath("$.googleConnectorAccess[?(@ == 'CALENDAR_EVENT_CREATION')]").exists());

        JsonNode proposed = propose(member, timedBody(member, "2026-10-05T09:00", "2026-10-05T10:30"));
        assertThat(proposed.get("type").asString()).isEqualTo("CALENDAR_CREATE_EVENT");
        JsonNode payload = proposed.get("payload");
        assertThat(payload.get("event").get("title").asString()).isEqualTo("Budget review");
        assertThat(payload.get("event").get("when").get("start").asString()).isEqualTo("2026-10-05T09:00:00+02:00");
        assertThat(payload.get("event").get("when").get("timeZone").asString()).isEqualTo("Europe/Paris");
        assertThat(payload.get("target").get("guests").asString()).isEqualTo("NONE");
        assertThat(payload.get("target").get("notifications").asString()).isEqualTo("NONE");
        assertThat(payload.get("account").get("email").asString()).isEqualTo("owner@example.org");
        String eventId = text("SELECT provider_key FROM action_request WHERE id = ?", proposed.get("id").asLong());
        assertThat(eventId).matches("[a-v0-9]{26}");
        assertThat(proposed.toString()).as("the event's id is never shown").doesNotContain(eventId);
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(EVENTS)))).as("proposing sends nothing").isEmpty();

        GOOGLE.stubFor(post(urlPathEqualTo(EVENTS)).willReturn(okJson("""
                {"id":"%s","status":"confirmed","htmlLink":"https://www.google.com/calendar/event?eid=added"}
                """.formatted(eventId))));
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/" + eventId)).willReturn(okJson(heldEvent(eventId))));

        mockMvc.perform(approve(member, proposed, "0".repeat(64)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTION_PAYLOAD_MISMATCH"));
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(EVENTS)))).as("a wrong hash sends nothing").isEmpty();

        JsonNode added = read(mockMvc.perform(approve(member, proposed, proposed.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(added.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(added.get("verification").asString()).isEqualTo("MATCHED");
        assertThat(added.get("externalLink").asString()).isEqualTo("https://www.google.com/calendar/event?eid=added");

        List<LoggedRequest> inserts = GOOGLE.findAll(postRequestedFor(urlPathEqualTo(EVENTS)));
        assertThat(inserts).hasSize(1);
        assertThat(queryOf(inserts.getFirst().getUrl())).isEqualTo(Map.of("sendUpdates", "none", "conferenceDataVersion", "0",
                "supportsAttachments", "false"));
        JsonNode sent = JSON.readTree(inserts.getFirst().getBodyAsString());
        assertThat(sent.get("id").asString()).isEqualTo(eventId);
        assertThat(sent.has("attendees")).isFalse();
        assertThat(sent.get("visibility").asString()).isEqualTo("private");

        JsonNode again = read(mockMvc.perform(approve(member, proposed, proposed.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(again.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(EVENTS)))).hasSize(1);

        // An all-day event with no place, answered as Google really answers one: no location field at all, and the
        // calendar's usual reminders written out as the event's own. That is the event approved.
        Map<String, Object> noPlace = new HashMap<>(JSON.readValue(body(member, "Offsite", "", true, null, "2026-10-06", "2026-10-07"), Map.class));
        noPlace.put("location", "");
        JsonNode offsite = propose(member, JSON.writeValueAsString(noPlace));
        String offsiteId = text("SELECT provider_key FROM action_request WHERE id = ?", offsite.get("id").asLong());
        GOOGLE.stubFor(post(urlPathEqualTo(EVENTS)).willReturn(okJson("{\"id\":\"" + offsiteId + "\",\"status\":\"confirmed\"}")));
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/" + offsiteId)).willReturn(okJson("""
                {"id":"%s","status":"confirmed","eventType":"default","summary":"Offsite",
                 "start":{"date":"2026-10-06"},"end":{"date":"2026-10-08"},"visibility":"private","guestsCanInviteOthers":false,
                 "reminders":{"useDefault":false,"overrides":[{"method":"popup","minutes":30}]}}
                """.formatted(offsiteId))));
        JsonNode allDay = read(mockMvc.perform(approve(member, offsite, offsite.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(allDay.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(allDay.get("verification").asString()).isEqualTo("MATCHED");
        JsonNode sentAllDay = JSON.readTree(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(EVENTS))).getLast().getBodyAsString());
        assertThat(sentAllDay.get("start").get("date").asString()).isEqualTo("2026-10-06");
        assertThat(sentAllDay.get("end").get("date").asString()).as("the day after the last, as Google counts").isEqualTo("2026-10-08");
        assertThat(sentAllDay.has("location")).isFalse();

        for (LoggedRequest request : GOOGLE.findAll(anyRequestedFor(anyUrl()))) {
            String path = request.getUrl().split("\\?")[0];
            if (!request.getMethod().getName().equals("GET")) {
                assertThat(path).as(request.getMethod().getName() + " " + path).isIn("/token", "/revoke", EVENTS);
            }
        }
        assertThat(count("SELECT count(*) FROM audit_event WHERE resource_type = 'action' AND resource_id = ?", added.get("id").asLong()))
                .as("approved, sent, finished").isEqualTo(3);
        assertThat(text("SELECT string_agg(details::text, ' ') FROM audit_event WHERE resource_type = 'action' AND resource_id = ?",
                added.get("id").asLong())).doesNotContain("Budget review").doesNotContain(eventId);
        assertThat(output.getAll()).doesNotContain(ACCESS_TOKEN).doesNotContain(REFRESH_TOKEN).doesNotContain(CLIENT_SECRET)
                .doesNotContain("Budget review");
    }

    @Test
    void anEventWhoseAnswerWasLostIsNeverSentAgainAndIsSettledOnlyByGoogleHoldingOrHavingDeletedIt() throws Exception {
        Member member = signInAndConnect("subject-calendar-event-lost");
        JsonNode proposed = propose(member, timedBody(member, "2026-10-06T09:00", "2026-10-06T10:30"));
        String eventId = text("SELECT provider_key FROM action_request WHERE id = ?", proposed.get("id").asLong());
        GOOGLE.stubFor(post(urlPathEqualTo(EVENTS)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        JsonNode lost = read(mockMvc.perform(approve(member, proposed, proposed.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(lost.get("state").asString()).isEqualTo("OUTCOME_UNKNOWN");
        read(mockMvc.perform(approve(member, proposed, proposed.get("payloadHash").asString())).andExpect(status().isOk()).andReturn());
        JsonNode second = propose(member, timedBody(member, "2026-10-06T09:00", "2026-10-06T10:30"));
        mockMvc.perform(approve(member, second, second.get("payloadHash").asString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTION_SIBLING_UNRESOLVED"));

        // Google does not know the id: that proves nothing, since Google forgets an event some time after it is deleted.
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/" + eventId)).willReturn(aResponse().withStatus(404)));
        JsonNode stillUnknown = read(mockMvc.perform(reconcile(member, proposed)).andExpect(status().isOk()).andReturn());
        assertThat(stillUnknown.get("state").asString()).isEqualTo("OUTCOME_UNKNOWN");

        // The event had in fact been added: it is compared, and counts.
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/" + eventId)).willReturn(okJson(heldEvent(eventId)
                .replace("2026-10-05T09:00:00+02:00", "2026-10-06T09:00:00+02:00").replace("2026-10-05T10:30:00+02:00", "2026-10-06T10:30:00+02:00"))));
        JsonNode settled = read(mockMvc.perform(reconcile(member, proposed)).andExpect(status().isOk()).andReturn());
        assertThat(settled.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(settled.get("verification").asString()).isEqualTo("MATCHED");
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(EVENTS)))).as("sent once, whatever happened next").hasSize(1);

        // Another event, lost the same way, that the person deleted in their calendar before anyone asked.
        JsonNode other = propose(member, timedBody(member, "2026-10-07T09:00", "2026-10-07T10:00"));
        String otherId = text("SELECT provider_key FROM action_request WHERE id = ?", other.get("id").asLong());
        read(mockMvc.perform(approve(member, other, other.get("payloadHash").asString())).andExpect(status().isOk()).andReturn());
        GOOGLE.stubFor(get(urlPathEqualTo(EVENTS + "/" + otherId)).willReturn(aResponse().withStatus(410)));
        JsonNode removed = read(mockMvc.perform(reconcile(member, other)).andExpect(status().isOk()).andReturn());
        assertThat(removed.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(removed.get("verification").asString()).isEqualTo("REMOVED_AFTERWARDS");
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(EVENTS)))).hasSize(2);
    }

    @Test
    void timesAreCheckedAsAPersonMeansThemAndNothingIsRecordedForOneThatIsRefused() throws Exception {
        Member member = signInAndConnect("subject-calendar-event-times");

        mockMvc.perform(proposeRequest(member, timedBody(member, "2026-03-29T02:30", "2026-03-29T04:00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTION_NOT_PROPOSABLE"))
                .andExpect(jsonPath("$.reason").value("TIME_SKIPPED"));
        mockMvc.perform(proposeRequest(member, body(member, "Budget review", "See <b>this</b>", false, "Europe/Paris",
                        "2026-10-05T09:00", "2026-10-05T10:00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INVALID"));
        mockMvc.perform(proposeRequest(member, body(member, "Budget review", "", false, "+02:00", "2026-10-05T09:00", "2026-10-05T10:00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INVALID"));
        mockMvc.perform(MockMvcRequestBuilders.post(actionsPath(member) + "/calendar-events")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"documentId\":" + member.documentId() + ",\"title\":\"x\",\"start\":\"2026-10-05\",\"end\":\"2026-10-05\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(count("SELECT count(*) FROM action_request WHERE workspace_id = ?", member.workspaceId())).isZero();

        JsonNode twice = propose(member, timedBody(member, "2026-10-25T02:30", "2026-10-25T04:00"));
        JsonNode when = twice.get("payload").get("event").get("when");
        assertThat(when.get("start").asString()).as("the first 02:30, before the clocks go back").isEqualTo("2026-10-25T02:30:00+02:00");
        assertThat(when.get("startIsFirstOfTwo").asBoolean()).isTrue();

        JsonNode allDay = propose(member, body(member, "Offsite", "", true, null, "2026-10-05", "2026-10-06"));
        JsonNode days = allDay.get("payload").get("event").get("when");
        assertThat(days.get("allDay").asBoolean()).isTrue();
        assertThat(days.get("startDate").asString()).isEqualTo("2026-10-05");
        assertThat(days.get("endDate").asString()).as("the end day not included, as Google counts").isEqualTo("2026-10-07");
    }

    // --- fixtures ---

    private record Member(Cookie session, long workspaceId, long userId, long documentId) {
    }

    private static String heldEvent(String eventId) {
        return """
                {"id":"%s","status":"confirmed","eventType":"default","summary":"Budget review","description":"Bring the figures.",
                 "location":"Town hall",
                 "start":{"dateTime":"2026-10-05T09:00:00+02:00","timeZone":"Europe/Paris"},
                 "end":{"dateTime":"2026-10-05T10:30:00+02:00","timeZone":"Europe/Paris"},
                 "reminders":{"useDefault":true},"visibility":"private","htmlLink":"https://www.google.com/calendar/event?eid=added"}
                """.formatted(eventId);
    }

    private static String timedBody(Member member, String start, String end) {
        return body(member, "Budget review", "Bring the figures.", false, "Europe/Paris", start, end);
    }

    private static String body(Member member, String title, String description, boolean allDay, String zone, String start, String end) {
        Map<String, Object> body = new HashMap<>();
        body.put("documentId", member.documentId());
        body.put("title", title);
        body.put("description", description);
        body.put("location", "Town hall");
        body.put("allDay", allDay);
        body.put("timeZone", zone);
        body.put("start", start);
        body.put("end", end);
        return JSON.writeValueAsString(body);
    }

    private JsonNode propose(Member member, String body) throws Exception {
        return read(mockMvc.perform(proposeRequest(member, body)).andExpect(status().isCreated()).andReturn());
    }

    private MockHttpServletRequestBuilder proposeRequest(Member member, String body) {
        return MockMvcRequestBuilders.post(actionsPath(member) + "/calendar-events")
                .cookie(member.session()).with(csrf()).contentType("application/json").content(body);
    }

    private MockHttpServletRequestBuilder approve(Member member, JsonNode action, String hash) {
        return MockMvcRequestBuilders.post(actionsPath(member) + "/" + action.get("id").asLong() + "/approve")
                .cookie(member.session()).with(csrf()).contentType("application/json")
                .content("{\"payloadHash\":\"" + hash + "\"}");
    }

    private MockHttpServletRequestBuilder reconcile(Member member, JsonNode action) {
        return MockMvcRequestBuilders.post(actionsPath(member) + "/" + action.get("id").asLong() + "/reconcile")
                .cookie(member.session()).with(csrf());
    }

    private static String actionsPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/actions";
    }

    private Member signInAndConnect(String subject) throws Exception {
        Member member = signIn(subject);
        MvcResult started = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/connections/google")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"access\":\"CALENDAR_EVENT_CREATION\",\"returnTo\":\"/connections\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String url = JSON.readTree(started.getResponse().getContentAsString()).get("authorizationUrl").asString();
        assertThat(queryOf(url).get("scope")).as("adding events, and who the account is; never reading every calendar")
                .isEqualTo("openid email " + WRITE_SCOPE);
        String state = queryOf(url).get("state");
        MvcResult answered = mockMvc.perform(MockMvcRequestBuilders.get(CALLBACK).param("code", "code-" + subject).param("state", state)
                        .cookie(member.session()))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(answered.getResponse().getRedirectedUrl()).contains("google=connected").contains("access=calendar_event_creation");
        return member;
    }

    private Member signIn(String subject) throws SQLException {
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
        return new Member(cookie, workspaceId, userId, newDocument(workspaceId, userId));
    }

    private static <S extends Session> S createAuthenticatedSession(FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    private long newDocument(long workspaceId, long userId) throws SQLException {
        long artifactId;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                context.setString(1, String.valueOf(userId));
                context.executeQuery();
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO artifact (workspace_id, blob_key, status, byte_count,"
                    + " detected_media_type) VALUES (?, ?, 'READY', 100, 'DOCX') RETURNING id")) {
                insert.setLong(1, workspaceId);
                insert.setString(2, "test-blob-" + UUID.randomUUID());
                try (ResultSet rs = insert.executeQuery()) {
                    rs.next();
                    artifactId = rs.getLong(1);
                }
            }
            connection.commit();
        }
        StructuralNode control = new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode root = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(control));
        ExtractionVersion extraction = extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "test-parser-v1",
                new DocxStructuralGraph("test-parser-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, root))));
        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", artifactId, extraction.id());
        TemplateVersion draft = templateRepository.replaceDraftBindings(workspaceId, userId, template.id(), 1, List.of(new FieldDefinition(
                "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.title"))));
        TemplateVersion version = templateRepository.activate(workspaceId, userId, template.id(), draft.versionNumber());
        return revisionService.createDocument(workspaceId, userId, new IdempotencyKey("create-" + UUID.randomUUID()),
                CanonicalRequestHash.sha256OfCanonicalText("create-" + UUID.randomUUID()), "Budget minutes", version.templateId(), version.id(),
                new DocumentContent(Map.of("meeting.title", new FieldValue.TextValue("Budget review"))), Map.of(), "initial draft")
                .document().id();
    }

    private static JsonNode read(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static Map<String, String> queryOf(String url) {
        Map<String, String> values = new HashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams()
                .forEach((name, list) -> values.put(name, URLDecoder.decode(list.getFirst(), StandardCharsets.UTF_8)));
        return values;
    }

    private static long count(String sql, long parameter) throws SQLException {
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String text(String sql, long parameter) throws SQLException {
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
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
