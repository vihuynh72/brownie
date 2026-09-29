package io.github.vihuynh72.brownie.core.action;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What survived a conversion is counted the same way on both sides, and short values are not counted at all. */
class ConversionCheckTest {

    @Test
    void valuesAreFoundWhateverWhitespaceOrCompositionTheConversionChose() {
        String converted = "Spring\u00A0Budget\u000bPlanning\n\uE907March 5,   2026\nCafe\u0301 notes";
        ConversionCount count = ConversionCheck.count(List.of("Spring Budget Planning", "March 5, 2026", "Caf\u00E9 notes"), converted);
        assertEquals(new ConversionCount(3, 3), count);
        assertTrue(count.complete());
    }

    @Test
    void aValueTheConversionDroppedIsCountedAsMissingAndAShortOneIsNotLookedFor() {
        ConversionCount count = ConversionCheck.count(List.of("Spring Budget Planning", "Book the room", "No", " "), "Spring Budget Planning");
        assertEquals(new ConversionCount(2, 1), count);
        assertFalse(count.complete());
        assertEquals(ActionVerification.CONVERSION_DIFFERS, count.verification());
    }

    @Test
    void aConversionWithNothingLongEnoughToLookForIsNeverCalledChecked() {
        ConversionCount nothing = ConversionCheck.count(List.of("No", "5"), "No 5");
        assertEquals(new ConversionCount(0, 0), nothing);
        assertFalse(nothing.complete());
        assertEquals(ActionVerification.CONVERSION_UNCHECKED, nothing.verification());
        assertEquals(ActionVerification.CONVERSION_CHECKED, new ConversionCount(2, 2).verification());
    }
}
