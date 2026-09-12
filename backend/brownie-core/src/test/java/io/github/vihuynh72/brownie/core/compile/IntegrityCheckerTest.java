package io.github.vihuynh72.brownie.core.compile;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrityCheckerTest {

    @Test
    void findsTextPresentInBothReopenedDocxAndRenderedPdf() {
        List<IntegrityFinding> findings = IntegrityChecker.check(
                Map.of("meeting.title", List.of("Spring Budget Planning")),
                "Minutes for Spring Budget Planning meeting.",
                "Spring   Budget\nPlanning");

        assertEquals(1, findings.size());
        IntegrityFinding finding = findings.getFirst();
        assertEquals("meeting.title", finding.fieldId());
        assertTrue(finding.foundInDocx());
        assertTrue(finding.foundInPdf());
        assertTrue(finding.passed());
    }

    @Test
    void reportsMissingTextIndependentlyPerLocation() {
        List<IntegrityFinding> findings = IntegrityChecker.check(
                Map.of("meeting.title", List.of("October minutes")),
                "The document only ever said September minutes.",
                "October minutes rendered fine.");

        IntegrityFinding finding = findings.getFirst();
        assertTrue(!finding.foundInDocx());
        assertTrue(finding.foundInPdf());
        assertTrue(!finding.passed());
    }

    @Test
    void skipsBlankIntendedValuesEntirely() {
        List<IntegrityFinding> findings = IntegrityChecker.check(
                Map.of("meeting.organization", List.of("")),
                "anything",
                "anything");

        assertTrue(findings.isEmpty());
    }

    @Test
    void checksEveryRepeatedValueOnItsOwn() {
        List<IntegrityFinding> findings = IntegrityChecker.check(
                Map.of("action.item.task", List.of("Reserve the van", "Confirm sponsor logo")),
                "Reserve the van for the trip.",
                "Reserve the van for the trip. Confirm sponsor logo placement.");

        assertEquals(2, findings.size());
        assertTrue(findings.stream().anyMatch(f -> f.expectedText().equals("Reserve the van") && f.foundInDocx() && f.foundInPdf()));
        assertTrue(findings.stream().anyMatch(f -> f.expectedText().equals("Confirm sponsor logo") && !f.foundInDocx() && f.foundInPdf()));
    }
}
