package io.github.vihuynh72.brownie.api.action;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Saving an approved export to a person's Drive, end to end: Brownie's real
 * routes, sessions, database and policies, storage, malware scanner and the
 * isolated renderer that produces the export, with Google stood in for by
 * WireMock over real HTTP. What is proved is what the person would meet and
 * what Google would receive: the exact exported bytes, sent once, to the top
 * of My Drive, shared with nobody, read back before the save counts; a lost
 * answer that stays unknown until Drive is asked, and is never sent twice; a
 * conversion labelled and counted as one; and no write, no token and no
 * content anywhere it should not be.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "brownie.public-origin=http://localhost:8081",
        "brownie.web.origin=http://localhost:5173",
        "brownie.connectors.google.client-id=stand-in-client.apps.googleusercontent.com",
        "brownie.connectors.google.client-secret=" + DriveSaveIntegrationTest.CLIENT_SECRET,
        "brownie.connectors.token-key-id=test-key",
        "brownie.connectors.google.actions-offered=true"})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class DriveSaveIntegrationTest {

    static final String CLIENT_SECRET = "stand-in-client-secret-value";
    private static final String ISSUER = "https://issuer-drive-save";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String CALLBACK = "/api/v1/connectors/google/callback";
    private static final String ACCESS_TOKEN = "ya29.drive-saving-access-stand-in";
    private static final String REFRESH_TOKEN = "1//drive-saving-refresh-stand-in";
    private static final String UPLOAD = "/upload/drive/v3/files";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String GOOGLE_DOC = "application/vnd.google-apps.document";
    private static final String BATCH_UPDATE = "/v1/documents/convertedDoc12345:batchUpdate";

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
        registry.add("spring.datasource.password", () -> "brownie_api_local_only");
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
        registry.add("brownie.connectors.google.docs-api-base-uri", GOOGLE::baseUrl);
    }

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

    @BeforeEach
    void resetGoogle() {
        GOOGLE.resetAll();
        GOOGLE.stubFor(post(urlPathEqualTo("/token")).willReturn(okJson("""
                {"access_token":"%s","expires_in":3599,"refresh_token":"%s","token_type":"Bearer",
                 "scope":"https://www.googleapis.com/auth/drive.file"}
                """.formatted(ACCESS_TOKEN, REFRESH_TOKEN))));
        GOOGLE.stubFor(post(urlPathEqualTo("/revoke")).willReturn(aResponse().withStatus(200)));
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/about"))
                .willReturn(okJson("{\"user\":{\"permissionId\":\"perm-owner\",\"emailAddress\":\"owner@example.org\"}}")));
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/files/generateIds")).atPriority(1)
                .willReturn(okJson("{\"kind\":\"drive#generatedIds\",\"ids\":[\"reservedFile" + UUID.randomUUID().toString().replace("-", "") + "\"]}")));
    }

    @AfterAll
    static void stopGoogle() {
        GOOGLE.stop();
    }

    @Test
    void anApprovedSaveSendsTheExactExportedBytesOnceToTheTopOfMyDriveAndCountsOnlyAfterReadingThemBack(CapturedOutput output)
            throws Exception {
        Member member = signInAndConnect("subject-drive-save");
        Exported exported = exportedDocument(member);

        JsonNode proposed = propose(member, exported.documentId(), "WORD_FILE");
        assertThat(proposed.get("state").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(proposed.get("type").asString()).isEqualTo("DRIVE_SAVE_FILE");
        JsonNode payload = proposed.get("payload");
        assertThat(payload.get("content").get("fileName").asString()).isEqualTo("Spring Budget Planning minutes.docx");
        assertThat(payload.get("content").get("sha256").asString()).isEqualTo(sha256(exported.docx()));
        assertThat(payload.get("content").get("bytes").asLong()).isEqualTo(exported.docx().length);
        assertThat(payload.get("target").get("sharing").asString()).isEqualTo("NOBODY");
        assertThat(payload.get("account").get("email").asString()).isEqualTo("owner@example.org");
        assertThat(proposed.toString()).as("the reserved id is never shown").doesNotContain("reservedFile");
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(UPLOAD)))).as("proposing sends nothing").isEmpty();

        String reservedId = text("SELECT provider_key FROM action_request WHERE id = ?", proposed.get("id").asLong());
        GOOGLE.stubFor(post(urlPathEqualTo(UPLOAD)).willReturn(okJson("""
                {"id":"%s","name":"Spring Budget Planning minutes.docx","mimeType":"%s",
                 "webViewLink":"https://docs.google.com/document/d/%s/edit"}
                """.formatted(reservedId, DOCX, reservedId))));
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/files/" + reservedId)).willReturn(okJson("""
                {"id":"%s","name":"Spring Budget Planning minutes.docx","mimeType":"%s","trashed":false,"parents":["0ATop"],
                 "shared":false,"size":"%d","md5Checksum":"%s","webViewLink":"https://docs.google.com/document/d/%s/edit"}
                """.formatted(reservedId, DOCX, exported.docx().length, md5(exported.docx()), reservedId))));

        mockMvc.perform(approve(member, proposed, "0".repeat(64)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTION_PAYLOAD_MISMATCH"));
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(UPLOAD)))).as("a wrong hash sends nothing").isEmpty();

        JsonNode saved = read(mockMvc.perform(approve(member, proposed, proposed.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(saved.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(saved.get("verification").asString()).isEqualTo("MATCHED");
        assertThat(saved.get("sent").asBoolean()).isTrue();
        assertThat(saved.get("externalLink").asString()).isEqualTo("https://docs.google.com/document/d/" + reservedId + "/edit");
        assertThat(saved.has("externalId")).isFalse();

        List<LoggedRequest> uploads = GOOGLE.findAll(postRequestedFor(urlPathEqualTo(UPLOAD)));
        assertThat(uploads).hasSize(1);
        LoggedRequest upload = uploads.getFirst();
        assertThat(queryOf(upload.getUrl())).isEqualTo(Map.of(
                "uploadType", "multipart", "ignoreDefaultVisibility", "true", "fields", "id,name,mimeType,webViewLink"));
        byte[] body = upload.getBody();
        assertThat(indexOf(body, exported.docx())).as("the exported bytes, exactly").isPositive();
        String description = new String(body, 0, Math.min(body.length, 600), StandardCharsets.ISO_8859_1);
        assertThat(description).contains("\"id\":\"" + reservedId + "\"").doesNotContain("parents").doesNotContain("permissions");

        // Approving again changes nothing and sends nothing.
        JsonNode again = read(mockMvc.perform(approve(member, proposed, proposed.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(again.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(UPLOAD)))).hasSize(1);

        // What reaches Google: token refreshes and the one upload; everything else is a read.
        for (LoggedRequest request : GOOGLE.findAll(anyRequestedFor(anyUrl()))) {
            String path = request.getUrl().split("\\?")[0];
            if (!request.getMethod().getName().equals("GET")) {
                assertThat(path).as(request.getMethod().getName() + " " + path).isIn("/token", "/revoke", UPLOAD);
            }
            assertThat(path).doesNotContain("permissions").doesNotContain("/copy");
        }
        assertThat(count("SELECT count(*) FROM audit_event WHERE resource_type = 'action' AND resource_id = ?", saved.get("id").asLong()))
                .as("approved, sent, finished").isEqualTo(3);
        assertThat(text("SELECT string_agg(details::text, ' ') FROM audit_event WHERE resource_type = 'action' AND resource_id = ?",
                saved.get("id").asLong())).doesNotContain("Spring Budget").doesNotContain(reservedId);
        assertThat(output.getAll()).doesNotContain(ACCESS_TOKEN).doesNotContain(REFRESH_TOKEN).doesNotContain(CLIENT_SECRET);
    }

    @Test
    void aSaveWhoseAnswerWasLostStaysUnknownIsNeverSentTwiceAndIsSettledByAskingDrive() throws Exception {
        Member member = signInAndConnect("subject-drive-save-lost");
        Exported exported = exportedDocument(member);
        JsonNode proposed = propose(member, exported.documentId(), "WORD_FILE");
        String reservedId = text("SELECT provider_key FROM action_request WHERE id = ?", proposed.get("id").asLong());
        GOOGLE.stubFor(post(urlPathEqualTo(UPLOAD)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        JsonNode lost = read(mockMvc.perform(approve(member, proposed, proposed.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(lost.get("state").asString()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(lost.get("sent").asBoolean()).isTrue();

        JsonNode approvedAgain = read(mockMvc.perform(approve(member, proposed, proposed.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(approvedAgain.get("state").asString()).as("an unknown outcome is not sent again").isEqualTo("OUTCOME_UNKNOWN");
        // Drive hands out a fresh id for every reservation.
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/files/generateIds")).atPriority(1)
                .willReturn(okJson("{\"kind\":\"drive#generatedIds\",\"ids\":[\"secondReservation12345\"]}")));
        JsonNode second = propose(member, exported.documentId(), "WORD_FILE");
        mockMvc.perform(approve(member, second, second.get("payloadHash").asString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTION_SIBLING_UNRESOLVED"));
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(UPLOAD)))).as("sent once, whatever happened next").hasSize(1);

        // The upload had in fact arrived: Drive has the file under the reserved id.
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/files/" + reservedId)).willReturn(okJson("""
                {"id":"%s","name":"Spring Budget Planning minutes.docx","mimeType":"%s","trashed":false,"parents":["0ATop"],
                 "shared":false,"size":"%d","sha256Checksum":"%s","webViewLink":"https://drive.google.com/file/d/%s/view"}
                """.formatted(reservedId, DOCX, exported.docx().length, sha256(exported.docx()), reservedId))));
        JsonNode settled = read(mockMvc.perform(MockMvcRequestBuilders.post(actionPath(member, proposed) + "/reconcile")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        assertThat(settled.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(settled.get("verification").asString()).isEqualTo("MATCHED");
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(UPLOAD)))).hasSize(1);
    }

    @Test
    void aConversionIsLabelledAsOneAndCountsTheFilledInValuesItFinds() throws Exception {
        Member member = signInAndConnect("subject-drive-save-convert");
        Exported exported = exportedDocument(member);
        JsonNode proposed = propose(member, exported.documentId(), "GOOGLE_DOC");
        assertThat(proposed.get("type").asString()).isEqualTo("DRIVE_SAVE_AS_GOOGLE_DOC");
        assertThat(proposed.get("payload").get("effect").get("conversion").asString()).isEqualTo("GOOGLE_DOC");
        assertThat(proposed.get("payload").get("check").get("values").toString())
                .as("the values the filler wrote, as it wrote them").contains("Spring Budget Planning").contains("March 5, 2026");
        assertThat(text("SELECT coalesce(provider_key, 'none') FROM action_request WHERE id = ?", proposed.get("id").asLong()))
                .as("Drive refuses reserved ids for conversions").isEqualTo("none");
        assertThat(GOOGLE.findAll(anyRequestedFor(urlPathEqualTo("/drive/v3/files/generateIds")))).isEmpty();

        GOOGLE.stubFor(post(urlPathEqualTo(UPLOAD)).willReturn(okJson("""
                {"id":"convertedDoc12345","name":"Spring Budget Planning minutes","mimeType":"%s",
                 "webViewLink":"https://docs.google.com/document/d/convertedDoc12345/edit"}
                """.formatted(GOOGLE_DOC))));
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/files/convertedDoc12345")).willReturn(okJson("""
                {"id":"convertedDoc12345","name":"Spring Budget Planning minutes","mimeType":"%s","trashed":false,
                 "parents":["0ATop"],"shared":false,"webViewLink":"https://docs.google.com/document/d/convertedDoc12345/edit"}
                """.formatted(GOOGLE_DOC))));
        GOOGLE.stubFor(get(urlPathEqualTo("/v1/documents/convertedDoc12345")).willReturn(okJson("""
                {"documentId":"convertedDoc12345","revisionId":"rev-1","title":"Spring Budget Planning minutes",
                 "body":{"content":[{"endIndex":60,"paragraph":{"elements":[{"textRun":{"content":"Spring Budget Planning\\nMarch 5, 2026\\n"}}]}}]}}
                """)));

        JsonNode converted = read(mockMvc.perform(approve(member, proposed, proposed.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(converted.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(converted.get("verification").asString()).isEqualTo("CONVERSION_CHECKED");
        assertThat(converted.get("conversionCheck").get("total").asInt()).isEqualTo(2);
        assertThat(converted.get("conversionCheck").get("found").asInt()).isEqualTo(2);
        String description = new String(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(UPLOAD))).getFirst().getBody(), 0, 400,
                StandardCharsets.ISO_8859_1);
        assertThat(description).contains("\"mimeType\":\"" + GOOGLE_DOC + "\"").doesNotContain("\"id\"");
    }

    @Test
    void nothingIsProposedFromAnOutdatedExportAFormatNotExportedOrWithoutASavingConnection() throws Exception {
        Member unconnected = signIn("subject-drive-save-unconnected");
        Exported plain = exportedDocument(unconnected);
        mockMvc.perform(proposeRequest(unconnected, plain.documentId(), "WORD_FILE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONNECTION_NOT_FOUND"));

        Member member = signInAndConnect("subject-drive-save-refusals");
        Exported exported = exportedDocument(member, "DOCX");
        mockMvc.perform(proposeRequest(member, exported.documentId(), "PDF_FILE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTION_NOT_PROPOSABLE"))
                .andExpect(jsonPath("$.reason").value("FORMAT_NOT_EXPORTED"));
        mockMvc.perform(proposeRequest(member, exported.documentId(), "SPREADSHEET"))
                .andExpect(status().isBadRequest());
        // A PDF-only export whose PDF was made offers no Word file to download, so none is saved or converted either.
        Exported pdfOnly = exportedDocument(member, "PDF");
        for (String kind : List.of("WORD_FILE", "GOOGLE_DOC")) {
            mockMvc.perform(proposeRequest(member, pdfOnly.documentId(), kind))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("FORMAT_NOT_EXPORTED"));
        }

        JsonNode document = read(mockMvc.perform(MockMvcRequestBuilders.get(documentsPath(member) + "/" + exported.documentId())
                .cookie(member.session())).andExpect(status().isOk()).andReturn());
        mockMvc.perform(MockMvcRequestBuilders.patch(documentsPath(member) + "/" + exported.documentId() + "/content")
                        .cookie(member.session()).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + document.get("currentRevision").get("id").asLong()
                                + ",\"edits\":[{\"operation\":\"SET\",\"fieldId\":\"meeting.title\",\"value\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\","
                                + "\"value\":\"Changed after export\"}}],\"editReason\":\"edited\"}"))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(proposeRequest(member, exported.documentId(), "WORD_FILE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("EXPORT_STALE"));
        assertThat(count("SELECT count(*) FROM action_request WHERE workspace_id = ?", member.workspaceId())).isZero();
    }

    @Test
    void thisVersionsTextIsAddedToADocASaveMadeOnlyAgainstTheApprovedRevisionAndCountsOnlyWhenReadBack() throws Exception {
        Member member = signInAndConnect("subject-doc-append");
        Exported exported = exportedDocument(member);
        JsonNode conversion = converted(member, exported);
        String before = "Spring Budget Planning\nMarch 5, 2026\n";
        googleDoc("rev-1", before);

        JsonNode proposed = proposeAppend(member, exported.documentId(), conversion.get("id").asLong());
        assertThat(proposed.get("type").asString()).isEqualTo("GOOGLE_DOC_APPEND");
        JsonNode payload = proposed.get("payload");
        String text = payload.get("content").get("text").asString();
        assertThat(text).startsWith("\n").contains("Spring Budget Planning");
        assertThat(payload.get("target").get("revision").asString()).isEqualTo("rev-1");
        assertThat(payload.get("target").get("shared").asBoolean()).isFalse();
        assertThat(proposed.toString()).as("the Doc's id is never shown").doesNotContain("convertedDoc12345");
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(BATCH_UPDATE)))).as("proposing sends nothing").isEmpty();

        String after = before.substring(0, before.length() - 1) + text + "\n";
        acceptsAdditionAt("rev-1", "rev-2", after, false);
        JsonNode added = read(mockMvc.perform(approve(member, proposed, proposed.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(added.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(added.get("verification").asString()).isEqualTo("MATCHED");
        assertThat(added.get("externalLink").asString()).isEqualTo("https://docs.google.com/document/d/convertedDoc12345/edit");
        List<LoggedRequest> sent = GOOGLE.findAll(postRequestedFor(urlPathEqualTo(BATCH_UPDATE)));
        assertThat(sent).hasSize(1);
        JsonNode body = JSON.readTree(sent.getFirst().getBodyAsString());
        assertThat(body.get("writeControl").get("requiredRevisionId").asString()).isEqualTo("rev-1");
        assertThat(body.get("requests").get(0).get("insertText").get("text").asString()).isEqualTo(text);
        assertThat(body.get("requests").size()).as("one insertion, nothing deleted or restyled").isEqualTo(1);

        // A second addition whose answer is lost and which Google never applied. Right after the send an unchanged Doc
        // proves nothing (the request may still land); after the settle interval it proves "not added", and the
        // addition is sent again, safely, because Google applies it only at the revision it names.
        googleDoc("rev-2", after);
        JsonNode second = proposeAppend(member, exported.documentId(), conversion.get("id").asLong());
        StubMapping lostAnswer = GOOGLE.stubFor(post(urlPathEqualTo(BATCH_UPDATE)).atPriority(1)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        JsonNode lost = read(mockMvc.perform(approve(member, second, second.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(lost.get("state").asString()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(read(mockMvc.perform(reconcile(member, second)).andExpect(status().isOk()).andReturn()).get("state").asString())
                .as("right after the send, not yet").isEqualTo("OUTCOME_UNKNOWN");
        // Time passes for every send alike, so the earlier addition of the same text stays earlier.
        ageSends(proposed, 6);
        ageSends(second, 6);
        assertThat(read(mockMvc.perform(reconcile(member, second)).andExpect(status().isOk()).andReturn()).get("state").asString())
                .as("well after the send and still at the approved revision: certainly not added").isEqualTo("APPROVED");
        GOOGLE.removeStub(lostAnswer);
        String afterTwice = after.substring(0, after.length() - 1) + text + "\n";
        acceptsAdditionAt("rev-2", "rev-3", afterTwice, false);
        JsonNode retried = read(mockMvc.perform(approve(member, second, second.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(retried.get("state").asString()).isEqualTo("SUCCEEDED");
        List<LoggedRequest> sends = GOOGLE.findAll(postRequestedFor(urlPathEqualTo(BATCH_UPDATE)));
        assertThat(sends).hasSize(3);
        assertThat(JSON.readTree(sends.getLast().getBodyAsString()).get("writeControl").get("requiredRevisionId").asString())
                .as("sent again naming the same revision, which is what makes it safe").isEqualTo("rev-2");

        // A third whose answer is lost although Google did apply it: asking finds exactly the text at the end, and it counts.
        googleDoc("rev-3", afterTwice);
        JsonNode third = proposeAppend(member, exported.documentId(), conversion.get("id").asLong());
        String afterThrice = afterTwice.substring(0, afterTwice.length() - 1) + text + "\n";
        acceptsAdditionAt("rev-3", "rev-4", afterThrice, true);
        JsonNode appliedButLost = read(mockMvc.perform(approve(member, third, third.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(appliedButLost.get("state").asString()).isEqualTo("OUTCOME_UNKNOWN");
        JsonNode found = read(mockMvc.perform(reconcile(member, third)).andExpect(status().isOk()).andReturn());
        assertThat(found.get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(found.get("verification").asString()).isEqualTo("MATCHED");

        // One whose answer is lost and which Google never applied; the person says they looked, and the same text is
        // then added through a new addition. Asking about the first finds the text there, but not whose it is: it is
        // never credited with the other's addition.
        googleDoc("rev-4", afterThrice);
        JsonNode unclear = proposeAppend(member, exported.documentId(), conversion.get("id").asLong());
        StubMapping lostOnce = GOOGLE.stubFor(post(urlPathEqualTo(BATCH_UPDATE)).atPriority(1)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThat(read(mockMvc.perform(approve(member, unclear, unclear.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn()).get("state").asString()).isEqualTo("OUTCOME_UNKNOWN");
        GOOGLE.removeStub(lostOnce);
        mockMvc.perform(MockMvcRequestBuilders.post(actionPath(member, unclear) + "/acknowledge").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomeAcknowledged").value(true));
        JsonNode instead = proposeAppend(member, exported.documentId(), conversion.get("id").asLong());
        acceptsAdditionAt("rev-4", "rev-4b", afterThrice.substring(0, afterThrice.length() - 1) + text + "\n", false);
        assertThat(read(mockMvc.perform(approve(member, instead, instead.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn()).get("state").asString()).isEqualTo("SUCCEEDED");
        assertThat(read(mockMvc.perform(reconcile(member, unclear)).andExpect(status().isOk()).andReturn()).get("state").asString())
                .as("the text at the end is the other addition's as much as this one's").isEqualTo("OUTCOME_UNKNOWN");

        // A fourth, prepared, then the Doc edited before the approval, and a fifth whose Doc was shared meanwhile:
        // each ends before anything is sent, because the revision does not cover who can see the Doc.
        googleDoc("rev-4", afterThrice);
        JsonNode fourth = proposeAppend(member, exported.documentId(), conversion.get("id").asLong());
        googleDoc("rev-5", "Edited by the person\n");
        int sendsBefore = GOOGLE.findAll(postRequestedFor(urlPathEqualTo(BATCH_UPDATE))).size();
        JsonNode edited = read(mockMvc.perform(approve(member, fourth, fourth.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(edited.get("state").asString()).isEqualTo("FAILED");
        assertThat(edited.get("failure").asString()).isEqualTo("TARGET_CHANGED");
        JsonNode fifth = proposeAppend(member, exported.documentId(), conversion.get("id").asLong());
        StubMapping sharedNow = GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/files/convertedDoc12345")).atPriority(1).willReturn(okJson("""
                {"id":"convertedDoc12345","name":"Spring Budget Planning minutes","mimeType":"%s","trashed":false,
                 "parents":["0ATop"],"shared":true}
                """.formatted(GOOGLE_DOC))));
        JsonNode shared = read(mockMvc.perform(approve(member, fifth, fifth.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(shared.get("failure").asString()).isEqualTo("TARGET_CHANGED");
        GOOGLE.removeStub(sharedNow);
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo(BATCH_UPDATE)))).as("neither was sent").hasSize(sendsBefore);

        // Nobody else's save can be a target.
        Member stranger = signInAndConnect("subject-doc-append-stranger");
        Exported theirs = exportedDocument(stranger);
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + stranger.workspaceId() + "/actions/doc-appends")
                        .cookie(stranger.session()).with(csrf()).contentType("application/json")
                        .content("{\"documentId\":" + theirs.documentId() + ",\"targetActionId\":" + conversion.get("id").asLong() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INVALID"));

        // Only a Doc a save of this person made can be added to.
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/files/generateIds")).atPriority(1)
                .willReturn(okJson("{\"kind\":\"drive#generatedIds\",\"ids\":[\"anotherReservation123\"]}")));
        JsonNode exactFile = propose(member, exported.documentId(), "WORD_FILE");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/actions/doc-appends")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"documentId\":" + exported.documentId() + ",\"targetActionId\":" + exactFile.get("id").asLong() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INVALID"));

        // Nor to a Doc whose save did not succeed: made, but not as approved (shared, here).
        JsonNode mismatched = propose(member, exported.documentId(), "GOOGLE_DOC");
        GOOGLE.stubFor(post(urlPathEqualTo(UPLOAD)).willReturn(okJson("""
                {"id":"mismatchDoc12345","name":"Spring Budget Planning minutes","mimeType":"%s"}
                """.formatted(GOOGLE_DOC))));
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/files/mismatchDoc12345")).willReturn(okJson("""
                {"id":"mismatchDoc12345","name":"Spring Budget Planning minutes","mimeType":"%s","trashed":false,
                 "parents":["0ATop"],"shared":true}
                """.formatted(GOOGLE_DOC))));
        JsonNode notAsApproved = read(mockMvc.perform(approve(member, mismatched, mismatched.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(notAsApproved.get("failure").asString()).isEqualTo("READBACK_MISMATCH");
        // The Doc itself is there and editable: only the save's own outcome refuses it.
        GOOGLE.stubFor(get(urlPathEqualTo("/v1/documents/mismatchDoc12345")).willReturn(okJson("""
                {"documentId":"mismatchDoc12345","revisionId":"rev-m","title":"Spring Budget Planning minutes",
                 "body":{"content":[{"endIndex":5,"paragraph":{"elements":[{"textRun":{"content":"Text\\n"}}]}}]}}
                """)));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/actions/doc-appends")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"documentId\":" + exported.documentId() + ",\"targetActionId\":" + mismatched.get("id").asLong() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INVALID"));

        // Nor from another Google account: disconnected, then connected again as someone else, the person may not add to it.
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/connections/google/disconnect")
                .cookie(member.session()).with(csrf())).andExpect(status().isOk());
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/about"))
                .willReturn(okJson("{\"user\":{\"permissionId\":\"perm-other\",\"emailAddress\":\"other@example.org\"}}")));
        connectSaving(member, "subject-doc-append-other");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/actions/doc-appends")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"documentId\":" + exported.documentId() + ",\"targetActionId\":" + conversion.get("id").asLong() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INVALID"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("another Google account")));
    }

    /** Google Docs as it is at {@code revision} holding {@code text}, and nothing added yet. */
    private void googleDoc(String revision, String text) throws Exception {
        GOOGLE.resetScenarios();
        GOOGLE.stubFor(get(urlPathEqualTo("/v1/documents/convertedDoc12345")).inScenario("doc").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(docJson(revision, text))));
    }

    /**
     * Google Docs as it answers an addition: applied only when the request names
     * {@code fromRevision}, after which the Doc is at {@code toRevision} holding
     * {@code afterText}; any other request refused with a plain 400, as Google
     * refuses a stale {@code requiredRevisionId}. {@code answerLost}: applied, but
     * the answer never arrives.
     */
    private void acceptsAdditionAt(String fromRevision, String toRevision, String afterText, boolean answerLost) throws Exception {
        GOOGLE.stubFor(post(urlPathEqualTo(BATCH_UPDATE)).atPriority(9).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":400,\"message\":\"stale\",\"status\":\"INVALID_ARGUMENT\"}}")));
        GOOGLE.stubFor(post(urlPathEqualTo(BATCH_UPDATE)).atPriority(2).inScenario("doc").whenScenarioStateIs(Scenario.STARTED)
                .withRequestBody(matchingJsonPath("$.writeControl.requiredRevisionId", equalTo(fromRevision)))
                .willSetStateTo("added")
                .willReturn(answerLost
                        ? aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)
                        : okJson("{\"documentId\":\"convertedDoc12345\",\"writeControl\":{\"requiredRevisionId\":\"" + toRevision + "\"}}")));
        GOOGLE.stubFor(get(urlPathEqualTo("/v1/documents/convertedDoc12345")).inScenario("doc").whenScenarioStateIs("added")
                .willReturn(okJson(docJson(toRevision, afterText))));
    }

    private static String docJson(String revision, String text) throws Exception {
        return """
                {"documentId":"convertedDoc12345","revisionId":"%s","title":"Spring Budget Planning minutes",
                 "body":{"content":[{"endIndex":1,"sectionBreak":{}},
                   {"endIndex":%d,"paragraph":{"elements":[{"textRun":{"content":%s}}]}}]}}
                """.formatted(revision, text.length() + 1, JSON.writeValueAsString(text));
    }

    /** Moves every send of this action back by {@code minutes}, as the clock would. */
    private static void ageSends(JsonNode action, int minutes) throws SQLException {
        try (Connection owner = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement update = owner.prepareStatement(
                        "UPDATE action_attempt SET sent_at = sent_at - make_interval(mins => ?) WHERE action_id = ? AND sent_at IS NOT NULL")) {
            update.setInt(1, minutes);
            update.setLong(2, action.get("id").asLong());
            update.executeUpdate();
        }
    }

    private MockHttpServletRequestBuilder reconcile(Member member, JsonNode action) {
        return MockMvcRequestBuilders.post(actionPath(member, action) + "/reconcile").cookie(member.session()).with(csrf());
    }

    /** A Google Doc saved from this export, as the conversion scenario makes one. */
    private JsonNode converted(Member member, Exported exported) throws Exception {
        JsonNode proposed = propose(member, exported.documentId(), "GOOGLE_DOC");
        GOOGLE.stubFor(post(urlPathEqualTo(UPLOAD)).willReturn(okJson("""
                {"id":"convertedDoc12345","name":"Spring Budget Planning minutes","mimeType":"%s",
                 "webViewLink":"https://docs.google.com/document/d/convertedDoc12345/edit"}
                """.formatted(GOOGLE_DOC))));
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/files/convertedDoc12345")).willReturn(okJson("""
                {"id":"convertedDoc12345","name":"Spring Budget Planning minutes","mimeType":"%s","trashed":false,
                 "parents":["0ATop"],"shared":false,"webViewLink":"https://docs.google.com/document/d/convertedDoc12345/edit"}
                """.formatted(GOOGLE_DOC))));
        googleDoc("rev-1", "Spring Budget Planning\nMarch 5, 2026\n");
        JsonNode saved = read(mockMvc.perform(approve(member, proposed, proposed.get("payloadHash").asString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(saved.get("state").asString()).isEqualTo("SUCCEEDED");
        return saved;
    }

    private JsonNode proposeAppend(Member member, long documentId, long targetActionId) throws Exception {
        return read(mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/actions/doc-appends")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"documentId\":" + documentId + ",\"targetActionId\":" + targetActionId + "}"))
                .andExpect(status().isCreated()).andReturn());
    }

    // --- fixtures ---

    private record Member(Cookie session, long workspaceId, long userId) {
    }

    private record Exported(long documentId, byte[] docx) {
    }

    private Member signInAndConnect(String subject) throws Exception {
        Member member = signIn(subject);
        connectSaving(member, subject);
        return member;
    }

    private void connectSaving(Member member, String subject) throws Exception {
        MvcResult started = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/connections/google")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"access\":\"DRIVE_SAVING\",\"returnTo\":\"/connections\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String url = JSON.readTree(started.getResponse().getContentAsString()).get("authorizationUrl").asString();
        assertThat(queryOf(url).get("scope")).isEqualTo("https://www.googleapis.com/auth/drive.file");
        assertThat(queryOf(url)).doesNotContainKey("trigger_onepick");
        String state = queryOf(url).get("state");
        MvcResult answered = mockMvc.perform(MockMvcRequestBuilders.get(CALLBACK).param("code", "code-" + subject).param("state", state)
                        .cookie(member.session()))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(answered.getResponse().getRedirectedUrl()).contains("google=connected").contains("access=drive_saving");
    }

    private Exported exportedDocument(Member member) throws Exception {
        return exportedDocument(member, "BOTH");
    }

    /** A document of the built-in minutes template, validated, approved for export in {@code format}, and exported. */
    private Exported exportedDocument(Member member, String format) throws Exception {
        builtInTemplateProvisioningService.ensureBuiltInTemplates(member.workspaceId(), member.userId());
        JsonNode templates = read(mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/workspaces/" + member.workspaceId() + "/templates")
                .cookie(member.session())).andExpect(status().isOk()).andReturn());
        JsonNode template = null;
        for (JsonNode each : templates) {
            if ("Flowing meeting minutes".equals(each.get("displayName").asString())) {
                template = each;
            }
        }
        assertThat(template).isNotNull();
        String body = """
                {"title":"Spring Budget Planning minutes","templateId":%d,"templateVersionId":%d,
                 "fields":{"meeting.title":{"type":"TEXT","cardinality":"SCALAR","value":"Spring Budget Planning"},
                           "meeting.date":{"type":"DATE","cardinality":"SCALAR","value":"2026-03-05"}},
                 "initialRevisionReason":"initial draft"}
                """.formatted(template.get("id").asLong(), template.get("currentActiveVersionId").asLong());
        JsonNode created = read(mockMvc.perform(MockMvcRequestBuilders.post(documentsPath(member))
                        .cookie(member.session()).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn());
        long documentId = created.get("id").asLong();
        JsonNode manifest = read(mockMvc.perform(MockMvcRequestBuilders.post(documentsPath(member) + "/" + documentId + "/validate")
                        .cookie(member.session()).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"expectedRevisionId\":" + created.get("currentRevision").get("id").asLong() + "}"))
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(MockMvcRequestBuilders.post(documentsPath(member) + "/" + documentId + "/export-approval")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"validationManifestId\":" + manifest.get("id").asLong() + ",\"format\":\"" + format + "\"}"))
                .andExpect(status().isCreated());
        JsonNode receipt = read(mockMvc.perform(MockMvcRequestBuilders.post(documentsPath(member) + "/" + documentId + "/export")
                .cookie(member.session()).with(csrf())).andExpect(status().isCreated()).andReturn());
        byte[] docx = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/workspaces/" + member.workspaceId() + "/uploads/"
                        + receipt.get("docxArtifactId").asLong() + "/download").cookie(member.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return new Exported(documentId, docx);
    }

    private JsonNode propose(Member member, long documentId, String kind) throws Exception {
        return read(mockMvc.perform(proposeRequest(member, documentId, kind)).andExpect(status().isCreated()).andReturn());
    }

    private MockHttpServletRequestBuilder proposeRequest(Member member, long documentId, String kind) {
        return MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/actions/drive-saves")
                .cookie(member.session()).with(csrf()).contentType("application/json")
                .content("{\"documentId\":" + documentId + ",\"kind\":\"" + kind + "\"}");
    }

    private MockHttpServletRequestBuilder approve(Member member, JsonNode action, String hash) {
        return MockMvcRequestBuilders.post(actionPath(member, action) + "/approve")
                .cookie(member.session()).with(csrf()).contentType("application/json")
                .content("{\"payloadHash\":\"" + hash + "\"}");
    }

    private static String actionPath(Member member, JsonNode action) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/actions/" + action.get("id").asLong();
    }

    private static String documentsPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/documents";
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

    private static JsonNode read(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static Map<String, String> queryOf(String url) {
        Map<String, String> values = new HashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams()
                .forEach((name, list) -> values.put(name, URLDecoder.decode(list.getFirst(), StandardCharsets.UTF_8)));
        return values;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
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
