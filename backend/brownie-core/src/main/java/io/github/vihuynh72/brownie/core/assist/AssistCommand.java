package io.github.vihuynh72.brownie.core.assist;

import io.github.vihuynh72.brownie.core.template.FieldType;

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

    /**
     * Add a single-value fill spot called {@code label} (null when the
     * person did not say what it is called). Where it goes: by the words in
     * {@code quotedText} when the person quoted some, as {@code placement}
     * says; at the place selected on the page when they said "here" or
     * "this line" ({@code here}); otherwise wherever their words describe,
     * which one bounded model call works out.
     */
    record AddFillSpot(String label, String quotedText, SpotPlacement placement, boolean here, FieldType type) implements AssistCommand {
        public AddFillSpot {
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(type, "type");
        }
    }

    /** Give an existing fill spot a new name; its value stays. */
    record RenameFillSpot(String fieldId, String label) implements AssistCommand {
        public RenameFillSpot {
            Objects.requireNonNull(fieldId, "fieldId");
            Objects.requireNonNull(label, "label");
        }
    }

    /** Take a fill spot away, and its value with it. */
    record RemoveFillSpot(String fieldId) implements AssistCommand {
        public RemoveFillSpot {
            Objects.requireNonNull(fieldId, "fieldId");
        }
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

    /**
     * Where a new spot goes relative to the quoted words: right after them
     * ({@link #AFTER}), in their place ({@link #REPLACE}, also used whenever
     * the quoted words are themselves a blank such as "____" or "[Company]"),
     * in the line holding them, over its blank or at its end ({@link
     * #IN_LINE}), or over the whole line ({@link #WHOLE_LINE}, "this line is
     * the ..."). {@link #UNSPECIFIED} when the words give no place.
     */
    enum SpotPlacement {
        AFTER,
        REPLACE,
        IN_LINE,
        WHOLE_LINE,
        UNSPECIFIED
    }
}
