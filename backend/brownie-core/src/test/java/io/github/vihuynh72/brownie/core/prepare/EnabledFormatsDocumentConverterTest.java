package io.github.vihuynh72.brownie.core.prepare;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnabledFormatsDocumentConverterTest {

    private static final ConvertedDocument CONVERTED = new ConvertedDocument(new byte[] {1}, "test converter");

    @Test
    void aFormatThatIsSwitchedOffNeverReachesTheConverter() {
        AtomicInteger calls = new AtomicInteger();
        DocumentConverter counting = (source, format) -> {
            calls.incrementAndGet();
            return CONVERTED;
        };
        DocumentConverter gated = new EnabledFormatsDocumentConverter(counting, EnumSet.of(ConvertibleFormat.ODT));

        ConversionFormatDisabledException refused = assertThrows(ConversionFormatDisabledException.class,
                () -> gated.convertToDocx(new byte[] {1}, ConvertibleFormat.PAGES));

        assertEquals(ConvertibleFormat.PAGES, refused.format());
        assertTrue(refused.getMessage().contains("switched off"));
        assertEquals(0, calls.get());
        assertEquals(CONVERTED, gated.convertToDocx(new byte[] {1}, ConvertibleFormat.ODT));
        assertEquals(1, calls.get());
    }

    @Test
    void theSettingNamesTheFormatsLeftOnInAnyCaseAndEmptyTurnsThemAllOff() {
        assertEquals(EnumSet.allOf(ConvertibleFormat.class),
                EnabledFormatsDocumentConverter.parseEnabledFormats("WORD_97,WORD_95,RTF,ODT,ODT_TEMPLATE,PAGES"));
        assertEquals(EnumSet.of(ConvertibleFormat.RTF, ConvertibleFormat.ODT),
                EnabledFormatsDocumentConverter.parseEnabledFormats(" rtf , Odt ,"));
        assertEquals(EnumSet.noneOf(ConvertibleFormat.class), EnabledFormatsDocumentConverter.parseEnabledFormats(""));
        assertEquals(EnumSet.noneOf(ConvertibleFormat.class), EnabledFormatsDocumentConverter.parseEnabledFormats(null));
    }

    @Test
    void aMisspeltFormatIsRefusedRatherThanQuietlySwitchingThatFormatOff() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> EnabledFormatsDocumentConverter.parseEnabledFormats("WORD_97,WORD97"));

        assertTrue(refused.getMessage().contains("WORD97"));
    }

    @Test
    void withEveryFormatOffEverythingIsRefused() {
        DocumentConverter gated = new EnabledFormatsDocumentConverter((source, format) -> CONVERTED,
                EnabledFormatsDocumentConverter.parseEnabledFormats(""));

        for (ConvertibleFormat format : ConvertibleFormat.values()) {
            assertThrows(ConversionFormatDisabledException.class, () -> gated.convertToDocx(new byte[] {1}, format));
        }
    }
}
