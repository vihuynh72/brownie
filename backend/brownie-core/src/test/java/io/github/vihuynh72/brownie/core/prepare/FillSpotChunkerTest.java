package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.generation.usage.UsageBudget;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a form is split into the parts sent one request each: a table stays
 * with its heading, nothing is lost between parts, and what does not fit
 * the allowed parts is left for the rules rather than dropped.
 */
class FillSpotChunkerTest {

    @Test
    void aTableAndTheLineBeforeItStayInOnePart() {
        List<OutlineLine> outline = List.of(
                new OutlineLine("P1", "Name: [[c1]]"),
                new OutlineLine("P2", "Phone: [[c2]]"),
                new OutlineLine("P3", "Items ordered"),
                new OutlineLine("T1R1", "[[c3]] | [[c4]]"),
                new OutlineLine("T1R2", "[[c5]] | [[c6]]"),
                new OutlineLine("P4", "Date: [[c7]]"));

        FillSpotChunker.Plan plan = FillSpotChunker.plan(input(outline, 7, List.of("T1R2")), 4, 10_000, 4);

        assertEquals(List.of(List.of("c1", "c2"), List.of("c3", "c4", "c5", "c6"), List.of("c7")), ids(plan));
        assertEquals(List.of(), plan.leftOver());
        String table = plan.chunks().get(1).request().messages().get(1).content();
        assertTrue(table.contains("\n[P3] Items ordered\n[T1R1] [[c3]] | [[c4]]\n[T1R2 can-repeat] [[c5]] | [[c6]]\n"), table);
        assertEquals(List.of("T1R2"), plan.chunks().get(1).rowKeys());
        assertEquals(List.of(), plan.chunks().get(0).rowKeys());
    }

    @Test
    void aTableTooBigForOnePartIsCutBetweenItsRowsAndLosesNothing() {
        List<OutlineLine> outline = List.of(
                new OutlineLine("P1", "Items"),
                new OutlineLine("T1R1", "[[c1]] | [[c2]]"),
                new OutlineLine("T1R2", "[[c3]] | [[c4]]"),
                new OutlineLine("T1R3", "[[c5]] | [[c6]]"));

        FillSpotChunker.Plan plan = FillSpotChunker.plan(input(outline, 6, List.of()), 4, 10_000, 3);

        assertEquals(List.of(List.of("c1", "c2"), List.of("c3", "c4"), List.of("c5", "c6")), ids(plan));
        assertEquals(List.of(), plan.leftOver());
    }

    @Test
    void aLineWithMorePlacesThanAnyPartHoldsIsLeftForTheRules() {
        List<OutlineLine> outline = List.of(
                new OutlineLine("P1", "[[c1]] [[c2]] [[c3]] [[c4]]"),
                new OutlineLine("P2", "Name: [[c5]]"));

        FillSpotChunker.Plan plan = FillSpotChunker.plan(input(outline, 5, List.of()), 4, 10_000, 3);

        assertEquals(List.of(List.of("c5")), ids(plan));
        assertEquals(List.of("c1", "c2", "c3", "c4"), plan.leftOver().stream().map(NamingCandidate::id).toList());
    }

    @Test
    void placesBeyondTheLastAllowedPartAreLeftOverInOrder() {
        List<OutlineLine> outline = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            outline.add(new OutlineLine("P" + i, "Blank: [[c" + i + "]]"));
        }

        FillSpotChunker.Plan plan = FillSpotChunker.plan(input(outline, 7, List.of()), 2, 10_000, 3);

        assertEquals(List.of(List.of("c1", "c2", "c3"), List.of("c4", "c5", "c6")), ids(plan));
        assertEquals(List.of("c7"), plan.leftOver().stream().map(NamingCandidate::id).toList());
    }

    @Test
    void aPlaceNoLineMarksIsStillAskedAbout() {
        List<OutlineLine> outline = List.of(new OutlineLine("p1.l1", "Name: [[c1]]"));

        FillSpotChunker.Plan plan = FillSpotChunker.plan(input(outline, 2, List.of()), 4, 10_000, 150);

        assertEquals(List.of(List.of("c1", "c2")), ids(plan));
        assertTrue(plan.chunks().getFirst().request().messages().get(1).content().contains("{\"id\":\"c2\""));
    }

    @Test
    void everyPartStaysWithinTheInputEstimateTheLedgerReservesAgainst() {
        List<OutlineLine> outline = new ArrayList<>();
        for (int i = 1; i <= 400; i++) {
            outline.add(new OutlineLine("P" + i, "Question " + i + " " + "about the applicant's history ".repeat(8) + "[[c" + i + "]]"));
        }

        FillSpotChunker.Plan plan = FillSpotChunker.plan(input(outline, 400, List.of()), 10, 10_000, 150);

        assertTrue(plan.chunks().size() > 1);
        int asked = 0;
        for (FillSpotChunker.Chunk chunk : plan.chunks()) {
            assertTrue(UsageBudget.estimateInputTokens(chunk.request()) <= 10_000);
            assertTrue(chunk.candidates().size() <= 150);
            asked += chunk.candidates().size();
        }
        assertEquals(400, asked);
        assertEquals(List.of(), plan.leftOver());
    }

    private static SpotNamingInput input(List<OutlineLine> outline, int candidates, List<String> rowKeys) {
        List<NamingCandidate> offered = new ArrayList<>();
        for (int i = 1; i <= candidates; i++) {
            String rowKey = null;
            for (OutlineLine line : outline) {
                if (line.text().contains("[[c" + i + "]]") && rowKeys.contains(line.key())) {
                    rowKey = line.key();
                }
            }
            offered.add(new NamingCandidate("c" + i, "UNDERSCORES", "Blank " + i, FieldType.TEXT, null, false, rowKey));
        }
        return new SpotNamingInput(DocumentKind.WORD, outline, offered, rowKeys);
    }

    private static List<List<String>> ids(FillSpotChunker.Plan plan) {
        return plan.chunks().stream().map(chunk -> chunk.candidates().stream().map(NamingCandidate::id).toList()).toList();
    }
}
