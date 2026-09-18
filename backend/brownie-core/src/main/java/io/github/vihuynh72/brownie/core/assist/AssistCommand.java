package io.github.vihuynh72.brownie.core.assist;

import java.util.Objects;

/**
 * The bounded things a person can ask Assist to do with a document, as
 * the composer understands them. Every command names its scope up front,
 * so the workspace can show what would be affected before anything runs;
 * free-form text that matches none of them is {@link Unrecognized}, and
 * is answered with what Brownie can do rather than guessed at.
 */
public sealed interface AssistCommand {

    /** Fill the document from its attached sources: the existing grounded extraction, started from the composer. */
    record DraftFromSources() implements AssistCommand {
    }

    /** Set one field to a value the person typed; deterministic, no model call. */
    record ChangeField(String fieldId, String value) implements AssistCommand {
        public ChangeField {
            Objects.requireNonNull(fieldId, "fieldId");
            Objects.requireNonNull(value, "value");
        }
    }

    /** Ask the model for a shorter or reworded version of one text field's current value; {@code instruction} may be null. */
    record RewriteField(String fieldId, RewriteMode mode, String instruction) implements AssistCommand {
        public RewriteField {
            Objects.requireNonNull(fieldId, "fieldId");
            Objects.requireNonNull(mode, "mode");
        }
    }

    /** Explain a validation finding in plain language; {@code fieldId} narrows it to one field when the person named one. */
    record ExplainFinding(String fieldId) implements AssistCommand {
    }

    /** Text that maps to no command. */
    record Unrecognized(String text) implements AssistCommand {
        public Unrecognized {
            Objects.requireNonNull(text, "text");
        }
    }

    enum RewriteMode {
        SHORTEN,
        REWRITE
    }
}
