package io.github.vihuynh72.brownie.core.rule;

import io.github.vihuynh72.brownie.core.source.SourceKind;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;

import java.util.List;

/**
 * The initial constrained rule vocabulary: ten closed, typed shapes, never
 * an arbitrary string of code, a regular expression, or a template
 * expression. Each variant's own fields are exactly what deciding it
 * requires -- no shared "value" bag a caller could stuff anything into.
 * {@link #category()} fixes each variant's {@link RuleCategory}; it is a
 * property of the rule's own kind, not a caller-supplied, independently
 * choosable field, so a payload can never claim a category that does not
 * actually describe it.
 */
public sealed interface RulePayload {

    RuleCategory category();

    /** Every field named here must have a value when a document is generated from this template. VALIDATION: a presence check, not a formatting or behavioral choice. */
    record RequiredFields(List<String> fieldIds) implements RulePayload {
        @Override
        public RuleCategory category() {
            return RuleCategory.VALIDATION;
        }
    }

    /** VALIDATION: bounds how much text one field may hold. */
    record MaxTextLength(String fieldId, int maxCharacters) implements RulePayload {
        @Override
        public RuleCategory category() {
            return RuleCategory.VALIDATION;
        }
    }

    /** VALIDATION: bounds how many items a repeated field may hold. */
    record MaxItemCount(String fieldId, int maxItems) implements RulePayload {
        @Override
        public RuleCategory category() {
            return RuleCategory.VALIDATION;
        }
    }

    /**
     * CONTENT: the required order of the template's own sections, named by
     * caller-supplied identifiers -- this codebase's extraction graph has
     * no independent "section" concept yet to validate these identifiers
     * against, so only structural well-formedness (non-empty, no
     * duplicates) is checked; see {@link RulePayloadValidator}.
     */
    record AllowedSectionOrder(List<String> orderedSectionIds) implements RulePayload {
        @Override
        public RuleCategory category() {
            return RuleCategory.CONTENT;
        }
    }

    /** VISUAL: how a date-typed field's value is displayed. */
    record DateDisplayFormat(String fieldId, DateFormatStyle style) implements RulePayload {
        @Override
        public RuleCategory category() {
            return RuleCategory.VISUAL;
        }
    }

    /** SOURCE: which kinds of source a field's value may be drawn from. */
    record AllowedSourceKinds(String fieldId, List<SourceKind> allowedKinds) implements RulePayload {
        @Override
        public RuleCategory category() {
            return RuleCategory.SOURCE;
        }
    }

    /** BEHAVIOR: what happens when a scalar field has no value. */
    record MissingValueBehavior(String fieldId, EmptyValueResolution resolution) implements RulePayload {
        @Override
        public RuleCategory category() {
            return RuleCategory.BEHAVIOR;
        }
    }

    /** BEHAVIOR: what happens when a fixed-layout field's content overflows its own bounded capacity. */
    record AllowedOverflowBehavior(String fieldId, OverflowResolution resolution) implements RulePayload {
        @Override
        public RuleCategory category() {
            return RuleCategory.BEHAVIOR;
        }
    }

    /** BEHAVIOR: what happens when a repeated field's list of items is empty. */
    record RepeatableRegionEmptyBehavior(String fieldId, EmptyValueResolution resolution) implements RulePayload {
        @Override
        public RuleCategory category() {
            return RuleCategory.BEHAVIOR;
        }
    }

    /**
     * VISUAL: marks a region of the source document -- addressed the same
     * way a field's own binding is, but not necessarily bound to any
     * field, for example a signature block or a fixed disclaimer -- as one
     * that must never be altered.
     */
    record ProtectedRegion(FieldBindingTarget target) implements RulePayload {
        @Override
        public RuleCategory category() {
            return RuleCategory.VISUAL;
        }
    }
}
