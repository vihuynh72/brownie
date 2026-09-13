package io.github.vihuynh72.brownie.core.revision;

/**
 * Who or what produced a field's current wording -- one of five
 * independent dimensions a value can carry at once (see {@link
 * FieldState}), not mutually exclusive with any of the others. Only
 * {@link #USER_AUTHORED} is reachable through this codebase's two
 * existing mutation paths today ({@code RevisionService#createDocument}
 * and {@code #applyUserEdits}, both explicitly human-driven); the other
 * three are real, modeled values with no producing call path yet, the
 * same honest gap this plan's own evidence-support and review dimensions
 * below also have until a composing or validating caller exists.
 */
public enum Authorship {
    IMPORTED,
    AI_COMPOSED,
    USER_AUTHORED,
    MIXED
}
