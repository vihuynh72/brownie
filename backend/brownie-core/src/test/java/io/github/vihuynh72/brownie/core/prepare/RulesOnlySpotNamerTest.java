package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Naming by the rules alone: what runs when the model is switched off, and what every fallback gives. */
class RulesOnlySpotNamerTest {

    private static final NamingCandidate NAME = new NamingCandidate("c1", "UNDERSCORES", "Full name", FieldType.TEXT, null, false, null);
    private static final NamingCandidate SIGN = new NamingCandidate("c2", "UNDERSCORES", "Signature", FieldType.TEXT, null, true, "T1R2");
    private static final NamingCandidate ITEM = new NamingCandidate("c3", "EMPTY_CELL", "Item", FieldType.TEXT, null, false, "T1R3");
    private static final NamingCandidate WHEN = new NamingCandidate("c4", "EMPTY_CELL", "Date", FieldType.DATE, null, false, "T1R3");

    @Test
    void everyPlaceThatIsNotForASignatureIsKeptUnderTheRulesNameAndType() {
        SpotNaming naming = new RulesOnlySpotNamer(SpotNaming.DISABLED).name(
                7, 3, new SpotNamingInput(DocumentKind.WORD, List.of(), List.of(NAME, SIGN, ITEM, WHEN), List.of("T1R2", "T1R3")));

        assertEquals(List.of(
                new NamedSpot("c1", true, "Full name", FieldType.TEXT, "TEXT", false, false),
                new NamedSpot("c2", false, "Signature", FieldType.TEXT, "TEXT", false, false),
                new NamedSpot("c3", true, "Item", FieldType.TEXT, "TEXT", false, false),
                new NamedSpot("c4", true, "Date", FieldType.DATE, "DATE", false, false)), naming.spots());
        assertEquals(NamingSource.RULES, naming.source());
        assertEquals(SpotNaming.DISABLED, naming.rulesOnlyReason());
        assertEquals(List.of(), naming.notices());
        // T1R2 holds only the signature line, which is not kept, so the first row with a place to fill is T1R3.
        assertEquals("T1R3", naming.repeatingRowKey());
    }

    @Test
    void noRowRepeatsWhenNoKeptPlaceSitsInAnOfferedRow() {
        SpotNaming naming = new RulesOnlySpotNamer(SpotNaming.DISABLED).name(
                7, 3, new SpotNamingInput(DocumentKind.PDF, List.of(), List.of(NAME, SIGN), List.of("T1R2", "T9R9")));

        assertNull(naming.repeatingRowKey());
    }

    @Test
    void twoCandidatesCannotShareAnId() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpotNamingInput(DocumentKind.WORD, List.of(), List.of(NAME, NAME), List.of()));
    }
}
