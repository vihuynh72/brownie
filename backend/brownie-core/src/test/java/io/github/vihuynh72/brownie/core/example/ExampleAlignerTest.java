package io.github.vihuynh72.brownie.core.example;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Hand-built graphs, the same fake-graph style {@code TemplateBindingValidatorTest} and {@code FieldBindingCandidateProposerTest} already use. */
class ExampleAlignerTest {

    private static final long TEMPLATE_ID = 1L;
    private static final String PARSER_VERSION = "test-v1";

    @Test
    void anExampleContainingEveryBoundTagIsAligned() {
        List<FieldDefinition> fields = List.of(
                field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title")),
                field("meeting.date", new FieldBindingTarget.ContentControlTag("meeting.date")));
        DocxStructuralGraph example = graphWithTags("meeting.title", "meeting.date");

        assertEquals(ExampleAlignmentStatus.ALIGNED, ExampleAligner.align(TEMPLATE_ID, fields, example));
    }

    @Test
    void anExampleMissingOneBoundTagIsMismatchedFamily() {
        List<FieldDefinition> fields = List.of(
                field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title")),
                field("meeting.date", new FieldBindingTarget.ContentControlTag("meeting.date")));
        DocxStructuralGraph example = graphWithTags("meeting.title");

        assertEquals(ExampleAlignmentStatus.MISMATCHED_FAMILY, ExampleAligner.align(TEMPLATE_ID, fields, example));
    }

    @Test
    void anExampleWithExtraUnrelatedTagsIsStillAlignedAsLongAsEveryBoundTagIsPresent() {
        List<FieldDefinition> fields = List.of(field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title")));
        DocxStructuralGraph example = graphWithTags("meeting.title", "some.other.field");

        assertEquals(ExampleAlignmentStatus.ALIGNED, ExampleAligner.align(TEMPLATE_ID, fields, example));
    }

    @Test
    void aStructuralNodeBoundFieldIsIgnoredNotTreatedAsAMismatch() {
        List<FieldDefinition> fields = List.of(
                field("meeting.title", new FieldBindingTarget.ContentControlTag("meeting.title")),
                field("meeting.location", new FieldBindingTarget.StructuralNode(DocumentPartKind.MAIN_DOCUMENT, "p9/sdt0")));
        DocxStructuralGraph example = graphWithTags("meeting.title");

        assertEquals(ExampleAlignmentStatus.ALIGNED, ExampleAligner.align(TEMPLATE_ID, fields, example));
    }

    @Test
    void noComparableFieldAtAllRefusesRatherThanGuessing() {
        List<FieldDefinition> fields =
                List.of(field("meeting.location", new FieldBindingTarget.StructuralNode(DocumentPartKind.MAIN_DOCUMENT, "p9/sdt0")));
        DocxStructuralGraph example = graphWithTags("meeting.title");

        assertThrows(NoComparableFieldBindingsException.class, () -> ExampleAligner.align(TEMPLATE_ID, fields, example));
    }

    @Test
    void anEmptyFieldListRefusesRatherThanGuessing() {
        assertThrows(
                NoComparableFieldBindingsException.class,
                () -> ExampleAligner.align(TEMPLATE_ID, List.of(), graphWithTags("meeting.title")));
    }

    private static FieldDefinition field(String fieldId, FieldBindingTarget binding) {
        return new FieldDefinition(fieldId, FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL, binding);
    }

    private static DocxStructuralGraph graphWithTags(String... tags) {
        List<StructuralNode> controls = new java.util.ArrayList<>();
        for (int index = 0; index < tags.length; index++) {
            controls.add(new StructuralNode(
                    "p" + index + "/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, tags[index], null, List.of()));
        }
        StructuralNode body = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, controls);
        return new DocxStructuralGraph(PARSER_VERSION, List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, body)));
    }
}
