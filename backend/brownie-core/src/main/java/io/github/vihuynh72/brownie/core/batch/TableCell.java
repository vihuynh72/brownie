package io.github.vihuynh72.brownie.core.batch;

import java.time.LocalDate;
import java.util.List;

/**
 * One cell as the table rules see it: what a person sees on screen
 * ({@code display}), a real date when the cell holds one, and why it could
 * not be read when it could not. A cell with a {@link CellProblem} has a
 * blank display and no date -- never a guess at what it might have meant.
 */
public record TableCell(String display, LocalDate date, CellProblem problem) {

    public TableCell {
        display = display == null ? "" : display;
        if (problem != null && (!display.isBlank() || date != null)) {
            throw new IllegalArgumentException("A cell that could not be read has no display or date.");
        }
    }

    /** A cell with nothing in it. */
    public static TableCell blank() {
        return new TableCell("", null, null);
    }

    public static List<TableCell> blanks(int count) {
        return java.util.Collections.nCopies(count, blank());
    }

    /** Whether nothing is shown in the cell -- blank display, no date, no problem. */
    public boolean isBlank() {
        return problem == null && date == null && display.isBlank();
    }
}
