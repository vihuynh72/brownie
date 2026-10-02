package io.github.vihuynh72.brownie.core.template;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldIdsTest {

    // ---- fromLabel ----------------------------------------------------------------------------

    @Test
    void anAccentedLabelLosesItsAccentsAndItsSpacesBecomeDots() {
        assertEquals("ho.va.ten", FieldIds.fromLabel("H\u1ecd v\u00e0 t\u00ean", Set.of()));
        assertEquals("ngay.sinh", FieldIds.fromLabel("Ng\u00e0y sinh", Set.of()));
        assertEquals("date.de.naissance", FieldIds.fromLabel("Date de naissance", Set.of()));
        assertEquals("company.name", FieldIds.fromLabel("  Company   name:  ", Set.of()));
    }

    @Test
    void aDWithAStrokeBecomesAPlainD() {
        assertEquals("dia.chi", FieldIds.fromLabel("\u0110\u1ecba ch\u1ec9", Set.of()));
        assertEquals("duong", FieldIds.fromLabel("\u0111\u01b0\u1eddng", Set.of()));
    }

    @Test
    void compatibilityFormsFoldToPlainLetters() {
        // Full-width letters and a ligature are the same letters written differently.
        assertEquals("name", FieldIds.fromLabel("\uff2e\uff41\uff4d\uff45", Set.of()));
        assertEquals("office", FieldIds.fromLabel("O\ufb03ce", Set.of()));
    }

    @Test
    void aLabelWithNothingLeftIsASpot() {
        assertEquals("spot", FieldIds.fromLabel("\u59d3\u540d", Set.of()));
        assertEquals("spot", FieldIds.fromLabel("\u0418\u043c\u044f", Set.of()));
        assertEquals("spot", FieldIds.fromLabel("  :  ", Set.of()));
        assertEquals("spot", FieldIds.fromLabel("", Set.of()));
        assertEquals("spot", FieldIds.fromLabel(null, Set.of()));
    }

    @Test
    void aLabelStartingWithADigitIsPrefixedSoTheIdStartsWithALetter() {
        assertEquals("spot.2024.budget", FieldIds.fromLabel("2024 budget", Set.of()));
        assertEquals("spot.1", FieldIds.fromLabel("1.", Set.of()));
        assertEquals("line.2", FieldIds.fromLabel("Line 2", Set.of()));
    }

    @Test
    void aLabelMixingScriptsKeepsOnlyTheLettersAnIdCanHold() {
        assertEquals("name", FieldIds.fromLabel("\u59d3\u540d / Name", Set.of()));
        assertEquals("e.mail", FieldIds.fromLabel("E-mail", Set.of()));
    }

    @Test
    void aTakenIdGetsTheNextFreeNumberIgnoringCase() {
        assertEquals("company.2", FieldIds.fromLabel("Company", Set.of("company")));
        assertEquals("company.3", FieldIds.fromLabel("Company", Set.of("company", "company.2")));
        assertEquals("company.2", FieldIds.fromLabel("Company", Set.of("Company")));
        assertEquals("spot.2", FieldIds.fromLabel("\u59d3\u540d", Set.of("spot")));
        assertEquals("company", FieldIds.fromLabel("Company", Set.of("company.2")));
    }

    @Test
    void idsMadeOneAfterAnotherNeverRepeat() {
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            assertTrue(taken.add(FieldIds.fromLabel("Signature date", taken)));
        }
        assertTrue(taken.contains("signature.date.12"));
    }

    @Test
    void anIdIsAtMostFortyEightCharactersAndNeverEndsInADot() {
        String longLabel = "Name of the person who is responsible for signing this particular form";
        String id = FieldIds.fromLabel(longLabel, Set.of());

        assertEquals("name.of.the.person.who.is.responsible.for.signin", id);
        assertEquals(FieldIds.MAX_ID_LENGTH, id.length());

        // Cutting at 48 would end on a dot; the dot goes.
        String cutOnADot = FieldIds.fromLabel("abcdefghij abcdefghij abcdefghij abcdefghij abc defg", Set.of());
        assertEquals("abcdefghij.abcdefghij.abcdefghij.abcdefghij.abc", cutOnADot);

        String numbered = FieldIds.fromLabel(longLabel, Set.of(id));
        assertEquals("name.of.the.person.who.is.responsible.for.sign.2", numbered);
        assertTrue(numbered.length() <= FieldIds.MAX_ID_LENGTH);
    }

    @Test
    void everyIdMadeFromALabelIsSafe() {
        String[] labels = {
            "H\u1ecd v\u00e0 t\u00ean", "\u59d3\u540d", "2024", "...", "a", "Z", "x--y__z", "\u00c9t\u00e9 2026",
            "\u0130stanbul", "\ud83d\ude00 Mood", "Name of the person who is responsible for signing this particular form"
        };
        for (String label : labels) {
            String id = FieldIds.fromLabel(label, Set.of());
            assertTrue(FieldIds.isSafeId(id), label + " gave " + id);
            assertTrue(id.matches("[a-z][a-z0-9.]*") && !id.endsWith(".") && !id.contains(".."), label + " gave " + id);
        }
        assertEquals("istanbul", FieldIds.fromLabel("\u0130stanbul", Set.of()));
    }

    // ---- normalizeLabel -----------------------------------------------------------------------

    @Test
    void aLabelIsComposedAndItsSpacesAreCollapsed() {
        assertEquals("H\u1ecd v\u00e0 t\u00ean", FieldIds.normalizeLabel("  Ho\u0323   va\u0300\tte\u0302n "));
        assertEquals("Company name", FieldIds.normalizeLabel("Company\u00a0name"));
        assertEquals("Company name", FieldIds.normalizeLabel("Company\u3000 name"));
        assertEquals("\u59d3\u540d", FieldIds.normalizeLabel("\u59d3\u540d"));
        assertEquals("7", FieldIds.normalizeLabel("7"));
    }

    @Test
    void invisibleFormattingIsDroppedButTheJoinersSomeScriptsNeedStay() {
        assertEquals("Company", FieldIds.normalizeLabel("Com\u200bpany\ufeff"));
        assertEquals("Name", FieldIds.normalizeLabel("\u202eName\u202c"));
        assertEquals("\u0645\u06cc\u200c\u062e\u0648\u0627\u0647\u0645", FieldIds.normalizeLabel("\u0645\u06cc\u200c\u062e\u0648\u0627\u0647\u0645"));
    }

    @Test
    void aLabelThatIsNotOneLineIsRefused() {
        assertNull(FieldIds.normalizeLabel("First line\nSecond line"));
        assertNull(FieldIds.normalizeLabel("First line\r\n"));
        assertNull(FieldIds.normalizeLabel("Name\u2028"));
        assertNull(FieldIds.normalizeLabel("Name\u0085"));
    }

    @Test
    void aLabelWithAControlCharacterIsRefusedRatherThanMended() {
        assertNull(FieldIds.normalizeLabel("Name\u0000"));
        assertNull(FieldIds.normalizeLabel("Na\u0007me"));
        assertNull(FieldIds.normalizeLabel("Name\u001f"));
        assertNull(FieldIds.normalizeLabel("Name\u009b"));
        assertNull(FieldIds.normalizeLabel("Name\ud800"));
    }

    @Test
    void anEmptyOrWordlessLabelIsRefused() {
        assertNull(FieldIds.normalizeLabel(null));
        assertNull(FieldIds.normalizeLabel(""));
        assertNull(FieldIds.normalizeLabel("   \t "));
        assertNull(FieldIds.normalizeLabel("____"));
        assertNull(FieldIds.normalizeLabel(":-"));
        assertNull(FieldIds.normalizeLabel("\u200b"));
    }

    @Test
    void aLabelIsAtMostSixtyCharacters() {
        String sixty = "a".repeat(60);
        assertEquals(sixty, FieldIds.normalizeLabel(sixty));
        assertEquals(sixty, FieldIds.normalizeLabel("  " + sixty + "  "));
        assertNull(FieldIds.normalizeLabel("a".repeat(61)));
        // Sixty characters outside the basic plane are sixty characters, not one hundred and twenty.
        String sixtyEmoji = "\ud83d\ude00".repeat(59) + "a";
        assertEquals(sixtyEmoji, FieldIds.normalizeLabel(sixtyEmoji));
    }

    // ---- isValidBlankText ----------------------------------------------------------------------

    @Test
    void aBlankIsOneLineOfUpToTwoHundredCharactersWithoutControlCharacters() {
        assertTrue(FieldIds.isValidBlankText("________"));
        assertTrue(FieldIds.isValidBlankText("[Company]"));
        assertTrue(FieldIds.isValidBlankText("     "));
        assertTrue(FieldIds.isValidBlankText("_".repeat(200)));
        assertFalse(FieldIds.isValidBlankText("_".repeat(201)));
        assertFalse(FieldIds.isValidBlankText(""));
        assertFalse(FieldIds.isValidBlankText(null));
        assertFalse(FieldIds.isValidBlankText("____\n____"));
        assertFalse(FieldIds.isValidBlankText("____\t____"));
        assertFalse(FieldIds.isValidBlankText("____\u0000"));
    }

    // ---- labelFor and isSafeId -----------------------------------------------------------------

    @Test
    void theWorkedOutLabelReadsLikeTheId() {
        assertEquals("Meeting title", FieldIds.labelFor("meeting.title"));
        assertEquals("Action item due", FieldIds.labelFor("action.item.due"));
        assertEquals("Meeting title", FieldIds.labelFor("meeting_title"));
        assertEquals("Meeting date", FieldIds.labelFor("meeting-date"));
        assertEquals("...", FieldIds.labelFor("..."));
    }

    @Test
    void aSafeIdStartsWithALetterAndHoldsOnlyPlainCharacters() {
        assertTrue(FieldIds.isSafeId("meeting.title"));
        assertTrue(FieldIds.isSafeId("Action_Item-2"));
        assertFalse(FieldIds.isSafeId(null));
        assertFalse(FieldIds.isSafeId(""));
        assertFalse(FieldIds.isSafeId("2024.budget"));
        assertFalse(FieldIds.isSafeId(".title"));
        assertFalse(FieldIds.isSafeId("title\""));
        assertFalse(FieldIds.isSafeId("ti tle"));
        assertFalse(FieldIds.isSafeId("h\u1ecd"));
    }

    // ---- FieldDefinition ----------------------------------------------------------------------

    @Test
    void aDefinitionWithoutTheOptionalPartsCameWithTheFormAndIsNamedAfterItsId() {
        FieldDefinition plain = new FieldDefinition(
                "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.title"));

        assertNull(plain.label());
        assertNull(plain.origin());
        assertNull(plain.docxControl());
        assertNull(plain.blankText());
        assertEquals(SpotOrigin.FORM, plain.effectiveOrigin());
        assertEquals("Meeting title", plain.displayLabel());
    }

    @Test
    void aStoredLabelAndOriginAreWhatAPersonSees() {
        FieldDefinition found = new FieldDefinition(
                "ho.va.ten", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.ContentControlTag("ho.va.ten"),
                "H\u1ecd v\u00e0 t\u00ean", SpotOrigin.FOUND_BY_BROWNIE, DocxControlOrigin.INSERTED_BY_BROWNIE, "________");

        assertEquals("H\u1ecd v\u00e0 t\u00ean", found.displayLabel());
        assertEquals(SpotOrigin.FOUND_BY_BROWNIE, found.effectiveOrigin());
    }
}
