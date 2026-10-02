package io.github.vihuynh72.brownie.api.document.pdf;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The layout rules, with every character half the font size wide so the arithmetic can be done by hand. */
class PdfTextLayoutTest {

    private static final PdfTextLayout.Measure HALF_EM = (text, size) -> text.codePointCount(0, text.length()) * size / 2;

    @Test
    void textThatFitsAtItsSizeKeepsItAndOneLineIsCentredVertically() {
        PdfTextLayout.Result result = layOut(request("abcd", 100, 20, false, 0, 10, true));

        assertTrue(result.fits());
        assertEquals(10, result.sizePt());
        assertFalse(result.shrunk(10));
        PdfTextLayout.Line line = result.lines().get(0);
        assertEquals(1, line.x());
        // Ascent 0.8 and descent -0.2 make a text height of 10 at 10 points: centred in 20, the baseline is 5 + 2.
        assertEquals(7, line.baseline(), 1e-9);
    }

    @Test
    void textShrinksHalfAPointAtATimeUntilItFits() {
        // Twenty characters at s/2 each need 10s points across 98 available: 9.8 points, so 9.5 after five steps.
        PdfTextLayout.Result result = layOut(request("a".repeat(20), 100, 20, false, 0, 12, true));

        assertTrue(result.fits());
        assertEquals(9.5, result.sizePt());
        assertTrue(result.shrunk(12));
    }

    @Test
    void textNeverShrinksBelowSixPointsAndThenDoesNotFit() {
        PdfTextLayout.Result result = layOut(request("a".repeat(40), 100, 20, false, 0, 12, true));

        assertFalse(result.fits());
        assertEquals(6, result.sizePt());
    }

    @Test
    void noStartingSizeKeepsTheLayoutTrying() {
        PdfTextLayout.Result huge = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> layOut(request("abcd", 100, 20, false, 0, 1e300, true)));
        PdfTextLayout.Result large = layOut(request("abcd", 100, 20, false, 0, 72, true));

        assertFalse(huge.fits(), "a size no caller uses gives up after " + PdfTextLayout.MOST_SIZES_TRIED + " sizes");
        assertTrue(large.fits(), "the largest size a caller starts at still shrinks all the way it needs to");
        assertEquals(18, large.sizePt());
    }

    @Test
    void textThatMayNotShrinkOverflowsAtOnce() {
        PdfTextLayout.Result result = layOut(request("a".repeat(20), 100, 20, false, 0, 12, false));

        assertFalse(result.fits());
        assertEquals(12, result.sizePt());
    }

    @Test
    void wrappedTextBreaksAtSpacesStartsAtTheTopAndHonoursLineBreaks() {
        PdfTextLayout.Result result = layOut(request("aaaa bbbb cccc\ndd", 52, 60, true, 0, 10, false));

        assertTrue(result.fits());
        assertEquals(List.of("aaaa bbbb", "cccc", "dd"), result.lines().stream().map(PdfTextLayout.Line::text).toList());
        // The first baseline sits one ascent below the top padding; each next line one line height lower.
        assertEquals(60 - 1 - 8, result.lines().get(0).baseline(), 1e-9);
        assertEquals(10 * 1.1, result.lines().get(0).baseline() - result.lines().get(1).baseline(), 1e-9);
    }

    @Test
    void aWordWiderThanTheLineIsBrokenBetweenItsLettersNotCut() {
        List<String> lines = PdfTextLayout.wrapped("abcdefghij", 20, 10, HALF_EM);

        assertEquals(List.of("abcd", "efgh", "ij"), lines);
    }

    @Test
    void wrappedTextTallerThanTheBoxDoesNotFit() {
        PdfTextLayout.Result result = layOut(request("aaaa bbbb cccc dddd", 30, 20, true, 0, 10, false));

        assertFalse(result.fits());
    }

    @Test
    void aCombPutsEachCharacterInTheMiddleOfItsCell() {
        PdfTextLayout.Result result = layOut(new PdfTextLayout.Request(
                "123", 50, 20, 1, false, false, 5, 0, 10, false, 0.8, -0.2, 0.1));

        assertTrue(result.fits());
        assertEquals(List.of(2.5, 12.5, 22.5), result.combCharacters().stream().map(PdfTextLayout.Placed::x).toList());
    }

    @Test
    void centredAndRightAlignedTextFollowTheFieldsQuadding() {
        PdfTextLayout.Result centred = layOut(new PdfTextLayout.Request("ab", 100, 20, 1, false, false, 0, 1, 10, false, 0.8, -0.2, 0.1));
        PdfTextLayout.Result right = layOut(new PdfTextLayout.Request("ab", 100, 20, 1, false, false, 0, 2, 10, false, 0.8, -0.2, 0.1));

        assertEquals(45, centred.lines().get(0).x(), 1e-9);
        assertEquals(89, right.lines().get(0).x(), 1e-9);
    }

    private static PdfTextLayout.Request request(String text, double width, double height, boolean wrap, int comb, double size,
            boolean mayShrink) {
        return new PdfTextLayout.Request(text, width, height, 1, wrap, true, comb, 0, size, mayShrink, 0.8, -0.2, 0.1);
    }

    private static PdfTextLayout.Result layOut(PdfTextLayout.Request request) {
        return PdfTextLayout.layOut(request, HALF_EM);
    }
}
