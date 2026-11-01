package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.compile.PdfTemplateFill;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfFormReading;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfTemplateFillW9Test {

    private final PdfBoxFormReader reader = new PdfBoxFormReader();
    private final PdfBoxFormFiller filler = new PdfBoxFormFiller();
    private final PdfBoxFillVerifier verifier = new PdfBoxFillVerifier();

    @Test
    void aSocialSecurityNumberSplitsAcrossItsThreeLimitedFields() throws Exception {
        byte[] w9 = w9();
        PdfFormGraph graph = graphOf(w9);
        List<FieldDefinition> fields = ssnFields();
        DocumentContent content = content("socialSecurity1", "123-45-6789");

        PdfTemplateFill.Result result = new PdfTemplateFill(filler, verifier).fill(w9, graph, fields, content);

        assertTrue(result.findings().isEmpty(), () -> "findings: " + result.findings());
        try (PDDocument document = Loader.loadPDF(result.filled().bytes())) {
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            assertEquals("123", form.getField("topmostSubform[0].Page1[0].f1_11[0]").getValueAsString());
            assertEquals("45", form.getField("topmostSubform[0].Page1[0].f1_12[0]").getValueAsString());
            assertEquals("6789", form.getField("topmostSubform[0].Page1[0].f1_13[0]").getValueAsString());
        }
    }

    @Test
    void anEmployerIdSplitsAcrossItsTwoLimitedFields() throws Exception {
        byte[] w9 = w9();
        PdfFormGraph graph = graphOf(w9);
        List<FieldDefinition> fields = List.of(
                digitField("employerId1", "topmostSubform[0].Page1[0].f1_14[0]", "Employer identification number"),
                digitField("employerId2", "topmostSubform[0].Page1[0].f1_15[0]", "Employer identification number"));
        DocumentContent content = content("employerId1", "12-3456789");

        PdfTemplateFill.Result result = new PdfTemplateFill(filler, verifier).fill(w9, graph, fields, content);

        assertTrue(result.findings().isEmpty(), () -> "findings: " + result.findings());
        try (PDDocument document = Loader.loadPDF(result.filled().bytes())) {
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            assertEquals("12", form.getField("topmostSubform[0].Page1[0].f1_14[0]").getValueAsString());
            assertEquals("3456789", form.getField("topmostSubform[0].Page1[0].f1_15[0]").getValueAsString());
        }
    }

    @Test
    void aValueTheWrongLengthForItsGroupIsStillAFinding() throws Exception {
        byte[] w9 = w9();
        PdfFormGraph graph = graphOf(w9);
        List<FieldDefinition> fields = ssnFields();
        DocumentContent content = content("socialSecurity1", "123-45-678");

        PdfTemplateFill.Result result = new PdfTemplateFill(filler, verifier).fill(w9, graph, fields, content);

        assertTrue(result.findings().stream().anyMatch(f -> f.fieldId() != null),
                () -> "expected a finding for the too-short value, got: " + result.findings());
    }

    private List<FieldDefinition> ssnFields() {
        return List.of(
                digitField("socialSecurity1", "topmostSubform[0].Page1[0].f1_11[0]", "Social security number"),
                digitField("socialSecurity2", "topmostSubform[0].Page1[0].f1_12[0]", "Social security number"),
                digitField("socialSecurity3", "topmostSubform[0].Page1[0].f1_13[0]", "Social security number"));
    }

    private static FieldDefinition digitField(String fieldId, String acroName, String label) {
        return new FieldDefinition(fieldId, FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.AcroFormField(acroName), label, null, null, null);
    }

    private static DocumentContent content(String fieldId, String value) {
        return new DocumentContent(Map.of(fieldId, new FieldValue.TextValue(value)));
    }

    private byte[] w9() throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/w9.pdf"));
    }

    private PdfFormGraph graphOf(byte[] pdf) {
        PdfFormReading reading = reader.read(pdf);
        return reading instanceof PdfFormReading.Supported supported ? supported.graph() : null;
    }
}
