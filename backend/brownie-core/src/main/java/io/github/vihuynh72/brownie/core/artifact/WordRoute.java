package io.github.vihuynh72.brownie.core.artifact;

/** How a {@link SupportedMediaType} becomes a Word document Brownie can read and fill. */
public enum WordRoute {
    /** Already a plain Word document: read as it is. */
    NATIVE,
    /**
     * The same package as a Word document with a different main part (a
     * template, or one that may carry macros): a working copy is made by
     * changing that part's type and removing what the variant adds.
     */
    NATIVE_VARIANT,
    /** Another word processor's format: converted to a Word document in the sandbox first. */
    CONVERT,
    /** Not a word-processing document at all. */
    NONE
}
