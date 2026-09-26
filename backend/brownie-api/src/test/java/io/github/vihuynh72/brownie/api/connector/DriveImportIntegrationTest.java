package io.github.vihuynh72.brownie.api.connector;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorResourceUnavailableException;
import io.github.vihuynh72.brownie.core.connector.DriveFile;
import io.github.vihuynh72.brownie.core.connector.ProviderTokenRejectedException;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
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
import org.springframework.test.web.servlet.ResultActions;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Picking Google Drive files and copying them into documents, end to end:
 * Brownie's real routes, sessions, database, policies, storage and malware
 * scanner, with Google's token, revocation and account endpoints stood in for
 * by WireMock over real HTTP, and Drive itself by the pretend Drive that keeps
 * {@code DriveFileReader}'s contract. Nothing here sends a request for a
 * Drive file anywhere: what the real reader sends is for its own tests to
 * prove. What is proven is everything Brownie does around it: a pick is bound
 * to the session and the person that started it, only the files it returned
 * become choices, a copy is made only from a choice that is the person's and
 * open, what is refused is refused before anything is kept, races leave
 * nothing half-done, only reads reach Google, nothing lists Drive, and no
 * token, secret or Drive id leaves the server except a file's own link.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "brownie.public-origin=http://localhost:8081",
        "brownie.web.origin=http://localhost:5173",
        "brownie.connectors.google.client-id=stand-in-client.apps.googleusercontent.com",
        "brownie.connectors.google.client-secret=" + DriveImportIntegrationTest.CLIENT_SECRET,
        "brownie.connectors.google.drive-offered=true",
        "brownie.connectors.token-key-id=test-key"})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class DriveImportIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-drive-import";
    private static final String CALLBACK = "/api/v1/connectors/google/callback";
    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final String PICK_ACCESS = "ya29.drive-pick-access-stand-in";
    private static final String PICK_REFRESH = "1//drive-pick-refresh-stand-in";
    private static final String REFRESHED_ACCESS = "ya29.drive-refreshed-access-stand-in";
    /** Not private: the properties above, outside the class body, name it too. */
    static final String CLIENT_SECRET = "stand-in-client-secret-value";
    private static final long MAX_UPLOAD_BYTES = 10485760;
    private static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EI" + "CAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

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

    /** Drive, as the reader's contract says it behaves; it replaces the reader that refuses everything. */
    @TestConfiguration
    static class PretendDrive {

        @Bean
        @Primary
        InMemoryDriveFileReader pretendDrive() {
            return new InMemoryDriveFileReader();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryDriveFileReader drive;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private BuiltInTemplateProvisioningService builtInTemplateProvisioningService;

    @BeforeEach
    void resetGoogleAndDrive() {
        drive.clear();
        GOOGLE.resetAll();
        GOOGLE.stubFor(post(urlPathEqualTo("/token")).withRequestBody(containing("grant_type=authorization_code")).willReturn(okJson("""
                {"access_token":"%s","expires_in":3599,"refresh_token":"%s","token_type":"Bearer","scope":"%s"}
                """.formatted(PICK_ACCESS, PICK_REFRESH, DRIVE_SCOPE))));
        GOOGLE.stubFor(post(urlPathEqualTo("/token")).withRequestBody(containing("grant_type=refresh_token")).willReturn(okJson("""
                {"access_token":"%s","expires_in":3599,"token_type":"Bearer","scope":"%s"}
                """.formatted(REFRESHED_ACCESS, DRIVE_SCOPE))));
        GOOGLE.stubFor(post(urlPathEqualTo("/revoke")).willReturn(aResponse().withStatus(200)));
        stubDriveAccount("drive-account");
    }

    @AfterAll
    static void stopGoogle() {
        GOOGLE.stop();
    }

    @Test
    void pickedFilesBecomeCitableTextSourcesThatSayWhereTheyCameFromAndAreCopiedOnce(CapturedOutput output) throws Exception {
        Member member = signIn("subject-drive-import");
        drive.put(doc("docAlpha1", "Minutes", "12"), bytes("Agenda: budget review.\n"));
        drive.put(new DriveFile("txtBravo2", "notes.txt", DriveFile.PLAIN_TEXT, false, true, 12L, "3",
                OffsetDateTime.parse("2026-09-21T09:00:00Z"), "https://drive.google.com/file/d/txtBravo2/view"), bytes("Plain notes."));
        drive.put(new DriveFile("pdfCharlie3", "report.pdf", "application/pdf", false, true, 900L, "1", null, null), bytes("%PDF-1.4"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/capabilities").cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleConnectorAccess").value(org.hamcrest.Matchers.containsInAnyOrder("DRIVE_FILES", "CALENDAR_EVENTS")));

        Map<String, String> query = startPick(member, "/connections");
        assertThat(query).containsEntry("trigger_onepick", "true").containsEntry("allow_multiple", "true")
                .containsEntry("scope", DRIVE_SCOPE).containsEntry("access_type", "offline").containsEntry("code_challenge_method", "S256");
        assertThat(query.get("mimetypes")).isEqualTo(DriveFile.GOOGLE_DOC + "," + DriveFile.PLAIN_TEXT);
        String landed = answerPick(member, query.get("state"), "docAlpha1,txtBravo2,pdfCharlie3");

        assertThat(landed).isEqualTo(
                "http://localhost:5173/connections?google=picked&access=drive_files&added=2&unsupported=1&unavailable=0&unchecked=0&over_limit=0");
        assertThat(drive.calls()).extracting(InMemoryDriveFileReader.Call::method).containsOnly("describeFile").hasSize(3);
        assertThat(drive.tokensUsed()).as("each file is described with the pick's own access token").containsOnly(PICK_ACCESS);
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/token")))).as("no refresh during a pick").hasSize(1);
        mockMvc.perform(MockMvcRequestBuilders.get(connectionsPath(member)).cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].access").value("DRIVE_FILES"))
                .andExpect(jsonPath("$[0].grants.length()").value(2))
                .andExpect(jsonPath("$[0].grants[*].displayName").value(org.hamcrest.Matchers.containsInAnyOrder("Minutes", "notes.txt")))
                .andExpect(jsonPath("$[0].grants[*].type").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("DRIVE_FILE"))))
                .andExpect(jsonPath("$[0].grants[0].externalId").doesNotExist());

        long documentId = createDocument(member);
        drive.clear();
        drive.put(doc("docAlpha1", "Minutes", "12"), bytes("Agenda: budget review.\n"));
        JsonNode first = importFile(member, documentId, grantOf(member, "docAlpha1"));
        assertThat(first.get("newCopy").asBoolean()).isTrue();
        JsonNode source = first.get("source");
        assertThat(source.get("kind").asString()).isEqualTo("GOOGLE_DRIVE");
        assertThat(source.get("displayFilename").asString()).isEqualTo("Minutes.txt");
        assertThat(source.get("origin").get("provider").asString()).isEqualTo("GOOGLE");
        assertThat(source.get("origin").get("title").asString()).isEqualTo("Minutes");
        assertThat(source.get("origin").get("link").asString()).isEqualTo("https://docs.google.com/document/d/docAlpha1/edit");
        assertThat(source.get("origin").get("conversion").asString()).isEqualTo("GOOGLE_DOC_AS_TEXT");
        assertThat(source.get("origin").has("externalId")).as("Drive's id is not shown").isFalse();
        assertThat(drive.calls()).containsExactly(
                new InMemoryDriveFileReader.Call("describeFile", "docAlpha1", null),
                new InMemoryDriveFileReader.Call("readGoogleDocAsText", "docAlpha1", (int) MAX_UPLOAD_BYTES),
                new InMemoryDriveFileReader.Call("describeFile", "docAlpha1", null));
        assertThat(drive.tokensUsed()).as("a copy uses a freshly refreshed token").containsOnly(REFRESHED_ACCESS);
        long snapshotId = source.get("id").asLong();
        long artifactId = source.get("artifactId").asLong();

        assertThat(text("SELECT status || ':' || detected_media_type FROM artifact WHERE id = ?", artifactId)).isEqualTo("READY:PLAIN_TEXT");
        assertThat(text("SELECT origin_external_id || '|' || origin_revision || '|' || origin_conversion FROM source_snapshot WHERE id = ?",
                snapshotId)).isEqualTo("docAlpha1|12|GOOGLE_DOC_AS_TEXT");
        assertThat(text("SELECT normalized_text FROM plain_text_extraction_version WHERE artifact_id = ?", artifactId))
                .contains("Agenda: budget review.");
        assertThat(text("SELECT details::text FROM audit_event WHERE action = 'SOURCE_IMPORTED' AND resource_id = ?", snapshotId))
                .contains("GOOGLE_DRIVE").contains("GOOGLE_DOC_AS_TEXT").doesNotContain("Minutes").doesNotContain("docAlpha1");

        // The copy is a source like any other: a part of it can be cited, and the citation shown through the document.
        long spanId = JSON.readTree(mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/sources/" + snapshotId + "/spans")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"type\":\"PLAIN_TEXT\",\"startCodePoint\":0,\"endCodePointExclusive\":6}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(MockMvcRequestBuilders.get(documentPath(member, documentId) + "/evidence/" + spanId).cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excerptText").value("Agenda"));

        drive.clear();
        drive.put(doc("docAlpha1", "Minutes", "12"), bytes("Agenda: budget review.\n"));
        JsonNode again = importFile(member, documentId, grantOf(member, "docAlpha1"));
        assertThat(again.get("newCopy").asBoolean()).as("the same version is copied once").isFalse();
        assertThat(again.get("source").get("id").asLong()).isEqualTo(snapshotId);
        assertThat(drive.calls()).extracting(InMemoryDriveFileReader.Call::method).as("and not read again").containsExactly("describeFile");

        drive.put(doc("docAlpha1", "Minutes (moved)", "13"), bytes("Agenda: budget review, moved to Friday.\n"));
        JsonNode changed = importFile(member, documentId, grantOf(member, "docAlpha1"));
        assertThat(changed.get("newCopy").asBoolean()).as("a changed file is a new copy").isTrue();
        long changedId = changed.get("source").get("id").asLong();
        assertThat(changedId).isNotEqualTo(snapshotId);
        assertThat(text("SELECT origin_revision || '|' || origin_title FROM source_snapshot WHERE id = ?", snapshotId))
                .as("and the old copy is left exactly as it was").isEqualTo("12|Minutes");

        drive.put(doc("docAlpha1", "Minutes (moved)", "14"), bytes("Agenda: budget review, moved to Friday.\n"));
        JsonNode sameText = importFile(member, documentId, grantOf(member, "docAlpha1"));
        assertThat(sameText.get("newCopy").asBoolean()).as("a newer version with the same text is not a new copy").isFalse();
        assertThat(sameText.get("source").get("id").asLong()).isEqualTo(changedId);

        drive.put(new DriveFile("txtBravo2", "notes.txt", DriveFile.PLAIN_TEXT, false, true, 12L, "3", null,
                "https://drive.google.com/file/d/txtBravo2/view"), bytes("Plain notes."));
        JsonNode text = importFile(member, documentId, grantOf(member, "txtBravo2"));
        assertThat(text.get("source").get("displayFilename").asString()).isEqualTo("notes.txt");
        JsonNode conversion = text.get("source").get("origin").get("conversion");
        assertThat(conversion == null || conversion.isNull()).as("a text file arrives as it is").isTrue();
        assertThat(text.get("source").get("origin").get("link").asString()).isEqualTo("https://drive.google.com/file/d/txtBravo2/view");
        assertThat(drive.calls()).filteredOn(call -> call.fileId().equals("txtBravo2")).extracting(InMemoryDriveFileReader.Call::method)
                .as("a text file is downloaded, never exported").containsExactly("describeFile", "readTextFile", "describeFile");

        mockMvc.perform(MockMvcRequestBuilders.get(documentPath(member, documentId) + "/sources").cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        assertThat(output.getAll()).doesNotContain(PICK_ACCESS).doesNotContain(PICK_REFRESH).doesNotContain(REFRESHED_ACCESS)
                .doesNotContain("docAlpha1").doesNotContain("txtBravo2").doesNotContain("pdfCharlie3");
    }

    @Test
    void whatCannotBeCopiedSaysWhyKeepsNothingAndAFileGoogleSaysIsGoneLeavesTheList() throws Exception {
        Member member = signIn("subject-drive-refusals");
        List<String> ids = List.of("trashA1", "lockedB2", "bigC3", "latinD4", "eicarE5", "pdfF6", "nulG7", "goneH8", "lostI9");
        ids.forEach(id -> drive.put(new DriveFile(id, id + ".txt", DriveFile.PLAIN_TEXT, false, true, 10L, "1", null, null), bytes("text")));
        assertThat(pick(member, String.join(",", ids))).contains("added=9");
        long documentId = createDocument(member);

        drive.put(new DriveFile("trashA1", "a", DriveFile.GOOGLE_DOC, true, true, null, "2", null, null), bytes("text"));
        refused(member, documentId, "trashA1", 409, "CONNECTOR_RESOURCE_UNAVAILABLE", "TRASHED");
        drive.put(new DriveFile("lockedB2", "b", DriveFile.PLAIN_TEXT, false, false, 10L, "2", null, null), bytes("text"));
        refused(member, documentId, "lockedB2", 409, "CONNECTOR_RESOURCE_UNAVAILABLE", "DOWNLOAD_RESTRICTED");
        drive.put(new DriveFile("bigC3", "c", DriveFile.PLAIN_TEXT, false, true, MAX_UPLOAD_BYTES + 1, "2", null, null), bytes("text"));
        refused(member, documentId, "bigC3", 413, "CONNECTOR_RESOURCE_TOO_LARGE", null);
        assertThat(drive.calls()).filteredOn(call -> call.fileId().equals("bigC3")).extracting(InMemoryDriveFileReader.Call::method)
                .as("refused by its stated size, before anything is read").containsOnly("describeFile");
        drive.put(new DriveFile("latinD4", "d", DriveFile.PLAIN_TEXT, false, true, 4L, "2", null, null), new byte[] {'c', 'a', 'f', (byte) 0xE9});
        refused(member, documentId, "latinD4", 422, "CONNECTOR_RESOURCE_UNSUPPORTED", "NOT_UTF8");
        drive.put(new DriveFile("eicarE5", "e", DriveFile.PLAIN_TEXT, false, true, 68L, "2", null, null), bytes(EICAR));
        refused(member, documentId, "eicarE5", 422, "CONNECTOR_RESOURCE_REFUSED", "MALWARE_DETECTED");
        drive.put(new DriveFile("pdfF6", "f", DriveFile.PLAIN_TEXT, false, true, 30L, "2", null, null), bytes("%PDF-1.4\n% not text at all\n"));
        refused(member, documentId, "pdfF6", 422, "CONNECTOR_RESOURCE_REFUSED", null);
        drive.put(new DriveFile("nulG7", "g", DriveFile.PLAIN_TEXT, false, true, 5L, "2", null, null), new byte[] {'a', 0, 'b', 0, 'c'});
        refused(member, documentId, "nulG7", 422, "CONNECTOR_RESOURCE_REFUSED", "UNSUPPORTED_MEDIA_TYPE");
        drive.remove("goneH8");
        refused(member, documentId, "goneH8", 409, "CONNECTOR_RESOURCE_UNAVAILABLE", "GONE");
        drive.refuse("readTextFile", "lostI9", new ConnectorResourceUnavailableException(
                ConnectorResourceUnavailableException.Reason.ACCESS_LOST, ConnectorAccess.DRIVE_FILES));
        refused(member, documentId, "lostI9", 409, "CONNECTOR_RESOURCE_UNAVAILABLE", "ACCESS_LOST");

        assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ?", member.workspaceId())).as("nothing was kept").isZero();
        assertThat(texts("SELECT external_id || ':' || revoked_reason FROM connector_resource_grant WHERE workspace_id = ? AND revoked_at IS NOT NULL",
                member.workspaceId())).as("only what Google said is gone, or no longer Brownie's, left the list")
                .containsExactlyInAnyOrder("goneH8:PROVIDER_ACCESS_LOST", "lostI9:PROVIDER_ACCESS_LOST");
    }

    @Test
    void onlyPickedFilesAreReadAndAChoiceThatIsNotTheirsOrNotOpenAsksNothingOfGoogle() throws Exception {
        Member member = signIn("subject-drive-only-picked");
        Member other = signIn("subject-drive-only-picked-other");
        List<String> ids = new ArrayList<>(List.of("../../etc", "has space", "id?x=1"));
        IntStream.range(0, 12).forEach(i -> ids.add("file" + i));
        IntStream.range(0, 12).forEach(i -> drive.put(doc("file" + i, "File " + i, "1"), bytes("File " + i)));

        assertThat(pick(member, String.join(",", ids))).endsWith("added=10&unsupported=0&unavailable=0&unchecked=0&over_limit=2");
        assertThat(drive.calls()).extracting(InMemoryDriveFileReader.Call::fileId)
                .as("malformed ids and those past the tenth never reach Drive")
                .containsExactly("file0", "file1", "file2", "file3", "file4", "file5", "file6", "file7", "file8", "file9");
        assertThat(pick(other, "file0")).contains("added=1");
        long documentId = createDocument(member);
        long theirs = grantOf(other, "file0");
        long mine = grantOf(member, "file1");
        mockMvc.perform(MockMvcRequestBuilders.post(drivePath(member) + "/files/" + grantOf(member, "file2") + "/forget")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());
        long forgotten = forgottenGrantOf(member, "file2");

        int sentBefore = GOOGLE.getAllServeEvents().size();
        drive.clear();
        for (long grantId : new long[] {theirs, forgotten, 987654}) {
            importExpecting(member, documentId, grantId, 404).andExpect(jsonPath("$.code").value("CONNECTOR_RESOURCE_NOT_FOUND"));
        }
        mockMvc.perform(MockMvcRequestBuilders.post(drivePath(member) + "/imports")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"documentId\":" + documentId + ",\"grantId\":\"file1\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/deletions")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"scope\":\"DOCUMENT\",\"documentId\":" + documentId + "}"))
                .andExpect(status().isCreated());
        importExpecting(member, documentId, mine, 404).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(GOOGLE.getAllServeEvents()).as("nothing at all is sent to Google, not even a token refresh").hasSize(sentBefore);
        assertThat(drive.calls()).as("and nothing is asked of Drive").isEmpty();

        long another = createDocument(member);
        mockMvc.perform(MockMvcRequestBuilders.post(connectionsPath(member) + "/google/disconnect").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());
        sentBefore = GOOGLE.getAllServeEvents().size();
        importExpecting(member, another, mine, 404).andExpect(jsonPath("$.code").value("CONNECTOR_RESOURCE_NOT_FOUND"));
        assertThat(GOOGLE.getAllServeEvents()).as("a choice ended by disconnecting asks Google nothing").hasSize(sentBefore);
        assertThat(drive.calls()).isEmpty();
    }

    @Test
    void aPickIsBoundToTheSessionAndPersonThatStartedItAndEveryRefusalGrantsNothing() throws Exception {
        Member member = signIn("subject-drive-bound");
        Member other = signIn("subject-drive-bound-other");
        drive.put(doc("boundFile1", "Minutes", "1"), bytes("text"));

        Map<String, String> started = startPick(member, "/connections");
        assertThat(answerPick(member, "not-" + started.get("state"), "boundFile1")).endsWith("google=failed&access=drive_files&reason=state_mismatch");
        assertThat(answerPick(member, started.get("state"), "boundFile1"))
                .as("the pending pick was used up by the refused answer").endsWith("google=failed&reason=no_pending_request");

        Map<String, String> theirs = startPick(member, "/connections");
        assertThat(answerPick(other, theirs.get("state"), "boundFile1"))
                .as("another person's session holds no pick of theirs").endsWith("google=failed&reason=no_pending_request");

        Map<String, String> late = startPick(member, "/connections");
        agePendingPick(member, PendingConsent.LIFETIME.plusMinutes(1));
        assertThat(answerPick(member, late.get("state"), "boundFile1")).endsWith("reason=expired");

        Map<String, String> forged = startPick(member, "/connections");
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get(CALLBACK).param("code", "code-pick").param("state", forged.get("state"))
                        .param("iss", "https://accounts.google.example").param("picked_file_ids", "boundFile1").cookie(member.session()))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl()).endsWith("reason=issuer_mismatch");

        assertThat(drive.calls()).as("no refused answer reaches Drive").isEmpty();
        assertThat(count("SELECT count(*) FROM connector_resource_grant WHERE workspace_id = ?", member.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", member.workspaceId())).isZero();

        Map<String, String> good = startPick(member, "/connections");
        assertThat(answerPick(member, good.get("state"), "boundFile1")).contains("google=picked").contains("added=1");
        LoggedRequest exchange = GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/token")).withRequestBody(containing("grant_type=authorization_code")))
                .getLast();
        String verifier = formOf(exchange.getBodyAsString()).get("code_verifier");
        assertThat(s256(verifier)).as("the verifier sent with a pick's code is the one whose digest went into the pick address")
                .isEqualTo(good.get("code_challenge"));
        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ? AND position(convert_to('" + PICK_REFRESH
                + "', 'UTF8') in token_ciphertext) > 0", member.workspaceId())).as("the refresh token is kept only as ciphertext").isZero();

        stubDriveAccount("someone-else");
        Map<String, String> otherAccount = startPick(member, "/connections");
        drive.put(doc("boundFile2", "Other", "1"), bytes("text"));
        assertThat(answerPick(member, otherAccount.get("state"), "boundFile2")).endsWith("reason=different_account");
        assertThat(texts("SELECT external_id FROM connector_resource_grant WHERE workspace_id = ?", member.workspaceId()))
                .as("a pick from another account grants nothing").containsExactly("boundFile1");
    }

    @Test
    void aTokenRefusedDuringAPickOrACopyAsksToConnectAgainOnce() throws Exception {
        Member member = signIn("subject-drive-token");
        drive.put(doc("tokenFile1", "Minutes", "1"), bytes("text"));
        drive.refuse("describeFile", "tokenFile1", new ProviderTokenRejectedException("stand-in refusal"));

        assertThat(pick(member, "tokenFile1")).as("the consent completed, so the pick says what it left and why it stopped")
                .endsWith("google=picked&access=drive_files&added=0&unsupported=0&unavailable=0&unchecked=1&over_limit=0&stopped=token_refused");
        assertThat(text("SELECT state || ':' || reconnect_reason FROM connector_connection WHERE workspace_id = ?", member.workspaceId()))
                .isEqualTo("RECONNECT_REQUIRED:TOKEN_REJECTED");
        assertThat(count("SELECT count(*) FROM connector_resource_grant WHERE workspace_id = ?", member.workspaceId())).isZero();

        drive.stopRefusing();
        assertThat(pick(member, "tokenFile1")).as("picking again connects again").contains("google=picked").contains("added=1");
        long documentId = createDocument(member);
        drive.refuse("readGoogleDocAsText", "tokenFile1", new ProviderTokenRejectedException("stand-in refusal"));
        importExpecting(member, documentId, grantOf(member, "tokenFile1"), 409)
                .andExpect(jsonPath("$.code").value("CONNECTION_RECONNECT_REQUIRED"))
                .andExpect(jsonPath("$.reason").value("TOKEN_REJECTED"));
        int refreshes = GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/token"))).size();
        importExpecting(member, documentId, grantOf(member, "tokenFile1"), 409).andExpect(jsonPath("$.code").value("CONNECTION_RECONNECT_REQUIRED"));
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/token")))).as("once recorded, Google is not asked again").hasSize(refreshes);

        // The seven-day end of a testing project's tokens: Google refuses the refresh itself.
        drive.stopRefusing();
        assertThat(pick(member, "tokenFile1")).contains("google=picked");
        GOOGLE.stubFor(post(urlPathEqualTo("/token")).withRequestBody(containing("grant_type=refresh_token")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}")));
        importExpecting(member, documentId, grantOf(member, "tokenFile1"), 409)
                .andExpect(jsonPath("$.code").value("CONNECTION_RECONNECT_REQUIRED"))
                .andExpect(jsonPath("$.reason").value("TOKEN_REJECTED"));
        assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ?", member.workspaceId())).isZero();
    }

    @Test
    void aDisconnectAForgetOrAChangeDuringACopyKeepsNothingAndTwoCopiesOfOneVersionMakeOne() throws Exception {
        Member member = signIn("subject-drive-races");
        drive.put(doc("raceFile1", "Minutes", "1"), bytes("First text."));
        drive.put(doc("raceFile2", "Notes", "1"), bytes("Second text."));
        assertThat(pick(member, "raceFile1,raceFile2")).contains("added=2");
        long documentId = createDocument(member);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // A disconnect while the copy is being read.
            Future<MvcResult> copying = heldCopy(executor, member, documentId, grantOf(member, "raceFile1"), () -> mockMvc.perform(
                    MockMvcRequestBuilders.post(connectionsPath(member) + "/google/disconnect").cookie(member.session()).with(csrf()))
                    .andExpect(status().isOk()));
            assertThat(copying.get(60, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(404);
            assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ?", member.workspaceId())).isZero();

            // Taking the file back while it is being read.
            assertThat(pick(member, "raceFile1,raceFile2")).contains("added=2");
            long grant = grantOf(member, "raceFile1");
            copying = heldCopy(executor, member, documentId, grant, () -> mockMvc.perform(
                    MockMvcRequestBuilders.post(drivePath(member) + "/files/" + grant + "/forget").cookie(member.session()).with(csrf()))
                    .andExpect(status().isOk()));
            assertThat(copying.get(60, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(404);
            assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ?", member.workspaceId())).isZero();

            // The file changing while it is being read.
            long second = grantOf(member, "raceFile2");
            copying = heldCopy(executor, member, documentId, second, () -> drive.put(doc("raceFile2", "Notes", "2"), bytes("Second text, edited.")));
            MvcResult changed = copying.get(60, TimeUnit.SECONDS);
            assertThat(changed.getResponse().getStatus()).isEqualTo(409);
            assertThat(JSON.readTree(changed.getResponse().getContentAsString()).get("reason").asString()).isEqualTo("CHANGED_DURING_COPY");
            assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ?", member.workspaceId())).isZero();

            // Two copies of the same version at once, both getting as far as recording it: the one that records second
            // links the first's copy, and its own bytes are left unused. The stand-in hands the two different text for the
            // one version, so that neither can link the other by its text before that point.
            String unused = "SELECT count(*) FROM artifact a WHERE a.workspace_id = ?"
                    + " AND NOT EXISTS (SELECT 1 FROM source_snapshot s WHERE s.workspace_id = a.workspace_id AND s.artifact_id = a.id)";
            long unusedBefore = count(unused, member.workspaceId());
            long[] recordedFirst = new long[1];
            copying = heldCopy(executor, member, documentId, second, () -> {
                drive.put(doc("raceFile2", "Notes", "2"), bytes("Second text, as Drive sends it now."));
                JsonNode first = JSON.readTree(importExpecting(member, documentId, second, 201).andReturn().getResponse().getContentAsString());
                assertThat(first.get("newCopy").asBoolean()).isTrue();
                recordedFirst[0] = first.get("source").get("id").asLong();
            });
            JsonNode held = JSON.readTree(copying.get(60, TimeUnit.SECONDS).getResponse().getContentAsString());
            assertThat(held.get("newCopy").asBoolean()).isFalse();
            assertThat(held.get("source").get("id").asLong()).isEqualTo(recordedFirst[0]);
            assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ?", member.workspaceId())).isEqualTo(1);
            assertThat(count(unused, member.workspaceId())).as("the copy that lost made an artifact, left for the worker to clear away")
                    .isEqualTo(unusedBefore + 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void forgettingAFileTakesItOffTheListKeepsItsCopiesAndDeletingTheWorkspaceRemovesEverything() throws Exception {
        Member member = signIn("subject-drive-forget");
        drive.put(doc("keepFile1", "Minutes", "1"), bytes("Kept text."));
        drive.put(doc("keepFile2", "Notes", "1"), bytes("Other text."));
        assertThat(pick(member, "keepFile1,keepFile2")).contains("added=2");
        long documentId = createDocument(member);
        long grant = grantOf(member, "keepFile1");
        long snapshotId = importFile(member, documentId, grant).get("source").get("id").asLong();

        mockMvc.perform(MockMvcRequestBuilders.post(drivePath(member) + "/files/" + grant + "/forget").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].grants.length()").value(1))
                .andExpect(jsonPath("$[0].grants[0].displayName").value("Notes"));
        assertThat(text("SELECT revoked_reason FROM connector_resource_grant WHERE id = ?", grant)).isEqualTo("REMOVED");
        mockMvc.perform(MockMvcRequestBuilders.get(documentPath(member, documentId) + "/sources").cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(snapshotId))
                .andExpect(jsonPath("$[0].origin.title").value("Minutes"));
        importExpecting(member, documentId, grant, 404).andExpect(jsonPath("$.code").value("CONNECTOR_RESOURCE_NOT_FOUND"));
        mockMvc.perform(MockMvcRequestBuilders.post(drivePath(member) + "/files/" + grant + "/forget").cookie(member.session()).with(csrf()))
                .andExpect(status().isNotFound());

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/deletions")
                        .cookie(member.session()).with(csrf()).contentType("application/json").content("{\"scope\":\"WORKSPACE\"}"))
                .andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ?", member.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM connector_resource_grant WHERE workspace_id = ?", member.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", member.workspaceId())).isZero();
    }

    @Test
    void onlyReadsReachGoogleNothingListsDriveAndNoTokenSecretOrDriveIdLeavesTheServer(CapturedOutput output) throws Exception {
        Member member = signIn("subject-drive-gate");
        List<String> answers = new ArrayList<>();
        String link = "https://docs.google.com/document/d/gateDoc1/edit";
        drive.put(new DriveFile("gateDoc1", "Plan", DriveFile.GOOGLE_DOC, false, true, null, "4", null, link), bytes("The plan."));
        drive.put(doc("gateDoc2", "Other", "1"), bytes("Other."));

        MvcResult started = mockMvc.perform(MockMvcRequestBuilders.post(drivePath(member) + "/picks").cookie(member.session()).with(csrf())
                .contentType("application/json").content("{\"returnTo\":\"/connections\"}")).andExpect(status().isOk()).andReturn();
        answers.add(answer(started));
        String state = queryOf(JSON.readTree(started.getResponse().getContentAsString()).get("authorizationUrl").asString()).get("state");
        answers.add(answer(mockMvc.perform(MockMvcRequestBuilders.get(CALLBACK).param("code", "code-pick").param("state", state)
                .param("picked_file_ids", "gateDoc1,gateDoc2").cookie(member.session())).andExpect(status().isFound()).andReturn()));
        assertThat(answers.getLast()).contains("google=picked").contains("added=2");
        long documentId = createDocument(member);
        answers.add(answer(importExpecting(member, documentId, grantOf(member, "gateDoc1"), 201).andReturn()));
        answers.add(answer(mockMvc.perform(MockMvcRequestBuilders.get(documentPath(member, documentId) + "/sources").cookie(member.session()))
                .andExpect(status().isOk()).andReturn()));
        answers.add(answer(mockMvc.perform(MockMvcRequestBuilders.get(connectionsPath(member)).cookie(member.session()))
                .andExpect(status().isOk()).andReturn()));
        answers.add(answer(mockMvc.perform(MockMvcRequestBuilders.post(drivePath(member) + "/files/" + grantOf(member, "gateDoc2") + "/forget")
                .cookie(member.session()).with(csrf())).andExpect(status().isOk()).andReturn()));
        answers.add(answer(mockMvc.perform(MockMvcRequestBuilders.post(connectionsPath(member) + "/google/disconnect")
                .cookie(member.session()).with(csrf())).andExpect(status().isOk()).andReturn()));

        List<LoggedRequest> sent = GOOGLE.findAll(anyRequestedFor(urlMatching("/.*")));
        assertThat(sent).allSatisfy(request -> {
            String method = request.getMethod().getName();
            String path = request.getUrl().split("\\?")[0];
            assertThat(method.equals("GET") || (method.equals("POST") && (path.equals("/token") || path.equals("/revoke"))))
                    .as("%s %s: nothing but a read reaches Google's APIs; only the token and revocation endpoints are posted to", method, path)
                    .isTrue();
        });
        assertThat(sent.stream().map(request -> request.getUrl().split("\\?")[0]).filter(path -> path.startsWith("/drive/")))
                .as("Drive is asked whose account it is, and never for a list of files").containsOnly("/drive/v3/about");

        List<String> secrets = new ArrayList<>();
        String verifier = formOf(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/token")).withRequestBody(containing("grant_type=authorization_code")))
                .getLast().getBodyAsString()).get("code_verifier");
        for (String secret : List.of(PICK_ACCESS, PICK_REFRESH, REFRESHED_ACCESS, CLIENT_SECRET, TOKEN_KEY, "code-pick", verifier)) {
            secrets.add(secret);
            secrets.add(URLEncoder.encode(secret, StandardCharsets.UTF_8));
        }
        String everything = String.join("\n", answers);
        assertThat(everything).as("nothing a browser is sent").doesNotContain(secrets.toArray(String[]::new));
        assertThat(output.getAll()).as("nothing logged").doesNotContain(secrets.toArray(String[]::new));
        assertThat(everything.replace(link, "")).as("a Drive id reaches the browser only inside the file's own link")
                .doesNotContain("gateDoc1").doesNotContain("gateDoc2");
        assertThat(output.getAll()).as("and is never logged").doesNotContain("gateDoc1").doesNotContain("gateDoc2");
        long artifactId = count("SELECT artifact_id FROM source_snapshot WHERE workspace_id = ? AND kind = 'GOOGLE_DRIVE'", member.workspaceId());
        assertThat(text("SELECT normalized_text FROM plain_text_extraction_version WHERE artifact_id = ?", artifactId))
                .as("nor the copy, which is all of Drive that a request to the model can ever include").doesNotContain(secrets.toArray(String[]::new));
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/revoke")))).as("disconnecting revokes at Google").hasSize(1);
        assertThat(count("SELECT count(*) FROM connector_resource_grant WHERE workspace_id = ? AND revoked_at IS NULL", member.workspaceId()))
                .as("and closes every choice").isZero();
        assertThat(count("SELECT count(*) FROM source_snapshot WHERE workspace_id = ? AND kind = 'GOOGLE_DRIVE'", member.workspaceId()))
                .as("while copies stay with their origin").isEqualTo(1);
    }

    // ---- helpers

    private static DriveFile doc(String id, String name, String version) {
        return new DriveFile(id, name, DriveFile.GOOGLE_DOC, false, true, null, version, OffsetDateTime.parse("2026-09-20T10:00:00Z"),
                "https://docs.google.com/document/d/" + id + "/edit");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void stubDriveAccount(String permissionId) {
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/about"))
                .willReturn(okJson("{\"user\":{\"permissionId\":\"" + permissionId + "\",\"emailAddress\":\"person@example.org\"}}")));
    }

    private Map<String, String> startPick(Member member, String returnTo) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post(drivePath(member) + "/picks")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"returnTo\":\"" + returnTo + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        return queryOf(JSON.readTree(result.getResponse().getContentAsString()).get("authorizationUrl").asString());
    }

    /** Brings Google's answer to a pick back with these ids, as Google's picker does; the address the person lands on. */
    private String answerPick(Member member, String state, String pickedFileIds) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(CALLBACK)
                        .param("code", "code-pick").param("state", state).param("iss", "https://accounts.google.com")
                        .param("scope", DRIVE_SCOPE).param("picked_file_ids", pickedFileIds)
                        .cookie(member.session()))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getRedirectedUrl();
    }

    private String pick(Member member, String pickedFileIds) throws Exception {
        return answerPick(member, startPick(member, "/connections").get("state"), pickedFileIds);
    }

    /** Moves the pick waiting in this person's session back in time, as if they had spent that long in Google's picker. */
    private void agePendingPick(Member member, java.time.Duration by) {
        String sessionId = new String(Base64.getDecoder().decode(member.session().getValue()), StandardCharsets.UTF_8);
        age(sessionRepository, sessionId, by);
    }

    private static <S extends Session> void age(FindByIndexNameSessionRepository<S> repository, String sessionId, java.time.Duration by) {
        S session = repository.findById(sessionId);
        PendingConsent pending = session.getAttribute(PendingConsent.SESSION_ATTRIBUTE);
        session.setAttribute(PendingConsent.SESSION_ATTRIBUTE, new PendingConsent(pending.state(), pending.codeVerifier(), pending.workspaceId(),
                pending.userId(), pending.access(), pending.returnTo(), pending.createdAtEpochSecond() - by.toSeconds(), pending.pick()));
        repository.save(session);
    }

    private JsonNode importFile(Member member, long documentId, long grantId) throws Exception {
        return JSON.readTree(importExpecting(member, documentId, grantId, 201)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions importExpecting(Member member, long documentId, long grantId, int expected) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(drivePath(member) + "/imports")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"documentId\":" + documentId + ",\"grantId\":" + grantId + "}"))
                .andExpect(status().is(expected));
    }

    private void refused(Member member, long documentId, String fileId, int status, String code, String reason) throws Exception {
        ResultActions answer = importExpecting(member, documentId, grantOf(member, fileId), status).andExpect(jsonPath("$.code").value(code));
        if (reason != null) {
            answer.andExpect(jsonPath("$.reason").value(reason));
        }
    }

    /**
     * Starts copying a file on another thread, holds it once Drive's content
     * is being read, does {@code meanwhile}, then lets the read finish; the
     * copy's answer.
     */
    private Future<MvcResult> heldCopy(ExecutorService executor, Member member, long documentId, long grantId, Meanwhile meanwhile)
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch mayFinish = new CountDownLatch(1);
        drive.holdReads(started, mayFinish);
        Future<MvcResult> copying = executor.submit(() -> mockMvc.perform(MockMvcRequestBuilders.post(drivePath(member) + "/imports")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"documentId\":" + documentId + ",\"grantId\":" + grantId + "}"))
                .andReturn());
        assertThat(started.await(30, TimeUnit.SECONDS)).as("the copy reached Drive's content").isTrue();
        try {
            meanwhile.run();
        } finally {
            mayFinish.countDown();
        }
        return copying;
    }

    @FunctionalInterface
    private interface Meanwhile {
        void run() throws Exception;
    }

    private long grantOf(Member member, String fileId) throws SQLException {
        return grantWhere(member, fileId, "revoked_at IS NULL");
    }

    private long forgottenGrantOf(Member member, String fileId) throws SQLException {
        return grantWhere(member, fileId, "revoked_at IS NOT NULL");
    }

    private long grantWhere(Member member, String fileId, String condition) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM connector_resource_grant WHERE workspace_id = ? AND external_id = ? AND " + condition + " ORDER BY id DESC LIMIT 1")) {
            statement.setLong(1, member.workspaceId());
            statement.setString(2, fileId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("a grant for %s where %s", fileId, condition).isTrue();
                return rs.getLong(1);
            }
        }
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
                + ",\"fields\":{},\"initialRevisionReason\":\"Created for a Drive import test.\"}";
        return JSON.readTree(mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/documents")
                        .cookie(member.session()).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
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

    private static String connectionsPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/connections";
    }

    private static String drivePath(Member member) {
        return connectionsPath(member) + "/google/drive";
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

    private static Map<String, String> formOf(String body) {
        Map<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String s256(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
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
        List<String> all = texts(sql, parameter);
        assertThat(all).as(sql).hasSize(1);
        return all.getFirst();
    }

    private static List<String> texts(String sql, long parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
                return values;
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
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
