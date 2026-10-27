package io.github.vihuynh72.brownie.api.transcription;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vihuynh72.brownie.core.model.Transcription;
import io.github.vihuynh72.brownie.core.model.TranscriptionGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The committed fixture is a real person-made recording of "My name is
 * Jordan Rivera and my phone number is 408 555 0142" -- this checks the
 * configured speech model actually hears those words. Skipped unless a real
 * key is set.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@EnabledIf(
        value = "hasRealApiKey",
        disabledReason = "BROWNIE_OPENAI_API_KEY is not set to a real-looking key; skipping the real-model check.")
class TranscriptionRealModelIntegrationTest {

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
    private TranscriptionGateway gateway;

    @Test
    void theSpeechModelHearsTheName() throws Exception {
        byte[] wav = Files.readAllBytes(
                Path.of("src/test/resources/voice-jordan.wav"));
        Transcription answer = gateway.transcribe(wav, "voice-jordan.wav");
        assertThat(answer.text()).contains("Jordan Rivera");
    }
}
