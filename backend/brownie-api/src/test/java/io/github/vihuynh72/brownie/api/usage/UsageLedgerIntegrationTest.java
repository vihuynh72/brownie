package io.github.vihuynh72.brownie.api.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The model allowance is the owner's money, so this does not trust what a
 * route says it spent: it reads the ledger as the table owner, counts the
 * calls the (fake) model actually received, and checks the three things
 * that matter. Every paid request is written down with who made it and
 * what the provider billed; an allowance that is used up refuses the next
 * request before it is sent, not after; and nobody learns what anybody
 * else spent. The limits are made tiny here so a few cents' worth of seeded
 * rows reaches them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "brownie.usage.workspace-monthly-limit-usd=0.05",
        "brownie.usage.global-monthly-limit-usd=0.08"})
@Testcontainers
@Import(UsageLedgerIntegrationTest.CountingModelGatewayConfig.class)
class UsageLedgerIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-usage-ledger";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("brownie")
            .withUsername("postgres")
            .withPassword(BOOTSTRAP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptPath()), "/docker-entrypoint-initdb.d/01-app-roles.sql");

    @Container
    static final AzuriteContainer AZURITE = new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.37.0");

    @Container
    static final GenericContainer<?> CLAMAV = new GenericContainer<>(org.testcontainers.utility.DockerImageName.parse("clamav/clamav-debian:1.4"))
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
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    static final AtomicInteger MODEL_CALLS = new AtomicInteger();

    @TestConfiguration
    static class CountingModelGatewayConfig {
        @Bean
        @Primary
        ModelGateway countingModelGateway() {
            return request -> {
                MODEL_CALLS.incrementAndGet();
                return new ModelCompletion.Success("{\"value\":\"Shorter title\"}", new ModelUsage(400, 120));
            };
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
    private javax.sql.DataSource apiDataSource;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Test
    void aPaidRequestIsWrittenDownWithWhoMadeItAndWhatItCostAndTheUsageRouteReportsIt() throws Exception {
        clearLedger();
        Member member = signIn("subject-usage-records");
        long documentId = createTitledDocument(member);
        int callsBefore = MODEL_CALLS.get();

        executeShorten(member, documentId).andExpect(status().isOk());

        assertThat(MODEL_CALLS.get()).isEqualTo(callsBefore + 1);
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT requested_by_user_id, purpose, job_id, model_name, prompt_version, rate_card, state,
                               actual_input_tokens, actual_output_tokens, actual_cost_usd, reserved_cost_usd, closed_at IS NOT NULL
                        FROM model_usage WHERE workspace_id = ?
                        """)) {
            statement.setLong(1, member.workspaceId());
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(member.userId());
                assertThat(rs.getString(2)).isEqualTo("ASSIST");
                assertThat(rs.getObject(3)).isNull();
                assertThat(rs.getString(4)).isNotBlank();
                assertThat(rs.getString(5)).isEqualTo("assist-rewrite-v1");
                assertThat(rs.getString(6)).contains("0.75").contains("4.50");
                assertThat(rs.getString(7)).isEqualTo("SETTLED");
                assertThat(rs.getInt(8)).isEqualTo(400);
                assertThat(rs.getInt(9)).isEqualTo(120);
                // 400 input tokens at $0.75 and 120 output tokens at $4.50 per million.
                assertThat(rs.getBigDecimal(10)).isEqualByComparingTo(new BigDecimal("0.000840"));
                // What was held before the answer came back is never less than what it turned out to cost.
                assertThat(rs.getBigDecimal(11)).isGreaterThan(rs.getBigDecimal(10));
                assertThat(rs.getBoolean(12)).isTrue();
                assertThat(rs.next()).as("exactly one row for one request").isFalse();
            }
        }

        mockMvc.perform(get(usagePath(member)).cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthUsedUsd").value(0.000840))
                .andExpect(jsonPath("$.monthLimitUsd").value(0.05))
                .andExpect(jsonPath("$.monthRequests").value(1))
                .andExpect(jsonPath("$.sharedAllowanceExhausted").value(false));
    }

    @Test
    void aWorkspaceThatHasUsedItsMonthIsRefusedBeforeAnythingIsSentOrQueued() throws Exception {
        clearLedger();
        Member member = signIn("subject-usage-workspace-limit");
        long documentId = createTitledDocument(member);
        seedSettled(member.workspaceId(), member.userId(), "0.049500", "now()");
        int callsBefore = MODEL_CALLS.get();

        executeShorten(member, documentId)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("USAGE_LIMIT_REACHED"))
                .andExpect(jsonPath("$.limit").value("WORKSPACE_MONTH"));
        assertThat(MODEL_CALLS.get()).as("nothing was sent").isEqualTo(callsBefore);
        assertThat(count("SELECT count(*) FROM model_usage WHERE workspace_id = ? AND state = 'RESERVED'", member.workspaceId())).isZero();

        // The same allowance stops a generation run before a job exists. The month is not "fully used" here and
        // almost never can be, because reservations stop the sum short of the limit: what is left is half a
        // tenth of a cent, less than the first request of any run would hold, and that is what refuses it.
        long notesId = uploadNotes(member);
        mockMvc.perform(post(documentsPath(member) + "/" + documentId + "/generations")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + notesId + "}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.limit").value("WORKSPACE_MONTH"));
        assertThat(count("SELECT count(*) FROM job WHERE workspace_id = ?", member.workspaceId())).isZero();

        mockMvc.perform(get(usagePath(member)).cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthRemainingUsd").value(0.0005));
    }

    @Test
    void aStartThatWasAlreadyAcceptedIsAnsweredWithItsReceiptHoweverMuchHasBeenSpentSince() throws Exception {
        clearLedger();
        Member member = signIn("subject-usage-replayed-start");
        long documentId = createTitledDocument(member);
        long notesId = uploadNotes(member);
        String key = UUID.randomUUID().toString();
        String body = "{\"sourceArtifactId\":" + notesId + "}";

        long jobId = readJson(mockMvc.perform(post(documentsPath(member) + "/" + documentId + "/generations")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andReturn()).get("jobId").asLong();
        // The response was lost, and by the time the client asks again the month is used up.
        seedSettled(member.workspaceId(), member.userId(), "0.049900", "now()");

        mockMvc.perform(post(documentsPath(member) + "/" + documentId + "/generations")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId));
        // Anything that is not that same request is new work, and is refused.
        mockMvc.perform(post(documentsPath(member) + "/" + documentId + "/generations")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests());
        assertThat(count("SELECT count(*) FROM job WHERE workspace_id = ?", member.workspaceId())).isEqualTo(1);
    }

    /**
     * The provider's client resends a failed request by itself unless told not to, inside what the ledger
     * recorded as one request. It is told not to; the application's own bounded retry, which reserves each
     * try, is the only thing that sends a request twice.
     */
    @Test
    void theProvidersClientIsConfiguredToSendEachRequestOnce() {
        assertThat(environment.getProperty("spring.ai.openai.max-retries", Integer.class)).isZero();
        assertThat(environment.getProperty("spring.ai.openai.chat.max-retries", Integer.class)).isZero();
    }

    @Test
    void whatWasSpentLastMonthDoesNotCountAgainstThisMonth() throws Exception {
        clearLedger();
        Member member = signIn("subject-usage-last-month");
        long documentId = createTitledDocument(member);
        seedSettled(member.workspaceId(), member.userId(), "0.049900",
                "date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' - interval '1 second'");

        executeShorten(member, documentId).andExpect(status().isOk());

        mockMvc.perform(get(usagePath(member)).cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthRequests").value(1));
    }

    @Test
    void theAllowanceEveryoneSharesStopsAWorkspaceThatHasSpentNothingWithoutSayingWhoSpentIt() throws Exception {
        clearLedger();
        Member bigSpender = signIn("subject-usage-big-spender");
        Member frugal = signIn("subject-usage-frugal");
        long documentId = createTitledDocument(frugal);
        // Two other workspaces' worth of spending, which together reach what everyone shares.
        seedSettled(bigSpender.workspaceId(), bigSpender.userId(), "0.045000", "now()");
        seedSettled(999_001, 999_001, "0.034900", "now()");
        int callsBefore = MODEL_CALLS.get();

        executeShorten(frugal, documentId)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.limit").value("GLOBAL_MONTH"));
        assertThat(MODEL_CALLS.get()).isEqualTo(callsBefore);

        // A reservation that is still open counts at its full amount, which is what tips the shared allowance over here.
        seedReserved(999_001, 999_001, "0.000200");
        String body = mockMvc.perform(get(usagePath(frugal)).cookie(frugal.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthUsedUsd").value(0))
                .andExpect(jsonPath("$.sharedAllowanceExhausted").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("0.045").doesNotContain("0.0349").doesNotContain("0.08");

        // Another workspace's usage is not this person's to read at all.
        mockMvc.perform(get(usagePath(bigSpender)).cookie(frugal.session())).andExpect(status().isForbidden());
    }

    @Test
    void theApplicationsOwnLoginCanReadItsWorkspacesRowsButNeverWriteTheLedger() throws Exception {
        clearLedger();
        Member member = signIn("subject-usage-permissions");
        Member other = signIn("subject-usage-permissions-other");
        seedSettled(member.workspaceId(), member.userId(), "0.001000", "now()");
        seedSettled(other.workspaceId(), other.userId(), "0.002000", "now()");

        try (Connection connection = apiDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                context.setString(1, String.valueOf(member.userId()));
                context.executeQuery();
            }
            try (PreparedStatement read = connection.prepareStatement("SELECT count(*), max(workspace_id) FROM model_usage");
                    ResultSet rs = read.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("only this member's workspace is visible").isEqualTo(1);
                assertThat(rs.getLong(2)).isEqualTo(member.workspaceId());
            }
            connection.rollback();

            for (String statement : List.of(
                    "UPDATE model_usage SET actual_cost_usd = 0 WHERE workspace_id = " + member.workspaceId(),
                    "DELETE FROM model_usage WHERE workspace_id = " + member.workspaceId(),
                    "INSERT INTO model_usage (workspace_id, requested_by_user_id, purpose, model_name, prompt_version, rate_card,"
                            + " reserved_input_tokens, reserved_output_tokens, reserved_cost_usd) VALUES ("
                            + member.workspaceId() + ", " + member.userId() + ", 'ASSIST', 'm', 'p', 'r', 1, 1, 0)",
                    "SELECT * FROM model_usage_reserve(" + member.workspaceId() + ", " + member.userId()
                            + ", 'ASSIST', NULL, 0, 'm', 'p', 'r', 1, 1, 0.01, 1, 1, 1, 1)",
                    "SELECT model_usage_close(1, " + member.workspaceId() + ", 'RETAINED', NULL, NULL, NULL)",
                    "SELECT * FROM worker_reserve_model_usage(1, 'w', 1, 'm', 'p', 'r', 1, 1, 0.01, 1, 1, 1, 1)",
                    "SELECT worker_retain_stale_model_usage(3600000, 10)")) {
                try (PreparedStatement forbidden = connection.prepareStatement(statement)) {
                    assertThatThrownBy(forbidden::execute)
                            .as(statement)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("permission denied");
                } finally {
                    connection.rollback();
                }
            }

            // Closing somebody else's reservation through the member routine changes nothing.
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                context.setString(1, String.valueOf(member.userId()));
                context.executeQuery();
            }
            long othersReservation = seedReserved(other.workspaceId(), other.userId(), "0.010000");
            try (PreparedStatement close = connection.prepareStatement(
                    "SELECT close_member_model_usage(?, ?, 'SETTLED', 0, 0, 0)")) {
                close.setLong(1, other.workspaceId());
                close.setLong(2, othersReservation);
                try (ResultSet rs = close.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBoolean(1)).isFalse();
                }
            }
            connection.rollback();
            assertThat(text("SELECT state FROM model_usage WHERE id = ?", othersReservation)).isEqualTo("RESERVED");

            // A settlement with no figures cannot make a request free: it keeps what was held.
            long ownReservation = seedReserved(member.workspaceId(), member.userId(), "0.010000");
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                context.setString(1, String.valueOf(member.userId()));
                context.executeQuery();
            }
            try (PreparedStatement close = connection.prepareStatement(
                    "SELECT close_member_model_usage(?, ?, 'SETTLED', NULL, NULL, NULL)")) {
                close.setLong(1, member.workspaceId());
                close.setLong(2, ownReservation);
                try (ResultSet rs = close.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBoolean(1)).isTrue();
                }
            }
            connection.commit();
            assertThat(text("SELECT state FROM model_usage WHERE id = ?", ownReservation)).isEqualTo("RETAINED");
        }
        // And the table itself refuses a settled row that says nothing, whoever writes it.
        try (Connection owner = ownerConnection();
                PreparedStatement statement = owner.prepareStatement("""
                        INSERT INTO model_usage (workspace_id, requested_by_user_id, purpose, model_name, prompt_version, rate_card, state,
                                                 reserved_input_tokens, reserved_output_tokens, reserved_cost_usd, closed_at)
                        VALUES (1, 1, 'ASSIST', 'm', 'p', 'r', 'SETTLED', 1, 1, 0.01, now())
                        """)) {
            assertThatThrownBy(statement::execute).isInstanceOf(SQLException.class).hasMessageContaining("model_usage_state_shape");
        }
    }

    private record Member(Cookie session, long workspaceId, long userId) {
    }

    private Member signIn(String subject) {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow().id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
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
        Cookie cookie = new Cookie("SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        return new Member(cookie, workspaceId, userId);
    }

    private static <S extends Session> S createAuthenticatedSession(
            FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    /** A document whose title has text in it, so "shorten the meeting title" has something to send to the model. */
    private long createTitledDocument(Member member) throws Exception {
        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + member.workspaceId() + "/templates")
                        .cookie(member.session()))
                .andExpect(status().isOk()).andReturn());
        JsonNode flowing = null;
        for (JsonNode template : templates) {
            if (template.get("displayName").asText().equals("Flowing meeting minutes")) {
                flowing = template;
            }
        }
        assertThat(flowing).isNotNull();
        String body = """
                {
                  "title": "Usage test minutes",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "A rather long meeting title that could be shorter"}
                  },
                  "initialRevisionReason": "Created for a usage test."
                }
                """.formatted(flowing.get("id").asLong(), flowing.get("currentActiveVersionId").asLong());
        return readJson(mockMvc.perform(post(documentsPath(member))
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions executeShorten(Member member, long documentId) throws Exception {
        long revisionId = readJson(mockMvc.perform(get(documentsPath(member) + "/" + documentId).cookie(member.session()))
                .andExpect(status().isOk()).andReturn()).get("currentRevision").get("id").asLong();
        return mockMvc.perform(post(documentsPath(member) + "/" + documentId + "/assist/execute")
                .cookie(member.session()).with(csrf())
                .contentType("application/json")
                .content("{\"text\":\"shorten the meeting title\",\"expectedRevisionId\":" + revisionId + "}"));
    }

    private long uploadNotes(Member member) throws Exception {
        String uploads = "/api/v1/workspaces/" + member.workspaceId() + "/uploads";
        long artifactId = readJson(mockMvc.perform(post(uploads)
                        .cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"filename\":\"notes.txt\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mockMvc.perform(put(uploads + "/" + artifactId + "/content")
                        .cookie(member.session()).with(csrf())
                        .contentType("application/octet-stream")
                        .content("The meeting was called to order.".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        mockMvc.perform(post(uploads + "/" + artifactId + "/complete").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());
        return artifactId;
    }

    private static String documentsPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/documents";
    }

    private static String usagePath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/usage";
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
    }

    /** The shared allowance is one figure for the whole database, so each test starts from an empty month. */
    private static void clearLedger() throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM model_usage")) {
            statement.executeUpdate();
        }
    }

    private static void seedSettled(long workspaceId, long userId, String costUsd, String createdAtSql) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO model_usage (workspace_id, requested_by_user_id, purpose, model_name, prompt_version, rate_card, state,
                                         reserved_input_tokens, reserved_output_tokens, reserved_cost_usd,
                                         actual_input_tokens, actual_output_tokens, actual_cost_usd, created_at, closed_at)
                VALUES (?, ?, 'ASSIST', 'seeded-model', 'seeded-prompt', 'seeded rates', 'SETTLED', 1, 1, 0.100000, 1, 1, ?::numeric,
                        %s, now())
                """.formatted(createdAtSql))) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, userId);
            statement.setString(3, costUsd);
            statement.executeUpdate();
        }
    }

    private static long seedReserved(long workspaceId, long userId, String reservedCostUsd) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO model_usage (workspace_id, requested_by_user_id, purpose, model_name, prompt_version, rate_card,
                                         reserved_input_tokens, reserved_output_tokens, reserved_cost_usd)
                VALUES (?, ?, 'ASSIST', 'seeded-model', 'seeded-prompt', 'seeded rates', 1, 1, ?::numeric) RETURNING id
                """)) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, userId);
            statement.setString(3, reservedCostUsd);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
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
}
