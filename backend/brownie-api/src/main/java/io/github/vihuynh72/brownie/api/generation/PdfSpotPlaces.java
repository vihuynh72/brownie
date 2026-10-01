package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.core.assist.AssistCommand;
import io.github.vihuynh72.brownie.core.template.PdfTemplateLayout;
import io.github.vihuynh72.brownie.core.text.CodePoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The lines of a PDF form's pages a new box can be placed by, and how words
 * a person quoted, or a line the model chose, become a place on one of
 * them. Pure: it reads the page view's lines and says where on a line the
 * box goes; the box itself is suggested from the form reading ({@code
 * TemplateLayoutService#suggestBox} and {@code #suggestBoxAfterWords}), so
 * a place named in chat gets the same box a person pointing there would.
 *
 * <p>A line is a line of text on a page that is not blank; its id is
 * {@code P<page>L<index>} ("P2L14"), which the model is shown and must
 * answer with. Quoted words are found as for a Word form ({@link
 * SpotPlaces#occurrences}): ignoring case and runs of spaces.
 */
final class PdfSpotPlaces {

    /** One line of text on a page, with the id the model is shown. */
    record Line(String id, int pageNumber, int lineIndex, String text) {
    }

    /**
     * Where on a line a new box goes: just after the first {@code after}
     * code points of its text (the quoted words, or the words before the
     * ones a box goes in place of), or, when {@code after} is null, beside
     * the line as a whole. {@code description} says it in words ("after
     * \"Company:\"").
     */
    record Target(Line line, Integer after, String description) {
    }

    private PdfSpotPlaces() {
    }

    /** Every line with text, page by page and top to bottom. */
    static List<Line> linesOf(PdfTemplateLayout layout) {
        List<Line> lines = new ArrayList<>();
        for (PdfTemplateLayout.Page page : layout.pages()) {
            for (PdfTemplateLayout.Line line : page.lines()) {
                if (!line.text().isBlank()) {
                    lines.add(new Line("P" + page.pageNumber() + "L" + line.index(), page.pageNumber(), line.index(), line.text()));
                }
            }
        }
        return lines;
    }

    /** The lines as the placement call is shown them, each saying which page it is on. */
    static List<SpotPlaces.Line> forModel(List<Line> lines) {
        return lines.stream().map(line -> new SpotPlaces.Line(line.id(), null, line.text(), null, "page " + line.pageNumber())).toList();
    }

    /**
     * Every place the quoted words make, one per time they appear on a line:
     * after them, or, for words a box goes in place of (a blank), just after
     * what comes before them, so the box goes over the blank.
     */
    static List<Target> targetsOfQuoted(List<Line> lines, String quoted, AssistCommand.SpotPlacement placement) {
        List<Target> targets = new ArrayList<>();
        for (Line line : lines) {
            for (int[] found : SpotPlaces.occurrences(line.text(), quoted)) {
                targets.add(targetFor(line, found[0], found[1], placement));
            }
        }
        return targets;
    }

    private static Target targetFor(Line line, int start, int end, AssistCommand.SpotPlacement placement) {
        String words = CodePoints.substring(line.text(), start, end);
        return switch (placement) {
            case REPLACE -> new Target(line, start, "in place of \"" + words + "\"");
            case WHOLE_LINE, IN_LINE -> new Target(line, null, "beside the line");
            case AFTER, UNSPECIFIED -> new Target(line, end, "after \"" + words + "\"");
        };
    }

    /** The line a page anchor chose, if it is one of the lines. */
    static Optional<Line> lineAt(List<Line> lines, int pageNumber, int lineIndex) {
        return lines.stream().filter(line -> line.pageNumber() == pageNumber && line.lineIndex() == lineIndex).findFirst();
    }

    /**
     * The model's choice as a place, only when it holds together: a line it
     * was offered, and for a choice by words, words that are really on that
     * line. Anything else is no place at all.
     */
    static Optional<Target> fromModel(List<Line> offered, SpotPlacementPrompt.Reply reply) {
        Optional<Line> chosen = offered.stream().filter(line -> line.id().equals(reply.lineId())).findFirst();
        if (chosen.isEmpty() || reply.placement() == null) {
            return Optional.empty();
        }
        Line line = chosen.get();
        switch (reply.placement()) {
            case "AFTER_TEXT", "REPLACE_TEXT" -> {
                String words = reply.text();
                if (words == null || words.isBlank()) {
                    return Optional.empty();
                }
                int found = line.text().indexOf(words);
                if (found < 0) {
                    return Optional.empty();
                }
                int start = line.text().codePointCount(0, found);
                int end = start + CodePoints.length(words);
                return Optional.of(targetFor(line, start, end, reply.placement().equals("AFTER_TEXT")
                        ? AssistCommand.SpotPlacement.AFTER : AssistCommand.SpotPlacement.REPLACE));
            }
            case "END_OF_LINE", "WHOLE_LINE" -> {
                return Optional.of(new Target(line, null, "beside the line"));
            }
            default -> {
                return Optional.empty();
            }
        }
    }
}
