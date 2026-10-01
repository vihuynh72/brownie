package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.generation.CancellationSignal;
import io.github.vihuynh72.brownie.core.generation.ExtractionResult;
import io.github.vihuynh72.brownie.core.generation.ExtractionService;
import io.github.vihuynh72.brownie.core.generation.FieldCandidate;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.source.SourceService;
import io.github.vihuynh72.brownie.core.source.SourceSnapshot;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplateRegistry;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the whole grounded-extraction pipeline works end to end against
 * the real OpenAI API, not a stub -- real Postgres/Azurite/ClamAV (the
 * same infrastructure {@code SourceIntegrationTest} already establishes)
 * plus a real model call, using whatever real {@code
 * BROWNIE_OPENAI_API_KEY} is set in this shell's own environment.
 *
 * <p>Skips itself cleanly, rather than failing, when no real-looking key
 * is present, and decides so before it starts a container or a context
 * -- this is the one test in the suite that spends real,
 * if tiny, money and needs a real network path to api.openai.com, neither
 * of which a CI run or a fresh checkout can assume. A model's own reply is
 * not deterministic, so this test's assertions stay loose: it checks the
 * result has exactly the fields asked for and that any evidence citation
 * is real, not that the model's wording matches a fixed string.
 */
@SpringBootTest
@ActiveProfiles("test")
@DockerTest
@EnabledIf(
        value = "hasRealApiKey",
        disabledReason = "BROWNIE_OPENAI_API_KEY is not set to a real-looking key; skipping the real-model eval.")
class ExtractionIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("brownie.storage.local-connection", DB::azuriteConnectionString);
        registry.add("brownie.security.clamav.host", SharedContainers::clamAvHost);
        registry.add("brownie.security.clamav.port", SharedContainers::clamAvPort);
        String realKey = System.getenv("BROWNIE_OPENAI_API_KEY");
        if (hasRealApiKey()) {
            registry.add("spring.ai.openai.api-key", () -> realKey);
        }
    }

    private static boolean hasRealApiKey() {
        String key = System.getenv("BROWNIE_OPENAI_API_KEY");
        return key != null && key.startsWith("sk-") && key.length() > 20;
    }

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private SourceService sourceService;

    @Autowired
    private ExtractionService extractionService;

    /** The rates of whichever model this run is configured to call, so an evaluation of another model is budgeted at its own price. */
    @Autowired
    private ModelPricing modelPricing;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Test
    void extractsRealMeetingFactsFromARealTranscriptUsingTheRealModel() throws Exception {
        Assumptions.assumeTrue(
                hasRealApiKey(), "BROWNIE_OPENAI_API_KEY is not set to a real-looking key; skipping the real-model smoke test.");

        long workspaceId = ensureWorkspace("https://issuer-extraction-integration", "subject-extraction-real").id();
        long userId = userIdentityRepository.findByIssuerAndSubject("https://issuer-extraction-integration", "subject-extraction-real")
                .orElseThrow()
                .id();

        String transcript = """
                Weekly Robotics Club Sync

                The meeting was called to order by Priya Rao. The meeting title is
                "Weekly Robotics Club Sync" and it took place on 2026-03-05.

                Alex Chen agreed to finish wiring the practice robot by 2026-03-12.

                Jose Nunez will confirm the van reservation for the regional
                competition by 2026-03-10.
                """;
        long artifactId = uploadAndFinalize(workspaceId, userId, transcript);
        SourceSnapshot snapshot = sourceService.attachSnapshot(workspaceId, userId, artifactId);
        TemplateVersion templateVersion = flowingMinutesTemplateVersion(workspaceId);

        UsageBudget budget = new UsageBudget(UsageLimits.defaultRunLimits(), modelPricing);
        ExtractionResult result =
                extractionService.extract(workspaceId, userId, snapshot, templateVersion, budget, CancellationSignal.never());

        assertThat(result.scalarCandidates().keySet())
                .containsExactlyInAnyOrder(
                        "meeting.title", "meeting.organization", "meeting.date", "meeting.location", "meeting.attendees",
                        "meeting.decisions");

        FieldCandidate title = result.scalarCandidates().get("meeting.title");
        assertThat(title.unresolved()).isFalse();
        assertThat(title.value()).containsIgnoringCase("Weekly Robotics Club Sync");
        assertThat(title.evidenceSpanIds()).isNotEmpty();

        FieldCandidate date = result.scalarCandidates().get("meeting.date");
        assertThat(date.unresolved()).isFalse();
        assertThat(date.value()).isEqualTo("2026-03-05");

        assertThat(result.repeatedItems()).isNotEmpty();
        for (var item : result.repeatedItems()) {
            assertThat(item.fieldIds()).containsExactlyInAnyOrder("action.item.task", "action.item.owner", "action.item.due");
        }
    }

    private long uploadAndFinalize(long workspaceId, long userId, String text) {
        Artifact allocated = artifactService.initiateUpload(workspaceId, userId, "transcript.txt");
        artifactService.receiveContent(
                workspaceId, userId, allocated.id(), new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        Artifact finalized = artifactService.finalizeUpload(workspaceId, userId, allocated.id());
        assertThat(finalized.status().name()).isEqualTo("READY");
        return finalized.id();
    }

    private static TemplateVersion flowingMinutesTemplateVersion(long workspaceId) {
        var builtIn = BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow();
        return new TemplateVersion(1, workspaceId, 1, 1, 1, 1, TemplateVersionStatus.ACTIVATED, builtIn.fields(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private Workspace ensureWorkspace(String issuer, String subject) {
        userIdentityRepository.recordLogin(issuer, subject, null, null);
        var identity = userIdentityRepository.findByIssuerAndSubject(issuer, subject).orElseThrow();
        return workspaceRepository.ensurePersonalWorkspace(identity.id());
    }
}
