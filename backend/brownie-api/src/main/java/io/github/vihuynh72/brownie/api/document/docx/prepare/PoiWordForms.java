package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.api.document.docx.DocxNodeWalker;
import io.github.vihuynh72.brownie.core.document.DocxParseException;
import io.github.vihuynh72.brownie.core.prepare.FormOutline;
import io.github.vihuynh72.brownie.core.prepare.WordForms;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link WordForms} with Apache POI: {@link FormFieldWriter} writes a
 * form's own blank fields out as their text, then {@link FormOutlineReader}
 * reads the result. The outline is read from the saved file, parsed
 * afresh, so every node id and offset in it is one the editor will find in
 * the same bytes.
 */
public final class PoiWordForms implements WordForms {

    private final String parserVersion;

    /** {@code parserVersion} is the structural reader's, stamped on every anchor the outline yields. */
    public PoiWordForms(String parserVersion) {
        this.parserVersion = parserVersion;
    }

    @Override
    public ReadForm read(byte[] docx) {
        byte[] written = docx;
        Map<String, FormFieldWriter.WrittenField> byRun = new HashMap<>();
        try (XWPFDocument document = open(docx)) {
            Map<CTR, FormFieldWriter.WrittenField> fields = FormFieldWriter.writeOut(document);
            if (!fields.isEmpty()) {
                DocxNodeWalker.Part main = DocxNodeWalker.walk(document).getFirst();
                for (DocxNodeWalker.Paragraph paragraph : paragraphsOf(main)) {
                    for (DocxNodeWalker.Inline inline : paragraph.inlines()) {
                        if (inline instanceof DocxNodeWalker.Run run && fields.containsKey(run.run())) {
                            byRun.put(run.nodeId(), fields.get(run.run()));
                        }
                    }
                }
                written = save(document);
            }
        } catch (IOException e) {
            throw new DocxParseException("The form could not be read.", e);
        }
        try (XWPFDocument reread = open(written)) {
            FormOutline outline = FormOutlineReader.read(reread, byRun, parserVersion);
            return new ReadForm(written, outline);
        } catch (IOException e) {
            throw new DocxParseException("The form could not be read.", e);
        }
    }

    @Override
    public byte[] withoutRows(byte[] docx, List<String> rowNodeIds) {
        if (rowNodeIds.isEmpty()) {
            return docx;
        }
        Set<String> wanted = new HashSet<>(rowNodeIds);
        try (XWPFDocument document = open(docx)) {
            DocxNodeWalker.Part main = DocxNodeWalker.walk(document).getFirst();
            for (DocxNodeWalker.Block block : main.blocks()) {
                if (!(block instanceof DocxNodeWalker.Table table)) {
                    continue;
                }
                XWPFTable xwpf = table.table();
                List<Integer> doomed = new ArrayList<>();
                for (int i = 0; i < table.rows().size(); i++) {
                    if (wanted.remove(table.rows().get(i).nodeId())) {
                        doomed.add(i);
                    }
                }
                for (int i = doomed.size() - 1; i >= 0; i--) {
                    xwpf.removeRow(doomed.get(i));
                }
            }
            if (!wanted.isEmpty()) {
                throw new IllegalArgumentException("No table row " + wanted + " in the main document.");
            }
            return save(document);
        } catch (IOException e) {
            throw new DocxParseException("The form could not be read.", e);
        }
    }

    private static List<DocxNodeWalker.Paragraph> paragraphsOf(DocxNodeWalker.Part part) {
        List<DocxNodeWalker.Paragraph> paragraphs = new ArrayList<>();
        for (DocxNodeWalker.Block block : part.blocks()) {
            switch (block) {
                case DocxNodeWalker.Paragraph paragraph -> paragraphs.add(paragraph);
                case DocxNodeWalker.Table table -> table.rows().forEach(row -> row.cells().forEach(cell -> paragraphs.addAll(cell.paragraphs())));
                case DocxNodeWalker.OtherBlock ignored -> {
                    // Nothing with ids inside.
                }
            }
        }
        return paragraphs;
    }

    private static XWPFDocument open(byte[] docx) {
        try {
            return new XWPFDocument(new ByteArrayInputStream(docx));
        } catch (IOException | RuntimeException e) {
            throw new DocxParseException("The form is not a readable Word document.", e);
        }
    }

    private static byte[] save(XWPFDocument document) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.write(out);
            return out.toByteArray();
        }
    }
}
