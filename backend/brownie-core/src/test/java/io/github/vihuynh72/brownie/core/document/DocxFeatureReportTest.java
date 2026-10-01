package io.github.vihuynh72.brownie.core.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxFeatureReportTest {

    private static final DocxFeatureFinding SHAPE =
            new DocxFeatureFinding(UnsupportedDocxFeature.FLOATING_SHAPE, "word/document.xml, p0", "a text box");
    private static final DocxFeatureFinding CHANGE =
            new DocxFeatureFinding(UnsupportedDocxFeature.TRACKED_CHANGES, "word/document.xml, p1", "A tracked insertion.");

    @Test
    void whatTheFileKeepsAsItIsDoesNotStopTheRead() {
        DocxFeatureReport kept = new DocxFeatureReport(List.of(SHAPE));
        assertTrue(kept.isSupported());
        assertEquals(List.of(SHAPE), kept.keptAsIs());
        assertTrue(kept.refused().isEmpty());

        DocxFeatureReport both = new DocxFeatureReport(List.of(SHAPE, CHANGE));
        assertFalse(both.isSupported());
        assertEquals(List.of(CHANGE), both.refused());
        assertEquals(List.of(SHAPE), both.keptAsIs());
        assertTrue(DocxFeatureReport.empty().isSupported());
    }

    @Test
    void everyKindIsEitherRefusedOrKeptAsIs() {
        for (UnsupportedDocxFeature feature : List.of(
                UnsupportedDocxFeature.TRACKED_CHANGES, UnsupportedDocxFeature.UNRESOLVED_COMMENT,
                UnsupportedDocxFeature.LINKED_EXTERNAL_IMAGE, UnsupportedDocxFeature.UNSUPPORTED_FIELD,
                UnsupportedDocxFeature.PACKAGE_SIGNATURE, UnsupportedDocxFeature.UNSAFE_EMBEDDED_OBJECT)) {
            assertFalse(feature.keptAsIs(), feature.name());
        }
        for (UnsupportedDocxFeature feature : List.of(
                UnsupportedDocxFeature.FLOATING_SHAPE, UnsupportedDocxFeature.NESTED_TABLE,
                UnsupportedDocxFeature.EMBEDDED_OBJECT, UnsupportedDocxFeature.DYNAMIC_FIELD,
                UnsupportedDocxFeature.TRACKED_FORMATTING_CHANGE)) {
            assertTrue(feature.keptAsIs(), feature.name());
        }
        assertEquals(11, UnsupportedDocxFeature.values().length);
    }

    @Test
    void aFieldFindingCarriesItsInstructionAndItsKindCanBeReadBack() {
        DocxFeatureFinding field = DocxFeatureFinding.field(UnsupportedDocxFeature.DYNAMIC_FIELD, "word/footer1.xml, p0", "  PAGE \\* MERGEFORMAT ");
        assertEquals("Field instruction: PAGE \\* MERGEFORMAT", field.detail());
        assertEquals("PAGE", field.fieldKeyword());
        assertNull(SHAPE.fieldKeyword());

        DocxFeatureFinding longField = DocxFeatureFinding.field(UnsupportedDocxFeature.UNSUPPORTED_FIELD, "p0", "INCLUDETEXT " + "x".repeat(500));
        assertEquals("Field instruction: ".length() + 200 + 3, longField.detail().length());
        assertEquals("INCLUDETEXT", longField.fieldKeyword());
    }
}
