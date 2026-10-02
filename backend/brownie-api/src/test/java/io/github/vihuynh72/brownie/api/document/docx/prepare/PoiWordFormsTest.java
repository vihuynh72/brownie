package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.api.document.docx.PoiDocxStructuralExtractor;
import io.github.vihuynh72.brownie.api.document.docx.RawDocx;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.prepare.FormOutline;
import io.github.vihuynh72.brownie.core.prepare.PreparationMode;
import io.github.vihuynh72.brownie.core.prepare.WordForms;
import io.github.vihuynh72.brownie.core.template.ParagraphAnchorText;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a Word form for its places: the outline's text is the graph's to
 * the character, a form's own blank fields are written out as their text
 * and located, and every region and run property the finder relies on is
 * read.
 */
class PoiWordFormsTest {

    private static final PoiDocxStructuralExtractor EXTRACTOR = new PoiDocxStructuralExtractor();
    private static final String HEADER_TYPE = RawDocx.RELATIONSHIP_TYPE_BASE + "header";

    private final PoiWordForms forms = new PoiWordForms(EXTRACTOR.parserVersion());

    @Test
    void everyParagraphsAnchorTextIsTheGraphsForEachSampleForm() throws IOException {
        for (String name : List.of("membership-application", "equipment-request", "reference-letter")) {
            byte[] prepared = new PoiWorkingCopyPreparer().prepare(sampleForm(name + ".docx"), PreparationMode.UPLOAD).docxBytes();
            assertMatchesGraph(forms.read(prepared));
        }
    }

    @Test
    void linksPicturesControlsFieldsTabsAndBreaksCountTheSameAsInTheGraph() {
        byte[] docx = docx(
                "<w:p><w:r><w:t xml:space=\"preserve\">Visit </w:t></w:r><w:hyperlink w:anchor=\"x\"><w:r><w:t>our site</w:t></w:r>"
                        + "</w:hyperlink><w:r><w:tab/><w:t>then</w:t><w:br/><w:t>stop</w:t></w:r></w:p>"
                        + "<w:p><w:r><w:t xml:space=\"preserve\">Page </w:t></w:r>" + field(" PAGE ", "3")
                        + "<w:sdt><w:sdtPr><w:tag w:val=\"kept\"/></w:sdtPr><w:sdtContent><w:r><w:t>inside</w:t></w:r></w:sdtContent></w:sdt>"
                        + "<w:r><w:t>after</w:t></w:r></w:p>");

        WordForms.ReadForm read = forms.read(docx);

        assertMatchesGraph(read);
        FormOutline.Paragraph first = paragraph(read, "p0");
        assertThat(first.anchorText()).isEqualTo("Visit our site\tthen\nstop");
        assertThat(first.atoms()).contains(new FormOutline.Run(6, 14, false, false, true, false));
        FormOutline.Paragraph second = paragraph(read, "p1");
        assertThat(second.anchorText()).isEqualTo("Page 3after");
        assertThat(second.atoms()).contains(new FormOutline.Run(5, 6, false, false, false, true));
        assertThat(second.atoms()).filteredOn(atom -> atom instanceof FormOutline.Control).singleElement()
                .isEqualTo(new FormOutline.Control(6, "p1/sdt6", "kept", null, "inside", FormOutline.ControlKind.TEXT));
    }

    @Test
    void aFormWithNoFieldsForBlanksComesBackByteForByte() {
        byte[] docx = docx("<w:p><w:r><w:t>Name: ____</w:t></w:r></w:p>");

        assertThat(forms.read(docx).docxBytes()).isSameAs(docx);
    }

