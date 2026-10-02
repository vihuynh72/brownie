package io.github.vihuynh72.brownie.core.artifact;

/**
 * Whose package is being inspected, which decides one rule and no other.
 * Every bound on size, entries, depth, duplicate parts and document types
 * holds for both.
 */
public enum PackagePolicy {
    /**
     * A file as a person gave it, which may be downloaded again exactly as
     * it is: a relationship that asks its reader to fetch something from a
     * network is refused.
     */
    UPLOAD,
    /**
     * A Word document the sandboxed converter just wrote from an accepted
     * upload. It may carry the network links the original had (a converted
     * picture link, say); it is never stored or served as it is, and the
     * working-copy cleaner removes those links before anything else reads it.
     */
    CONVERTER_OUTPUT
}
