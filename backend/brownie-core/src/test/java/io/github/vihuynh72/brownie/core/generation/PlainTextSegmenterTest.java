package io.github.vihuynh72.brownie.core.generation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainTextSegmenterTest {

    @Test
    void splitsOnBlankLinesAndTrimsEachParagraph() {
        String text = "  Meeting called to order.  \n\nAlice will finish the budget by Friday.\n\n\nBob agreed to send the invite.";

        List<PlainTextSegmenter.Segment> segments = PlainTextSegmenter.segmentIntoParagraphs(text);

        assertEquals(3, segments.size());
        assertEquals("Meeting called to order.", segments.get(0).text());
        assertEquals("Alice will finish the budget by Friday.", segments.get(1).text());
        assertEquals("Bob agreed to send the invite.", segments.get(2).text());
    }

    @Test
    void everySegmentsOffsetsRoundTripThroughTheOriginalText() {
        String text = "First paragraph here.\n\nSecond one, a bit longer than the first.";

        List<PlainTextSegmenter.Segment> segments = PlainTextSegmenter.segmentIntoParagraphs(text);

        for (PlainTextSegmenter.Segment segment : segments) {
            String recovered = io.github.vihuynh72.brownie.core.text.CodePoints.substring(
                    text, segment.startCodePoint(), segment.endCodePointExclusive());
            assertEquals(segment.text(), recovered);
        }
    }

    @Test
    void aSingleParagraphWithNoBlankLineIsOneSegment() {
        List<PlainTextSegmenter.Segment> segments = PlainTextSegmenter.segmentIntoParagraphs("Just one line of text.");

        assertEquals(1, segments.size());
        assertEquals("Just one line of text.", segments.getFirst().text());
    }

    @Test
    void blankOrWhitespaceOnlyTextProducesNoSegments() {
        assertTrue(PlainTextSegmenter.segmentIntoParagraphs("").isEmpty());
        assertTrue(PlainTextSegmenter.segmentIntoParagraphs("   \n\n  \n").isEmpty());
    }

    @Test
    void internalSingleLineBreaksWithinAParagraphAreKept() {
        String text = "Line one\nLine two still the same paragraph\n\nA new paragraph.";

        List<PlainTextSegmenter.Segment> segments = PlainTextSegmenter.segmentIntoParagraphs(text);

        assertEquals(2, segments.size());
        assertEquals("Line one\nLine two still the same paragraph", segments.get(0).text());
    }
}
