package io.github.vihuynh72.brownie.core.prepare;

import java.util.Objects;
import java.util.Set;

/**
 * One line a person is told about how their file was made ready to fill:
 * {@code code} says what happened, {@code count} how many times, and
 * {@code detail} carries the one fact some codes need (the format a file
 * was converted from, the feature kept as it is), otherwise null. The
 * browser holds the sentences; the codes are the shared vocabulary.
 */
public record PreparationNotice(String code, int count, String detail) {

    /** Tracked changes were accepted and comments were left out. */
    public static final String TRACKED_CHANGES_AND_COMMENTS = "TRACKED_CHANGES_AND_COMMENTS";
    public static final String MACROS_REMOVED = "MACROS_REMOVED";
    public static final String SIGNATURE_REMOVED = "SIGNATURE_REMOVED";
    /** Links to a template, picture, object, frame or document outside the file were removed. */
    public static final String LINKED_CONTENT_REMOVED = "LINKED_CONTENT_REMOVED";
    /** Fields that fetch or run something were replaced by the text they showed. */
    public static final String FIELDS_FROZEN = "FIELDS_FROZEN";
    public static final String EMBEDDED_FILES_TO_PICTURES = "EMBEDDED_FILES_TO_PICTURES";
    public static final String EDITING_RESTRICTION_REMOVED = "EDITING_RESTRICTION_REMOVED";
    /** {@code detail} is the format converted from. */
    public static final String CONVERTED = "CONVERTED";
    /** {@code detail} is the feature kept as it is. */
    public static final String KEPT_AS_IS = "KEPT_AS_IS";
    public static final String SPOTS_FOUND = "SPOTS_FOUND";
    public static final String NO_SPOTS_FOUND = "NO_SPOTS_FOUND";
    public static final String SIGNATURE_LINES_LEFT = "SIGNATURE_LINES_LEFT";
    public static final String CHECKBOXES_LEFT = "CHECKBOXES_LEFT";
    public static final String SOME_NAMED_BY_RULES = "SOME_NAMED_BY_RULES";
    public static final String SPOTS_SKIPPED = "SPOTS_SKIPPED";
    public static final String BLANKS_OUTSIDE_BODY = "BLANKS_OUTSIDE_BODY";
    public static final String TABLE_ROWS_GROW = "TABLE_ROWS_GROW";
    public static final String SCANNED_PDF = "SCANNED_PDF";
    public static final String PDF_FIELDS_LEFT = "PDF_FIELDS_LEFT";
    /**
     * The naming step decided that some places the rules found are not
     * places to fill in (an example answer, a box for the office), so they
     * were left out; {@code count} says how many. A place to sign is not
     * counted here: {@link #SIGNATURE_LINES_LEFT} says that.
     */
    public static final String PLACES_LEFT_OUT = "PLACES_LEFT_OUT";

    /** Every code there is, which is all a stored or sent notice may carry. */
    public static final Set<String> CODES = Set.of(
            TRACKED_CHANGES_AND_COMMENTS, MACROS_REMOVED, SIGNATURE_REMOVED, LINKED_CONTENT_REMOVED, FIELDS_FROZEN,
            EMBEDDED_FILES_TO_PICTURES, EDITING_RESTRICTION_REMOVED, CONVERTED, KEPT_AS_IS, SPOTS_FOUND, NO_SPOTS_FOUND,
            SIGNATURE_LINES_LEFT, CHECKBOXES_LEFT, SOME_NAMED_BY_RULES, SPOTS_SKIPPED, BLANKS_OUTSIDE_BODY, TABLE_ROWS_GROW,
            SCANNED_PDF, PDF_FIELDS_LEFT, PLACES_LEFT_OUT);

    public PreparationNotice {
        Objects.requireNonNull(code, "code");
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative.");
        }
    }
}
