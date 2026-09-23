package io.github.vihuynh72.brownie.core.connector;

/** What happened when Brownie asked the provider to forget its access, on disconnecting. */
public enum ProviderRevocation {
    /** The provider confirmed it, or already did not know the token, which ends in the same place. */
    REVOKED,
    /**
     * The provider could not be reached. Brownie's own copy of the token is
     * wiped regardless, so Brownie can no longer use it; the access itself
     * remains listed in the person's account until they remove it there or it
     * expires.
     */
    FAILED,
    /** There was no token left to revoke, because the provider had already stopped accepting it. */
    NOT_NEEDED
}
