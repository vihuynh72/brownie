package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A PDF template's page view: its pages and lines as read, and one place per box and per place a form field is shown. */
class PdfTemplateLayoutTest {

    @Test
    void pagesKeepTheirSizeTurnAndLinesAndEveryPlaceIsListedInFieldOrder() {
        PdfFormGraph.Line line = new PdfFormGraph.Line(0, "Full name:", new PdfRect(72, 90, 50, 11), List.of());
        PdfFormGraph.Page page = new PdfFormGraph.Page(
                1, new PdfFormGraph.CropBox(0, 0, 612, 792), 90, 1, true, List.of(line), List.of(), List.of(), List.of());
        PdfFormGraph.Field phone = new PdfFormGraph.Field("applicant.phone", PdfFormGraph.FieldKind.TEXT, false, false, true, false,
                null, null, null, List.of(new PdfFormGraph.Widget(1, new PdfRect(150, 82, 300, 20)),
                        new PdfFormGraph.Widget(1, new PdfRect(150, 500, 300, 20))));
        PdfFormGraph graph = new PdfFormGraph("form-v1", List.of(page),
                new PdfFormGraph.AcroForm(true, PdfFormGraph.XfaKind.NONE, false, List.of(phone), 0), new PdfFormGraph.Risks(false, false, false));
        FieldBindingTarget.PageBox box = new FieldBindingTarget.PageBox(1, 130, 88, 200, 14, PdfTextStyle.DEFAULT, false, PdfOverflowPolicy.BLOCK);
        List<FieldDefinition> fields = List.of(
                new FieldDefinition("note", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL, box, "Note",
                        SpotOrigin.ADDED_BY_PERSON, null, null),
                new FieldDefinition("phone", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                        new FieldBindingTarget.AcroFormField("applicant.phone")),
                new FieldDefinition("gone", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                        new FieldBindingTarget.AcroFormField("not.in.form")));

        PdfTemplateLayout layout = PdfTemplateLayout.of(3, 4, 99, graph, fields);

        assertEquals("form-v1", layout.parserVersion());
        assertEquals(99, layout.sourceArtifactId());
        assertEquals(List.of(new PdfTemplateLayout.Page(1, 612, 792, 90, true,
                List.of(new PdfTemplateLayout.Line(0, "Full name:", new PdfRect(72, 90, 50, 11))))), layout.pages());
        assertEquals(List.of(
                new PdfTemplateLayout.Spot("note", "Note", SpotOrigin.ADDED_BY_PERSON, 1, box.box(), PdfTextStyle.DEFAULT, false,
                        PdfOverflowPolicy.BLOCK, "PAGE_BOX"),
                new PdfTemplateLayout.Spot("phone", "Phone", SpotOrigin.FORM, 1, new PdfRect(150, 82, 300, 20), null, true,
                        PdfOverflowPolicy.SHRINK_TO_FIT, "ACROFORM_FIELD"),
                new PdfTemplateLayout.Spot("phone", "Phone", SpotOrigin.FORM, 1, new PdfRect(150, 500, 300, 20), null, true,
                        PdfOverflowPolicy.SHRINK_TO_FIT, "ACROFORM_FIELD")), layout.spots());
        assertNull(layout.spots().get(1).style());
    }
}
