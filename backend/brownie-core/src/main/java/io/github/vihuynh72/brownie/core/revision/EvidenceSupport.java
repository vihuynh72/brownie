package io.github.vihuynh72.brownie.core.revision;

/**
 * Whether acceptable source material supports a field's current value --
 * a semantic classification, distinct from {@link DocumentRevision#evidence()}
 * itself (which names WHICH source spans were cited, not how well they
 * support the value). {@link #DIRECT} is derived today from whether any
 * span was cited at all for a field this revision sets; the finer
 * distinctions ({@link #TRANSFORMED}, {@link #AMBIGUOUS}, {@link
 * #UNSUPPORTED}) require real semantic evaluation this codebase does not
 * perform yet and are not produced by any path today -- citing a real
 * span only proves a reference exists, not that its content actually
 * supports the claim, a distinct and harder question this dimension
 * leaves open for later, dedicated evaluation work.
 */
public enum EvidenceSupport {
    DIRECT,
    TRANSFORMED,
    AMBIGUOUS,
    UNSUPPORTED,
    MISSING
}