    @Test
    void aFormsOwnFieldsAreWrittenOutAsTheirTextAndFoundAgainInTheOutline() {
        byte[] docx = docx(
                "<w:p><w:r><w:t xml:space=\"preserve\">Name: </w:t></w:r>"
                        + "<w:r><w:fldChar w:fldCharType=\"begin\"><w:ffData><w:name w:val=\"Text1\"/>"
                        + "<w:helpText w:type=\"text\" w:val=\"Your full name\"/><w:textInput/></w:ffData></w:fldChar></w:r>"
                        + "<w:r><w:instrText xml:space=\"preserve\"> FORMTEXT </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
                        + "<w:r><w:t xml:space=\"preserve\">\u2002\u2002\u2002\u2002\u2002</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
                        + "<w:p><w:r><w:t xml:space=\"preserve\">Dear </w:t></w:r>" + field(" MERGEFIELD FirstName \\* MERGEFORMAT ", "\u00ABFirstName\u00BB")
                        + "<w:r><w:t>,</w:t></w:r></w:p>"
                        + "<w:p><w:fldSimple w:instr=\" MERGEFIELD Last_Name \"><w:r><w:t>\u00ABLast_Name\u00BB</w:t></w:r></w:fldSimple></w:p>"
                        + "<w:p><w:r><w:t xml:space=\"preserve\">Client: </w:t></w:r>"
                        + "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> FILLIN \"Who is the client?\" </w:instrText></w:r>"
                        + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
                        + "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> MACROBUTTON NoMacro [Click to type the town] </w:instrText></w:r>"
                        + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
                        + "<w:p><w:r><w:t xml:space=\"preserve\">Member </w:t></w:r><w:r><w:fldChar w:fldCharType=\"begin\"><w:ffData><w:checkBox><w:default w:val=\"0\"/></w:checkBox></w:ffData></w:fldChar></w:r>"
                        + "<w:r><w:instrText xml:space=\"preserve\"> FORMCHECKBOX </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>");

        WordForms.ReadForm read = forms.read(docx);

        assertMatchesGraph(read);
        assertThat(formField(read, "p0")).isEqualTo(new FormOutline.FormField(6, 11, "FORMTEXT", "Your full name"));
        assertThat(formField(read, "p1")).isEqualTo(new FormOutline.FormField(5, 16, "MERGEFIELD", "FirstName"));
        assertThat(paragraph(read, "p2").anchorText()).isEqualTo("\u00ABLast_Name\u00BB");
        assertThat(formField(read, "p2")).isEqualTo(new FormOutline.FormField(0, 11, "MERGEFIELD", "Last_Name"));
        assertThat(formField(read, "p3")).isEqualTo(new FormOutline.FormField(8, 8, "FILLIN", "Who is the client?"));
        assertThat(paragraph(read, "p4").anchorText()).isEqualTo("[Click to type the town]");
        assertThat(formField(read, "p4").words()).isEqualTo("[Click to type the town]");
        assertThat(paragraph(read, "p5").atoms()).contains(new FormOutline.CheckboxField(7));
        assertThat(xmlOf(read.docxBytes())).doesNotContain("FORMTEXT").doesNotContain("MERGEFIELD").doesNotContain("FILLIN")
                .doesNotContain("fldSimple").contains("FORMCHECKBOX");
    }

    @Test
    void underlineHiddenTextAndTabLinesAreReadFromTheRunTheCharacterStyleAndTheParagraph() {
        byte[] docx = RawDocxWithStyles.build(
                "<w:p><w:pPr><w:tabs><w:tab w:val=\"right\" w:leader=\"underscore\" w:pos=\"9000\"/>"
                        + "<w:tab w:val=\"left\" w:pos=\"2000\"/></w:tabs></w:pPr>"
                        + "<w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:tab/></w:r></w:p>"
                        + "<w:p><w:r><w:rPr><w:u w:val=\"single\"/></w:rPr><w:t xml:space=\"preserve\">   </w:t></w:r>"
                        + "<w:r><w:rPr><w:rStyle w:val=\"Blank\"/></w:rPr><w:t>styled</w:t></w:r>"
                        + "<w:r><w:rPr><w:vanish/></w:rPr><w:t>hidden</w:t></w:r>"
                        + "<w:r><w:rPr><w:u w:val=\"none\"/></w:rPr><w:t>plain</w:t></w:r></w:p>");

        WordForms.ReadForm read = forms.read(docx);

        assertThat(paragraph(read, "p0").atoms()).contains(new FormOutline.Tab(1, null), new FormOutline.Tab(3, "underscore"));
        assertThat(paragraph(read, "p1").atoms()).contains(
                new FormOutline.Run(0, 3, true, false, false, false),
                new FormOutline.Run(3, 9, true, false, false, false),
                new FormOutline.Run(9, 15, false, true, false, false),
                new FormOutline.Run(15, 20, false, false, false, false));
    }

