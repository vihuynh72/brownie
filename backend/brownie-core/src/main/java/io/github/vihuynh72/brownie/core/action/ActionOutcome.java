package io.github.vihuynh72.brownie.core.action;

/** What is known about a change after sending it or asking about it: the decision an attempt ends with. */
public sealed interface ActionOutcome {

    /**
     * It happened, and reading it back found what was approved (or that the
     * person has since deleted it). {@code conversionCount} only for a
     * conversion: how many filled-in values were found in the result.
     */
    record Done(ActionVerification verification, String externalId, String link, ConversionCount conversionCount) implements ActionOutcome {
    }

    /** Something was made, and it is not what was approved. */
    record Mismatched(String externalId, String link) implements ActionOutcome {
    }

    /** It certainly did not happen. */
    record NotApplied() implements ActionOutcome {
    }

    /** It certainly did not happen, and will not if tried again. */
    record Refused(ActionFailure failure) implements ActionOutcome {
    }

    /** Whether it happened cannot be told now; {@code externalId} when an answer named what may have been made. */
    record StillUnknown(String externalId) implements ActionOutcome {
    }
}
