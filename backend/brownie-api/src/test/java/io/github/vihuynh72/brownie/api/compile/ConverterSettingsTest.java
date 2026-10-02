package io.github.vihuynh72.brownie.api.compile;

import io.github.vihuynh72.brownie.core.compile.RenderSlots;
import io.github.vihuynh72.brownie.core.prepare.ConversionFormatDisabledException;
import io.github.vihuynh72.brownie.core.prepare.ConvertibleFormat;
import io.github.vihuynh72.brownie.core.prepare.DocumentConverter;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the conversion settings do once the application reads them. A
 * format left out is refused without a container being started (the image
 * named here does not exist, so reaching it would fail differently), and a
 * misspelt format stops the application starting instead of quietly
 * switching that format off.
 */
class ConverterSettingsTest {

    private final CompilationConfig config = new CompilationConfig();
    private final RenderSlots slots = new RenderSlots(1, Duration.ofMillis(10));

    @Test
    void aFormatLeftOutOfTheSettingIsRefusedBeforeAnyContainerStarts() {
        DocumentConverter converter = config.documentConverter(
                slots, "brownie-image-that-does-not-exist:never", "", "", 1024, "WORD_97,RTF");

        assertThatThrownBy(() -> converter.convertToDocx(new byte[] {1}, ConvertibleFormat.PAGES))
                .isInstanceOf(ConversionFormatDisabledException.class);
    }

    @Test
    void aMisspeltFormatIsRefusedAtStartUp() {
        assertThatThrownBy(() -> config.documentConverter(
                slots, "brownie-image-that-does-not-exist:never", "", "", 1024, "WORD_97,PAGE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAGE");
    }
}