    @Test
    void headersTextBoxesAndTablesInsideTablesAreReadButHaveNoPlaceToFill() throws IOException {
        String textBox = "<w:r><w:pict><v:shape style=\"width:100pt;height:40pt\"><v:textbox><w:txbxContent>"
                + "<w:p><w:r><w:t>[Box name]</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r>";
        String nested = "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol/></w:tblGrid><w:tr><w:tc><w:tcPr/>"
                + "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol/></w:tblGrid><w:tr><w:tc><w:tcPr/><w:p><w:r><w:t>Inner ____</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                + "<w:p/></w:tc></w:tr></w:tbl>";
        byte[] docx = RawDocx.builder()
                .document("<w:p>" + textBox + "</w:p>" + nested
                        + "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdHeader\"/></w:sectPr>")
                .part("word/header1.xml", RawDocx.HEADER_CONTENT_TYPE, RawDocx.wordRoot("hdr", "<w:p><w:r><w:t>Ref: ______</w:t></w:r></w:p>"))
                .documentRelationship("rIdHeader", HEADER_TYPE, "header1.xml", false)
                .build();

        FormOutline outline = forms.read(docx).outline();

        assertThat(outline.paragraphs()).filteredOn(p -> p.region() == FormOutline.Region.TEXT_BOX).singleElement()
                .satisfies(p -> assertThat(p.anchorText()).isEqualTo("[Box name]"));
        assertThat(outline.paragraphs()).filteredOn(p -> p.region() == FormOutline.Region.NESTED_TABLE).singleElement()
                .satisfies(p -> {
                    assertThat(p.anchorText()).isEqualTo("Inner ____");
                    assertThat(p.nodeId()).isNull();
                });
        assertThat(outline.paragraphs()).filteredOn(p -> p.region() == FormOutline.Region.HEADER_FOOTER).singleElement()
                .satisfies(p -> {
                    assertThat(p.part()).isEqualTo(DocumentPartKind.HEADER);
                    assertThat(p.anchorText()).isEqualTo("Ref: ______");
                });
        assertThat(outline.paragraphs()).filteredOn(p -> p.region() == FormOutline.Region.TOP_TABLE_CELL).singleElement()
                .satisfies(p -> assertThat(p.cell()).isEqualTo(new FormOutline.Cell(1, "tbl1", "tbl1/row0", 0, 0, 1)));
    }

