package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.template.PdfSpotCandidate;
import io.github.vihuynh72.brownie.core.template.PdfSpotCandidateDetector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The candidate detector on what the form reader really reads from real PDFs, not on pages built by hand. */
class PdfSpotDetectionTest {

    @Test
    void aFlatFormsBlanksAreFoundAndItsLookAlikesAreNot() throws IOException {
        List<PdfSpotCandidate> candidates = PdfSpotCandidateDetector.detect(PdfBoxFormFillerTest.graphOf(PdfFormFixtures.flatForm()));

        assertEquals(List.of(
                        PdfSpotCandidate.Kind.UNDERSCORES, PdfSpotCandidate.Kind.DOT_LEADER, PdfSpotCandidate.Kind.LABEL_SPACE,
                        PdfSpotCandidate.Kind.RULE, PdfSpotCandidate.Kind.EMPTY_BOX, PdfSpotCandidate.Kind.EMPTY_BOX,
                        PdfSpotCandidate.Kind.EMPTY_BOX, PdfSpotCandidate.Kind.EMPTY_BOX, PdfSpotCandidate.Kind.EMPTY_BOX,
                        PdfSpotCandidate.Kind.RULE),
                candidates.stream().map(PdfSpotCandidate::kind).toList(), candidates.toString());
        assertEquals("Full name", candidates.get(0).labelGuess());
        assertEquals("Date of birth", candidates.get(1).labelGuess());
        assertEquals("Email", candidates.get(2).labelGuess());
        assertEquals("Address", candidates.get(3).labelGuess());
        assertEquals(PdfFontFamily.SERIF, candidates.get(3).style().family());
        assertEquals(12, candidates.get(3).style().sizePt());
        PdfSpotCandidate signature = candidates.get(9);
        assertEquals("Signature", signature.labelGuess());
        assertTrue(signature.signatureLike());
        assertEquals(1, candidates.stream().filter(PdfSpotCandidate::signatureLike).count());
    }

    @Test
    void aFlatFormsTableCellsAreNamedByTheirColumnsHeaderNeverByTheCourseBesideThem() throws IOException {
        List<PdfSpotCandidate> cells = PdfSpotCandidateDetector.detect(PdfBoxFormFillerTest.graphOf(PdfFormFixtures.flatForm()))
                .stream().filter(candidate -> candidate.kind() == PdfSpotCandidate.Kind.EMPTY_BOX).toList();

        assertEquals(List.of("Year (Painting)", "Course (row 2)", "Year (row 2)", "Course (row 3)", "Year (row 3)"),
                cells.stream().map(PdfSpotCandidate::labelGuess).toList());
        assertEquals(List.of("column: Year; row: Painting", "column: Course; row 2", "column: Year; row 2", "column: Course; row 3",
                "column: Year; row 3"), cells.stream().map(PdfSpotCandidate::contextText).toList());
        assertTrue(cells.stream().allMatch(cell -> "P1G1".equals(cell.gridKey())), "one grid, filled in whole or not at all");
        assertEquals(List.of("Painting"), cells.getFirst().tableValues());
    }

    @Test
    void aFillableFormsLabelsBelongToItsFieldsAndAScanHasNothingToGoOn() throws IOException {
        assertEquals(List.of(), PdfSpotCandidateDetector.detect(PdfBoxFormFillerTest.graphOf(PdfFormFixtures.fillableForm())));
        assertEquals(List.of(), PdfSpotCandidateDetector.detect(PdfBoxFormFillerTest.graphOf(PdfFormFixtures.scannedPage(false))));
    }

    @Test
    void aBlankOnATurnedPageIsFoundWhereItReads() throws IOException {
        for (int rotation : new int[] {0, 90, 180, 270}) {
            List<PdfSpotCandidate> candidates =
                    PdfSpotCandidateDetector.detect(PdfBoxFormFillerTest.graphOf(PdfFormFixtures.flatFormOnTurnedPage(rotation)));

            assertEquals(1, candidates.size(), "rotation " + rotation + ": " + candidates);
            assertEquals(PdfSpotCandidate.Kind.RULE, candidates.get(0).kind(), "rotation " + rotation);
            assertEquals("Full name", candidates.get(0).labelGuess(), "rotation " + rotation);
        }
    }
}
