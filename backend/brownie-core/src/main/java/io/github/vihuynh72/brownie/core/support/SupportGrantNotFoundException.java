package io.github.vihuynh72.brownie.core.support;

/** No open support grant with this id is visible to the caller in this workspace. */
public class SupportGrantNotFoundException extends RuntimeException {

    public SupportGrantNotFoundException(long grantId) {
        super("No open support grant " + grantId + " exists in this workspace.");
    }
}
