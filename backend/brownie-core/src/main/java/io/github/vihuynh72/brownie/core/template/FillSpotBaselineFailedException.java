package io.github.vihuynh72.brownie.core.template;

import java.util.List;

/**
 * The form, with the fill spots changed as asked, did not print its sample
 * values correctly, so the change was not made: documents would otherwise be
 * moved to a version that cannot be exported faithfully.
 */
public class FillSpotBaselineFailedException extends RuntimeException {

    private final List<String> failedFieldIds;

    public FillSpotBaselineFailedException(List<String> failedFieldIds) {
        super("The form would not print correctly with this change: " + failedFieldIds + ".");
        this.failedFieldIds = List.copyOf(failedFieldIds);
    }

    public List<String> failedFieldIds() {
        return failedFieldIds;
    }
}
