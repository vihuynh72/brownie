package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The names a checkbox gets from the words a form writes beside it. */
class PdfCheckboxPreparationTest {

    @Test
    void aCheckboxIsNamedByTheCaptionRightOfItsBox() {
        PdfFormGraph.Word individual = new PdfFormGraph.Word("Individual", new PdfRect(100, 90, 60, 12), null, 10, 0);
        PdfFormGraph.Line line = new PdfFormGraph.Line(1, "Individual", new PdfRect(100, 90, 60, 12), List.of(individual));
        PdfFormGraph graph = graphWithCheckbox(List.of(line), new PdfRect(80, 88, 14, 14));

        List<PdfFormPreparationService.Candidate> candidates = PdfFormPreparationService.candidates(graph).candidates();

        assertEquals(1, candidates.size());
        assertEquals(FieldType.CHECKBOX, candidates.get(0).naming().rulesType());
        assertEquals("FORM_CHECKBOX", candidates.get(0).naming().kind());
        assertEquals("Individual", candidates.get(0).naming().rulesLabel());
    }

    private static PdfFormGraph graphWithCheckbox(List<PdfFormGraph.Line> lines, PdfRect widgetBox) {
        PdfFormGraph.Widget widget = new PdfFormGraph.Widget(1, widgetBox);
        PdfFormGraph.Field box = new PdfFormGraph.Field("c1_1[0]", PdfFormGraph.FieldKind.CHECKBOX, false, false, false, false, null, null, null, List.of(widget));
        PdfFormGraph.Page page = new PdfFormGraph.Page(1, new PdfFormGraph.CropBox(0, 0, 612, 792), 0, 1, true, lines, List.of(), List.of(), List.of());
        PdfFormGraph.AcroForm form = new PdfFormGraph.AcroForm(true, PdfFormGraph.XfaKind.NONE, false, List.of(box), 0);
        return new PdfFormGraph("w9", List.of(page), form, new PdfFormGraph.Risks(false, false, false));
    }
}
