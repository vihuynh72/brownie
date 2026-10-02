package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.template.DocxControlOrigin;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stored shape of {@code template_version.field_definitions}, without a
 * database: a field with none of the later, optional parts must be written
 * exactly as it always was (every stored row, and every hash of one, stays
 * what it is), and a row written before those parts existed must still
 * read. {@code JdbcTemplateRepositoryTest} proves the same against Postgres.
 */
class JdbcTemplateRepositoryJsonTest {

    /** Exactly what this repository wrote for these two fields before labels, origins and blanks existed. */
    private static final String STORED_BEFORE_LABELS = "["
            + "{\"fieldId\":\"meeting.title\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"requiredness\":\"REQUIRED\","
            + "\"bindingKind\":\"CONTENT_CONTROL_TAG\",\"contentControlTag\":\"meeting.title\",\"structuralNodePart\":null,\"structuralNodeId\":null},"
            + "{\"fieldId\":\"action.items\",\"type\":\"TEXT\",\"cardinality\":\"REPEATED\",\"requiredness\":\"OPTIONAL\","
            + "\"bindingKind\":\"STRUCTURAL_NODE\",\"contentControlTag\":null,\"structuralNodePart\":\"MAIN_DOCUMENT\",\"structuralNodeId\":\"p2\"}"
            + "]";

    private static final List<FieldDefinition> PLAIN_FIELDS = List.of(
            new FieldDefinition(
                    "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                    new FieldBindingTarget.ContentControlTag("meeting.title")),
            new FieldDefinition(
                    "action.items", FieldType.TEXT, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL,
                    new FieldBindingTarget.StructuralNode(DocumentPartKind.MAIN_DOCUMENT, "p2")));

    private final JdbcTemplateRepository repository = new JdbcTemplateRepository(null, new ObjectMapper());

    @Test
    void aFieldWithoutTheOptionalPartsIsWrittenExactlyAsBefore() {
        assertThat(repository.toJson(PLAIN_FIELDS)).isEqualTo(STORED_BEFORE_LABELS);
    }

    @Test
    void aRowWrittenBeforeTheOptionalPartsReadsWithAllFourNull() {
        List<FieldDefinition> read = repository.fromJson(STORED_BEFORE_LABELS);

        assertThat(read).isEqualTo(PLAIN_FIELDS);
        assertThat(read).allSatisfy(field -> {
            assertThat(field.label()).isNull();
            assertThat(field.origin()).isNull();
            assertThat(field.docxControl()).isNull();
            assertThat(field.blankText()).isNull();
            assertThat(field.effectiveOrigin()).isEqualTo(SpotOrigin.FORM);
        });
    }

    @Test
    void theOptionalPartsRoundTripAndOnlyTheOnesSetAreWritten() {
        FieldDefinition found = new FieldDefinition(
                "ho.va.ten", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.ContentControlTag("ho.va.ten"),
                "H\u1ecd v\u00e0 t\u00ean", SpotOrigin.FOUND_BY_BROWNIE, DocxControlOrigin.INSERTED_BY_BROWNIE, "__________");
        FieldDefinition labelledOnly = new FieldDefinition(
                "company", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.ContentControlTag("company"), "Company \"quoted\" name", null, null, null);

        String json = repository.toJson(List.of(found, labelledOnly));

        assertThat(json).contains(
                "\"label\":\"H\u1ecd v\u00e0 t\u00ean\",\"origin\":\"FOUND_BY_BROWNIE\","
                        + "\"docxControl\":\"INSERTED_BY_BROWNIE\",\"blankText\":\"__________\"}");
        assertThat(json).endsWith("\"structuralNodeId\":null,\"label\":\"Company \\\"quoted\\\" name\"}]");
        assertThat(repository.fromJson(json)).containsExactly(found, labelledOnly);
    }

    @Test
    void aPdfFieldsBindingIsStoredUnderItsOwnKindWithOnlyItsOwnParts() {
        FieldDefinition formField = new FieldDefinition(
                "phone", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.AcroFormField("applicant.phone"), "Phone", SpotOrigin.FORM, null, null);
        FieldDefinition box = new FieldDefinition(
                "full.name", FieldType.DATE, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.PageBox(2, 130.25, 88.5, 300, 14, new PdfTextStyle(PdfFontFamily.SERIF, true, 10.5), true,
                        PdfOverflowPolicy.BLOCK),
                "Full name", SpotOrigin.FOUND_BY_BROWNIE, null, null);

        String json = repository.toJson(List.of(formField, box));

        assertThat(json).isEqualTo("["
                + "{\"fieldId\":\"phone\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"requiredness\":\"OPTIONAL\","
                + "\"bindingKind\":\"ACROFORM_FIELD\",\"contentControlTag\":null,\"structuralNodePart\":null,\"structuralNodeId\":null,"
                + "\"label\":\"Phone\",\"origin\":\"FORM\",\"acroFormField\":\"applicant.phone\"},"
                + "{\"fieldId\":\"full.name\",\"type\":\"DATE\",\"cardinality\":\"SCALAR\",\"requiredness\":\"REQUIRED\","
                + "\"bindingKind\":\"PAGE_BOX\",\"contentControlTag\":null,\"structuralNodePart\":null,\"structuralNodeId\":null,"
                + "\"label\":\"Full name\",\"origin\":\"FOUND_BY_BROWNIE\",\"pageBox\":{\"page\":2,\"x\":130.25,\"y\":88.5,"
                + "\"width\":300.0,\"height\":14.0,\"font\":\"SERIF\",\"bold\":true,\"sizePt\":10.5,\"multiline\":true,"
                + "\"overflow\":\"BLOCK\"}}"
                + "]");
        assertThat(repository.fromJson(json)).containsExactly(formField, box);
    }

    /** Adding PDF bindings must not change a single byte of a Word field's stored row. */
    @Test
    void aWordFieldIsStillWrittenExactlyAsBeforeNowThatPdfBindingsExist() {
        assertThat(repository.toJson(PLAIN_FIELDS)).isEqualTo(STORED_BEFORE_LABELS);
        assertThat(repository.toJson(PLAIN_FIELDS)).doesNotContain("acroFormField", "pageBox");
    }
}
