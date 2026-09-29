package io.github.vihuynh72.brownie.core.action;

/**
 * How a conversion was checked: of the {@code total} filled-in values long
 * enough to look for, how many were {@code found} in the converted text.
 */
public record ConversionCount(int total, int found) {

    public ConversionCount {
        if (total < 0 || found < 0 || found > total) {
            throw new IllegalArgumentException("A conversion finds between none and all of the values it looks for.");
        }
    }

    /** Every value looked for was found, and there was at least one to look for. */
    public boolean complete() {
        return total > 0 && found == total;
    }

    /** What reading the conversion back found, said as a verification. */
    public ActionVerification verification() {
        if (total == 0) {
            return ActionVerification.CONVERSION_UNCHECKED;
        }
        return complete() ? ActionVerification.CONVERSION_CHECKED : ActionVerification.CONVERSION_DIFFERS;
    }
}
