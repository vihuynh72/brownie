package io.github.vihuynh72.brownie.core.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a place in a table is named: by its column, with its row only to tell rows apart. */
class TableCellLabelsTest {

    @Test
    void aPlaceIsNamedByItsColumnWithItsRowAddedOnlyWhenSeveralRowsShareTheColumn() {
        assertEquals("Year", TableCellLabels.label("Year", "Painting", 1, false));
        assertEquals("Year (Painting)", TableCellLabels.label("Year", "Painting", 1, true));
        assertEquals("Course (row 2)", TableCellLabels.label("Course", null, 2, true));
    }

    @Test
    void aLongRowIsCutSoTheLabelStaysALabel() {
        String label = TableCellLabels.label("Year", "A".repeat(80), 1, true);

        assertEquals(FieldIds.MAX_LABEL_LENGTH, label.length());
        assertEquals("Year (" + "A".repeat(FieldIds.MAX_LABEL_LENGTH - 7) + ")", label);
        assertEquals("H".repeat(58), TableCellLabels.label("H".repeat(58), "Painting", 1, true), "no room for the row");
    }

    @Test
    void aFieldIdIsMadeFromTheLabelWithoutTheWordsInBracketsThatEndIt() {
        assertEquals("Year", TableCellLabels.idWords("Year (Painting)"));
        assertEquals("Quantity", TableCellLabels.idWords("Quantity (row 2)"));
        assertEquals("Full name", TableCellLabels.idWords("Full name"));
        assertEquals("(Optional)", TableCellLabels.idWords("(Optional)"), "nothing would be left");
        assertEquals("Date (DD/MM) of birth", TableCellLabels.idWords("Date (DD/MM) of birth"));
    }

    @Test
    void theNamingStepIsToldTheColumnAndTheRow() {
        assertEquals("column: Year; row: Painting", TableCellLabels.context("Year", "Painting", 1));
        assertEquals("column: Course; row 2", TableCellLabels.context("Course", null, 2));
    }
}
