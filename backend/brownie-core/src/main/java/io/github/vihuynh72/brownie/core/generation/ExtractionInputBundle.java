package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;

import java.util.List;
import java.util.Objects;

/**
 * Everything a real, tenant-database-free extraction attempt needs: which
 * fields to ask about and the exact, already-cited excerpts it may draw
 * from. Deliberately flat and framework-free -- no sealed-interface field
 * binding, no source-snapshot identity, nothing a caller without tenant
 * access could not already have been handed directly -- so both the API
 * (which builds one from a real document/template/source) and the trusted
 * worker (which only ever sees the bundle's own bytes) can serialize and
 * parse it with a plain JSON mapper, the same shape either side's own
 * Jackson runtime already understands for an ordinary Java record with no
 * custom (de)serializer required.
 */
public record ExtractionInputBundle(List<BundledField> fields, List<BundledExcerpt> excerpts, List<String> composableFieldIds) {

    public ExtractionInputBundle {
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        excerpts = List.copyOf(Objects.requireNonNull(excerpts, "excerpts"));
        composableFieldIds = List.copyOf(Objects.requireNonNullElse(composableFieldIds, List.of()));
    }

    /**
     * {@code existingContent} lets the worker later detect a conflict
     * between a freshly extracted candidate and the document's own current
     * value for that field ({@code QuestionDetectionService.detect} needs
     * exactly this) without ever giving the worker its own tenant-database
     * read -- the API, which already has real document access to build the
     * rest of this bundle, freezes each scalar field's current text
     * alongside it here. {@code composableFieldIds} names which of this
     * template's own scalar TEXT fields (for example a decisions summary)
     * the worker should re-synthesize with {@code CompositionService} once
     * every question is resolved, rather than trust extraction's own more
     * literal first pass at them -- frozen here for the identical reason:
     * deciding which fields are composable needs no live document access,
     * so it belongs wherever the rest of this bundle is already built,
     * once, rather than re-derived by the worker on every attempt.
     */
    public static ExtractionInputBundle from(
            List<FieldDefinition> fieldDefinitions, List<LabeledExcerpt> excerpts, DocumentContent existingContent,
            List<String> composableFieldIds) {
        return new ExtractionInputBundle(
                fieldDefinitions.stream().map(field -> BundledField.from(field, existingContent)).toList(),
                excerpts.stream().map(BundledExcerpt::from).toList(),
                composableFieldIds);
    }

    /**
     * Just enough of a {@link FieldDefinition} for prompting and parsing --
     * {@code requiredness} genuinely affects the prompt text a model sees
     * ({@link ExtractionPromptBuilder} labels a field "required" or
     * "optional"), but a binding target never does; deliberately omitted
     * rather than carried as a value nothing downstream of this bundle
     * ever reads. {@code existingValueText} is the document's own current
     * value for this field at the moment extraction started, as plain
     * comparison text -- {@code null} when the field had no value yet, or
     * is not a scalar.
     */
    public record BundledField(
            String fieldId, FieldType type, FieldCardinality cardinality, FieldRequiredness requiredness, String existingValueText) {
        static BundledField from(FieldDefinition field, DocumentContent existingContent) {
            var existing = existingContent.fields().get(field.fieldId());
            String existingValueText = existing == null ? null : existing.asPlainText();
            return new BundledField(field.fieldId(), field.type(), field.cardinality(), field.requiredness(), existingValueText);
        }

        /**
         * Reconstructs a {@link FieldDefinition} for callers (the
         * extraction prompt builder and response parser) that only ever
         * read {@code fieldId}/{@code type}/{@code cardinality}/{@code
         * requiredness} -- this placeholder {@link
         * FieldBindingTarget.ContentControlTag} must never be trusted as
         * this field's real template binding.
         */
        public FieldDefinition toFieldDefinitionForPromptingOnly() {
            return new FieldDefinition(fieldId, type, cardinality, requiredness, new FieldBindingTarget.ContentControlTag(fieldId));
        }
    }

    public record BundledExcerpt(long spanId, String text) {
        static BundledExcerpt from(LabeledExcerpt excerpt) {
            return new BundledExcerpt(excerpt.spanId(), excerpt.text());
        }
    }
}