    @Test
    void rowsAreTakenOutOfATableAndEveryOtherNodeKeepsItsId() throws IOException {
        String emptyRow = "<w:tr><w:tc><w:tcPr/><w:p/></w:tc></w:tr>";
        byte[] docx = docx("<w:tbl><w:tblPr/><w:tblGrid><w:gridCol/></w:tblGrid><w:tr><w:tc><w:tcPr/><w:p><w:r><w:t>Item</w:t></w:r></w:p></w:tc></w:tr>"
                + emptyRow + emptyRow + emptyRow + "</w:tbl><w:p><w:r><w:t>After</w:t></w:r></w:p>");

        byte[] fewer = forms.withoutRows(docx, List.of("tbl0/row2", "tbl0/row3"));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fewer))) {
            assertThat(document.getTables().getFirst().getNumberOfRows()).isEqualTo(2);
        }
        assertThat(nodeIds(graph(fewer))).contains("tbl0/row1/cell0/p0", "p1").doesNotContain("tbl0/row2");
        assertThat(forms.withoutRows(docx, List.of())).isSameAs(docx);
    }

    // ---------------------------------------------------------------- helpers

    /** Every paragraph the outline gives a node id reads exactly as the graph of the same bytes does. */
    private static void assertMatchesGraph(WordForms.ReadForm read) {
        DocxStructuralGraph graph = graph(read.docxBytes());
        Map<String, StructuralNode> graphParagraphs = new HashMap<>();
        for (DocumentPart part : graph.parts()) {
            if (part.kind() == DocumentPartKind.MAIN_DOCUMENT) {
                collect(part.root(), graphParagraphs);
            }
        }
        List<FormOutline.Paragraph> walked = read.outline().paragraphs().stream()
                .filter(p -> p.part() == DocumentPartKind.MAIN_DOCUMENT && p.nodeId() != null)
                .toList();
        assertThat(walked).isNotEmpty();
        for (FormOutline.Paragraph paragraph : walked) {
            assertThat(paragraph.anchorText()).as(paragraph.nodeId())
                    .isEqualTo(ParagraphAnchorText.of(graphParagraphs.get(paragraph.nodeId())));
        }
    }

    private static void collect(StructuralNode node, Map<String, StructuralNode> paragraphs) {
        if (node.kind() == StructuralNodeKind.PARAGRAPH) {
            paragraphs.put(node.nodeId(), node);
        }
        node.children().forEach(child -> collect(child, paragraphs));
    }

    private static List<String> nodeIds(DocxStructuralGraph graph) {
        List<String> ids = new java.util.ArrayList<>();
        collectIds(graph.parts().getFirst().root(), ids);
        return ids;
    }

    private static void collectIds(StructuralNode node, List<String> ids) {
        ids.add(node.nodeId());
        node.children().forEach(child -> collectIds(child, ids));
    }

    private static FormOutline.Paragraph paragraph(WordForms.ReadForm read, String nodeId) {
        return read.outline().paragraphs().stream()
                .filter(p -> p.part() == DocumentPartKind.MAIN_DOCUMENT && nodeId.equals(p.nodeId()))
                .findFirst()
                .orElseThrow();
    }

    private static FormOutline.FormField formField(WordForms.ReadForm read, String nodeId) {
        return paragraph(read, nodeId).atoms().stream()
                .filter(atom -> atom instanceof FormOutline.FormField)
                .map(FormOutline.FormField.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static DocxStructuralGraph graph(byte[] docx) {
        try {
            DocxExtractionOutcome outcome = EXTRACTOR.extract(new ByteArrayInputStream(docx));
            return ((DocxExtractionOutcome.Supported) outcome).graph();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String xmlOf(byte[] docx) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            return document.getDocument().xmlText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String field(String instruction, String result) {
        return "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\">" + instruction
                + "</w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>" + result
                + "</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
    }

    private static byte[] docx(String body) {
        try {
            return RawDocx.builder().document(body).build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] sampleForm(String fileName) throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("fixtures/public/forms"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("could not locate the repository root from the test working directory");
        }
        return Files.readAllBytes(current.resolve("fixtures/public/forms").resolve(fileName));
    }

    /** A document with a character style that underlines, for reading underline through a style. */
    private static final class RawDocxWithStyles {

        static byte[] build(String body) {
            String styles = RawDocx.wordRoot("styles",
                    "<w:style w:type=\"character\" w:styleId=\"Blank\"><w:name w:val=\"Blank\"/><w:rPr><w:u w:val=\"single\"/></w:rPr></w:style>");
            try {
                return RawDocx.builder()
                        .document(body)
                        .part("word/styles.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml", styles)
                        .documentRelationship("rIdStyles", RawDocx.RELATIONSHIP_TYPE_BASE + "styles", "styles.xml", false)
                        .build();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
