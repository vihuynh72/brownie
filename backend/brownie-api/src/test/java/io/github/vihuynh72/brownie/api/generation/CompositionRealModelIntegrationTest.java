package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.core.generation.CancellationSignal;
import io.github.vihuynh72.brownie.core.generation.CompositionService;
import io.github.vihuynh72.brownie.core.generation.ExtractionResult;
import io.github.vihuynh72.brownie.core.generation.FieldCandidate;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplateRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link CompositionService} against the real OpenAI API rather
 * than a fake -- whether the real prompt/schema shape actually produces a
 * sensible, correctly-cited composed value is a real-model question a
 * fake gateway cannot answer, the same reasoning {@code
 * ExtractionAdversarialIntegrationTest} already applies to its own two
 * real-model categories. Unlike that test, {@link CompositionService}
 * itself has no database, storage, or artifact dependency at all -- it
 * takes already-accepted facts as plain arguments -- so this test needs
 * no Postgres, Azurite, or ClamAV container, only the same lightweight,
 * Docker-free context {@code BrownieApiApplicationTests} already proves
 * is sufficient to construct every generation-side bean.
 *
 * <p>Skips itself cleanly, the same as {@code ExtractionIntegrationTest},
 * when no real-looking {@code BROWNIE_OPENAI_API_KEY} is present.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class CompositionRealModelIntegrationTest {

    @DynamicPropertySource
    static void openAiProperties(DynamicPropertyRegistry registry) {
        if (hasRealApiKey()) {
            registry.add("spring.ai.openai.api-key", () -> System.getenv("BROWNIE_OPENAI_API_KEY"));
        }
    }

    private static boolean hasRealApiKey() {
        String key = System.getenv("BROWNIE_OPENAI_API_KEY");
        return key != null && key.startsWith("sk-") && key.length() > 20;
    }

    @Autowired
    private CompositionService compositionService;

    @Test
    void composesAConciseDecisionsSummaryFromRealAcceptedFactsCitingOnlyRealOfferedEvidence() throws Exception {
        Assumptions.assumeTrue(hasRealApiKey(), "BROWNIE_OPENAI_API_KEY is not set to a real-looking key; skipping the real-model composition eval.");

        FieldCandidate title = new FieldCandidate("meeting.title", "Spring Budget Planning", List.of(101L), false, null);
        FieldCandidate date = new FieldCandidate("meeting.date", "2026-04-02", List.of(103L), false, null);
        FieldCandidate rawDecisions = new FieldCandidate(
                "meeting.decisions",
                "after a long discussion the club voted six to one in favor of purchasing new team uniforms before the "
                        + "regional competition, and separately, without much debate, agreed to reserve the club van for "
                        + "the same trip so that everyone could travel together",
                List.of(102L), false, null);
        ExtractionResult acceptedFacts = new ExtractionResult(
                Map.of("meeting.title", title, "meeting.date", date, "meeting.decisions", rawDecisions), List.of());

        ExtractionResult composed = compositionService.compose(
                BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow().fields(),
                List.of("meeting.decisions"),
                List.of(),
                acceptedFacts,
                new UsageBudget(UsageLimits.defaultRunLimits(), ModelPricing.gpt5Mini()),
                CancellationSignal.never());

        FieldCandidate composedDecisions = composed.scalarCandidates().get("meeting.decisions");
        assertThat(composedDecisions.unresolved()).isFalse();
        assertThat(composedDecisions.value()).isNotBlank();
        assertThat(composedDecisions.value().length()).isLessThan(rawDecisions.value().length());
        assertThat(composedDecisions.evidenceSpanIds()).isNotEmpty();
        assertThat(composedDecisions.evidenceSpanIds()).allMatch(List.of(101L, 102L, 103L)::contains);
        // The untouched fact passes through unchanged alongside the newly composed one.
        assertThat(composed.scalarCandidates().get("meeting.title")).isEqualTo(title);
    }
}
