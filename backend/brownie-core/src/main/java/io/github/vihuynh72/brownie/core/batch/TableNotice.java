package io.github.vihuynh72.brownie.core.batch;

import java.util.List;

/**
 * Something about a read table worth telling the person before they commit
 * to it: which rows are blank, repeat another row, hold a formula whose
 * answer was not stored, hold an error, or hold a numeric date that reads
 * two ways. {@code rows} are the row numbers the person sees.
 */
public record TableNotice(String code, List<Integer> rows) {

    public static final String DUPLICATE_ROWS = "DUPLICATE_ROWS";
    public static final String BLANK_ROWS = "BLANK_ROWS";
    public static final String FORMULAS_WITHOUT_RESULTS = "FORMULAS_WITHOUT_RESULTS";
    public static final String ERROR_VALUES = "ERROR_VALUES";
    public static final String AMBIGUOUS_DATES = "AMBIGUOUS_DATES";

    public TableNotice {
        rows = List.copyOf(rows);
    }
}
