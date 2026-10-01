package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * One PDF template version as a page view needs it: the source PDF to draw
 * (by its artifact), each page's size, turn and lines of text, and every
 * place a value goes, measured the way every PDF form record is ({@link
 * PdfRect}: points, the page as stored, origin at the crop box's top-left,
 * Y down). A form field shown in more than one place is one entry per
 * place. Read-only, and derived from the version's own pinned form reading.
 */
public record PdfTemplateLayout(
        long templateId, long versionId, String parserVersion, long sourceArtifactId, List<Page> pages, List<Spot> spots) {

    public PdfTemplateLayout {
        pages = List.copyOf(pages);
        spots = List.copyOf(spots);
    }

    /** One page: its visible size in points before it is turned, its own turn (0, 90, 180 or 270), and its lines in order. */
    public record Page(int pageNumber, double width, double height, int rotation, boolean hasText, List<Line> lines) {

        public Page {
            lines = List.copyOf(lines);
        }
    }

    /** One line of text, as the page's reading groups it. */
    public record Line(int index, String text, PdfRect box) {
    }

    /**
     * One place a field's value goes. {@code bindingKind} is {@code
     * ACROFORM_FIELD} for one of the form's own fields, whose look the form
     * sets ({@code style} is null), or {@code PAGE_BOX} for a box Brownie
     * writes in.
     */
    public record Spot(
            String fieldId,
            String label,
            SpotOrigin origin,
            int pageNumber,
            PdfRect box,
            PdfTextStyle style,
            boolean multiline,
            PdfOverflowPolicy overflow,
            String bindingKind) {
    }

    /** The layout of {@code fields} over {@code graph}; a field bound to a form field the reading does not have has no place. */
    public static PdfTemplateLayout of(
            long templateId, long versionId, long sourceArtifactId, PdfFormGraph graph, List<FieldDefinition> fields) {
        List<Page> pages = graph.pages().stream()
                .map(page -> new Page(page.pageNumber(), page.cropBox().width(), page.cropBox().height(), page.rotation(), page.hasText(),
                        page.lines().stream().map(line -> new Line(line.index(), line.text(), line.box())).toList()))
                .toList();
        List<Spot> spots = new ArrayList<>();
        for (FieldDefinition field : fields) {
            switch (field.binding()) {
                case FieldBindingTarget.PageBox box -> spots.add(new Spot(field.fieldId(), field.displayLabel(), field.effectiveOrigin(),
                        box.page(), box.box(), box.style(), box.multiline(), box.overflow(), "PAGE_BOX"));
                case FieldBindingTarget.AcroFormField(String name) -> TemplateBindingValidator.formField(graph, name).ifPresent(formField ->
                        formField.widgets().forEach(widget -> spots.add(new Spot(field.fieldId(), field.displayLabel(),
                                field.effectiveOrigin(), widget.pageNumber(), widget.box(), null, formField.multiline(),
                                PdfOverflowPolicy.SHRINK_TO_FIT, "ACROFORM_FIELD"))));
                case FieldBindingTarget.ContentControlTag ignored -> {
                }
                case FieldBindingTarget.StructuralNode ignored -> {
                }
            }
        }
        return new PdfTemplateLayout(templateId, versionId, graph.parserVersion(), sourceArtifactId, pages, spots);
    }
}
