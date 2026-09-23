package io.github.vihuynh72.brownie.core.connector;

/** Why Brownie stopped being allowed to read something a person had chosen. */
public enum GrantRevocationReason {
    /** The person disconnected the account it was read through. */
    DISCONNECTED,
    /** The person told Brownie to stop reading it, while staying connected. */
    REMOVED,
    /** The provider stopped letting Brownie open it: it was deleted, unshared, or Brownie's access to it was taken away there. */
    PROVIDER_ACCESS_LOST
}
