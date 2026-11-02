package io.github.vihuynh72.brownie.api.detail;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class DetailSealerTest {

    private static final String GOOD = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void aSealedValueOpensToTheSameWords() {
        DetailSealer sealer = DetailSealer.fromBase64(GOOD).orElseThrow();
        byte[] sealed = sealer.seal("123-45-6789");
        assertEquals("123-45-6789", sealer.open(sealed));
    }

    @Test
    void everySealGetsItsOwnNonceSoTwoOfTheSameValueDiffer() {
        DetailSealer sealer = DetailSealer.fromBase64(GOOD).orElseThrow();
        assertNotEquals(java.util.Arrays.toString(sealer.seal("same value")), java.util.Arrays.toString(sealer.seal("same value")));
    }

    @Test
    void aTamperedValueIsRefused() {
        DetailSealer sealer = DetailSealer.fromBase64(GOOD).orElseThrow();
        byte[] sealed = sealer.seal("123-45-6789");
        sealed[sealed.length - 1] ^= 0x01;
        assertThrows(IllegalStateException.class, () -> sealer.open(sealed));
    }

    @Test
    void withNoKeyTheSealerStillBuildsButRefusesToSeal() {
        DetailSealer absent = DetailSealer.absent();
        assertThrows(DetailSealingUnavailableException.class, () -> absent.seal("anything"));
        assertTrue(DetailSealer.fromBase64("").isEmpty());
        assertTrue(DetailSealer.fromBase64("  ").isEmpty());
    }

    @Test
    void aMalformedKeyIsRefusedAtBuildTime() {
        assertThrows(IllegalStateException.class, () -> DetailSealer.fromBase64("not base64 !!!"));
        assertThrows(IllegalStateException.class, () -> DetailSealer.fromBase64(Base64.getEncoder().encodeToString(new byte[16])));
    }

    @Test
    void theHintKeepsOnlyTheLastFourCharacters() {
        assertEquals("6789", DetailSealer.hint("123-45-6789"));
        assertEquals("1234", DetailSealer.hint("1234"));
        assertThrows(IllegalArgumentException.class, () -> DetailSealer.hint(""));
    }
}
