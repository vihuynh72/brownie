package io.github.vihuynh72.brownie.core.support;

/** What a support grant lets whoever runs the service do in one workspace. {@code CONTENT} includes {@code METADATA}. */
public enum SupportGrantScope {
    /** States, codes, timings and counts: what support can already see for the system as a whole, now for this workspace by name. */
    METADATA,
    /** The workspace's documents and files themselves. */
    CONTENT
}
