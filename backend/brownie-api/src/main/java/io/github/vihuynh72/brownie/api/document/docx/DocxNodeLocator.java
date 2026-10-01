package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a node id back into the place in a Word file it names, by walking
 * the file with {@link DocxNodeWalker} exactly as the structural graph was
 * built: the paragraph {@code tbl1/row2/cell0/p0}, the control
 * {@code p3/sdt1}, every control tagged {@code full.name}. It also reads a
 * paragraph's anchor text the way the graph does -- the text of its own
 * runs and its link runs, a picture's run adding nothing and a control's
 * text not counted -- so an offset chosen on the graph lands on the same
 * character here.
 *
 * <p>A locator describes the file as it was when it was made; after an
 * edit, make a new one.
 */
public final class DocxNodeLocator {

    /** One paragraph the walk gave an id, and the part it is in. */
    public record LocatedParagraph(DocumentPartKind part, String partName, DocxNodeWalker.Paragraph paragraph) {
    }

    /** One inline control the walk gave an id, and the paragraph and part it is in. */
    public record LocatedControl(DocumentPartKind part, String partName, DocxNodeWalker.Paragraph paragraph,
                                 DocxNodeWalker.Control control) {
    }

    private final List<DocxNodeWalker.Part> parts;
    private final Map<DocumentPartKind, Map<String, LocatedParagraph>> paragraphs = new HashMap<>();
    private final Map<DocumentPartKind, Map<String, LocatedControl>> controls = new HashMap<>();
    private final List<LocatedControl> allControls = new ArrayList<>();

    public DocxNodeLocator(XWPFDocument document) {
        this.parts = DocxNodeWalker.walk(document);
        for (DocxNodeWalker.Part part : parts) {
            for (DocxNodeWalker.Block block : part.blocks()) {
                index(part, block);
            }
        }
    }

    /** The parts in the order the walk lists them: the main document, then headers, then footers. */
    public List<DocxNodeWalker.Part> parts() {
        return parts;
    }

    /**
     * The paragraph with {@code nodeId} in the first part of kind {@code
     * part} that has one. Node ids are unique only within a part; the main
     * document is always one part, so for it the answer is exact.
     */
    public Optional<LocatedParagraph> paragraph(DocumentPartKind part, String nodeId) {
        return Optional.ofNullable(paragraphs.getOrDefault(part, Map.of()).get(nodeId));
    }

    /** The inline control with {@code nodeId}, in the first part of kind {@code part} that has one. */
    public Optional<LocatedControl> control(DocumentPartKind part, String nodeId) {
        return Optional.ofNullable(controls.getOrDefault(part, Map.of()).get(nodeId));
    }

    /** Every inline control, in every part, whose tag is {@code tag}. */
    public List<LocatedControl> controlsTagged(String tag) {
        List<LocatedControl> tagged = new ArrayList<>();
        for (LocatedControl located : allControls) {
            if (tag.equals(tagOf(located.control().sdt()))) {
                tagged.add(located);
            }
        }
        return tagged;
    }

    /** A control's tag, or null when it has none. */
    public static String tagOf(CTSdtRun sdt) {
        return sdt.isSetSdtPr() && sdt.getSdtPr().isSetTag() ? sdt.getSdtPr().getTag().getVal() : null;
    }

    /** The paragraph's anchor text, as the structural graph reads it. */
    public static String anchorText(DocxNodeWalker.Paragraph paragraph) {
        StringBuilder text = new StringBuilder();
        for (DocxNodeWalker.Inline inline : paragraph.inlines()) {
            if (inline instanceof DocxNodeWalker.Run run) {
                text.append(shownText(run.run()));
            }
        }
        return text.toString();
    }

    /** A run's text as the graph counts it: a run with a picture is an image, which carries no text. */
    public static String shownText(CTR run) {
        return run.sizeOfDrawingArray() > 0 ? "" : RunText.of(run);
    }

    private void index(DocxNodeWalker.Part part, DocxNodeWalker.Block block) {
        switch (block) {
            case DocxNodeWalker.Paragraph paragraph -> indexParagraph(part, paragraph);
            case DocxNodeWalker.Table table -> {
                for (DocxNodeWalker.Row row : table.rows()) {
                    for (DocxNodeWalker.Cell cell : row.cells()) {
                        for (DocxNodeWalker.Paragraph paragraph : cell.paragraphs()) {
                            indexParagraph(part, paragraph);
                        }
                    }
                }
            }
            case DocxNodeWalker.OtherBlock ignored -> {
                // Its content has no ids.
            }
        }
    }

    private void indexParagraph(DocxNodeWalker.Part part, DocxNodeWalker.Paragraph paragraph) {
        paragraphs.computeIfAbsent(part.kind(), kind -> new HashMap<>())
                .putIfAbsent(paragraph.nodeId(), new LocatedParagraph(part.kind(), part.partName(), paragraph));
        for (DocxNodeWalker.Inline inline : paragraph.inlines()) {
            if (inline instanceof DocxNodeWalker.Control control) {
                LocatedControl located = new LocatedControl(part.kind(), part.partName(), paragraph, control);
                controls.computeIfAbsent(part.kind(), kind -> new HashMap<>()).putIfAbsent(control.nodeId(), located);
                allControls.add(located);
            }
        }
    }
}
