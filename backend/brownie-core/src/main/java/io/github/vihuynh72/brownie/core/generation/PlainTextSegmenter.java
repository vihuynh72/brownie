package io.github.vihuynh72.brownie.core.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a plain-text source's own normalized text into paragraph-sized
 * excerpts an extraction run can address and cite individually -- the
 * addressable unit this format's {@code EvidenceLocator.PlainText} needs,
 * the same role a DOCX paragraph node or a PDF text line plays for their
 * own formats (neither of which this class handles; see {@link
 * ExtractionService}'s own stated boundary). A blank line (one or more
 * blank lines in a row) separates paragraphs; leading/trailing whitespace
 * within one paragraph is trimmed from its offsets, not just its text, so
 * a citation never quotes whitespace that was never part of the claim.
 */
public final class PlainTextSegmenter {

    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("\\n[ \\t]*\\n[\\s]*");

    private PlainTextSegmenter() {
    }

    public record Segment(int startCodePoint, int endCodePointExclusive, String text) {
    }

    public static List<Segment> segmentIntoParagraphs(String normalizedText) {
        List<Segment> segments = new ArrayList<>();
        Matcher separator = PARAGRAPH_SEPARATOR.matcher(normalizedText);
        int chunkStart = 0;
        int length = normalizedText.length();
        while (chunkStart <= length) {
            int chunkEnd = separator.find(chunkStart) ? separator.start() : length;
            addTrimmedSegment(segments, normalizedText, chunkStart, chunkEnd);
            if (chunkEnd == length) {
                break;
            }
            chunkStart = separator.end();
        }
        return List.copyOf(segments);
    }

    private static void addTrimmedSegment(List<Segment> segments, String text, int rawStart, int rawEnd) {
        int start = rawStart;
        int end = rawEnd;
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (start >= end) {
            return;
        }
        int startCodePoint = text.codePointCount(0, start);
        int endCodePoint = startCodePoint + text.codePointCount(start, end);
        segments.add(new Segment(startCodePoint, endCodePoint, text.substring(start, end)));
    }
}
