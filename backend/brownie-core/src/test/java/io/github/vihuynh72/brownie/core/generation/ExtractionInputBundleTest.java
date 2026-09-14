package io.github.vihuynh72.brownie.core.generation;

import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExtractionInputBundleTest {

    @Test
    void reconstructsAFieldDefinitionCarryingEveryValueThePromptBuilderAndParserActuallyRead() {
        ExtractionInputBundle.BundledField bundled = new ExtractionInputBundle.BundledField(
                "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED, null);

        FieldDefinition field = bundled.toFieldDefinitionForPromptingOnly();

        assertEquals("meeting.title", field.fieldId());
        assertEquals(FieldType.TEXT, field.type());
        assertEquals(FieldCardinality.SCALAR, field.cardinality());
        assertEquals(FieldRequiredness.REQUIRED, field.requiredness());
    }

    @Test
    void fromBuildsOneBundledEntryPerFieldAndExcerptInOrder() {
        List<FieldDefinition> fields = List.of(new FieldDefinition(
                "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.title")));
        List<LabeledExcerpt> excerpts = List.of(new LabeledExcerpt(1L, "The meeting was called to order."));

        ExtractionInputBundle bundle = ExtractionInputBundle.from(fields, excerpts, DocumentContent.empty(), List.of());

        assertEquals(1, bundle.fields().size());
        assertEquals("meeting.title", bundle.fields().get(0).fieldId());
        assertNull(bundle.fields().get(0).existingValueText());
        assertEquals(1, bundle.excerpts().size());
        assertEquals(1L, bundle.excerpts().get(0).spanId());
        assertEquals("The meeting was called to order.", bundle.excerpts().get(0).text());
        assertEquals(List.of(), bundle.composableFieldIds());
    }

    @Test
    void fromCarriesForwardTheDocumentSOwnCurrentScalarValueAsPlainText() {
        List<FieldDefinition> fields = List.of(new FieldDefinition(
                "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.title")));
        DocumentContent existing = new DocumentContent(Map.of("meeting.title", new FieldValue.TextValue("Weekly Sync")));

        ExtractionInputBundle bundle = ExtractionInputBundle.from(fields, List.of(), existing, List.of());

        assertEquals("Weekly Sync", bundle.fields().get(0).existingValueText());
    }

    @Test
    void fromCarriesForwardTheComposableFieldIds() {
        List<FieldDefinition> fields = List.of(new FieldDefinition(
                "meeting.decisions", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.ContentControlTag("meeting.decisions")));

        ExtractionInputBundle bundle = ExtractionInputBundle.from(fields, List.of(), DocumentContent.empty(), List.of("meeting.decisions"));

        assertEquals(List.of("meeting.decisions"), bundle.composableFieldIds());
    }
}
