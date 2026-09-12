package io.github.vihuynh72.brownie.core.rule;

/** What happens when a fixed-layout field's content exceeds its own bounded capacity. Only meaningful once a fixed-layout renderer exists to enforce it; recorded here as the rule vocabulary's own closed choice regardless. */
public enum OverflowResolution {
    BLOCK_EXPORT,
    ALLOW_REFLOW
}
