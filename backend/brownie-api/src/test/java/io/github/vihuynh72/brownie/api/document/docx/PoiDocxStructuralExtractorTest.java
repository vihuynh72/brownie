package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxParseException;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.document.UnsupportedDocxFeature;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoiDocxStructuralExtractorTest {

    private final PoiDocxStructuralExtractor extractor = new PoiDocxStructuralExtractor();

    @Test
    void parserVersionIsStableAndNonBlank() {
        assertEquals(PoiDocxStructuralExtractor.PARSER_VERSION, extractor.parserVersion());
        assertFalse(extractor.parserVersion().isBlank());
    }

    @Test
    void qualifiedDocumentExtractsAsSupportedWithExpectedStructure() throws IOException {
        DocxExtractionOutcome outcome = extract(DocxFixtures.qualifiedDocument());
        assertTrue(outcome instanceof DocxExtractionOutcome.Supported, "expected a supported outcome, got " + outcome);
        var graph = ((DocxExtractionOutcome.Supported) outcome).graph();
        assertEquals(PoiDocxStructuralExtractor.PARSER_VERSION, graph.parserVersion());

        DocumentPart main = partOfKind(graph.parts(), DocumentPartKind.MAIN_DOCUMENT);
        assertEquals("word/document.xml", main.partName());
        DocumentPart header = partOfKind(graph.parts(), DocumentPartKind.HEADER);
        assertEquals("word/header1.xml", header.partName());
        DocumentPart footer = partOfKind(graph.parts(), DocumentPartKind.FOOTER);
        assertEquals("word/footer1.xml", footer.partName());

        StructuralNode headingParagraph = main.root().children().get(0);
        assertEquals(StructuralNodeKind.PARAGRAPH, headingParagraph.kind());
        // "Heading1" is basedOn "Normal" and only sets bold/size directly -- its font family
        // must resolve all the way up to docDefaults, proving the basedOn chain is walked.
        StructuralNode headingRun = headingParagraph.children().get(0);
        assertEquals("Liberation Sans", headingRun.style().fontFamily());
        assertEquals(Boolean.TRUE, headingRun.style().bold());
        assertEquals(32, headingRun.style().fontSizeHalfPoints());

        StructuralNode fieldParagraph = main.root().children().get(1);
        StructuralNode contentControl = fieldParagraph.children().get(1);
        assertEquals(StructuralNodeKind.CONTENT_CONTROL, contentControl.kind());
        assertEquals("meeting.title", contentControl.contentControlTag());
        assertEquals("[meeting title]", contentControl.children().get(0).text());

        StructuralNode numberedParagraph = main.root().children().get(2);
        assertEquals(1, numberedParagraph.style().numberingId());
        assertEquals(0, numberedParagraph.style().numberingLevel());

        StructuralNode table = main.root().children().get(3);
        assertEquals(StructuralNodeKind.TABLE, table.kind());
        StructuralNode firstCell = table.children().get(0).children().get(0);
        assertEquals(StructuralNodeKind.TABLE_CELL, firstCell.kind());

        StructuralNode imageParagraph = main.root().children().get(4);
        StructuralNode image = imageParagraph.children().get(0);
        assertEquals(StructuralNodeKind.IMAGE, image.kind());
        assertNotNull(image.imageRelationshipId());
    }

    /**
     * A package may declare its main document under any name, and the upload inspector reads only parts named as
     * XML. Such a file reaches the library unread; nested this deeply it exhausts the library's stack, which must
     * come out as an ordinary failure to parse and not as an error nothing records.
     */
    @Test
    void aDocumentNestedDeeplyEnoughToExhaustTheLibraryIsAFailureToParseNotACrash() throws IOException {
        int depth = 20_000;
        // Each level carries something that does not compress, or the library's own check on how far an archive
        // expands refuses the file first, and it is the nesting that is being proved here.
        java.util.Random noise = new java.util.Random(1);
        StringBuilder opening = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            opening.append("<w:tbl><w:tr><w:tc w:rsidR=\"").append(Long.toHexString(noise.nextLong())).append("\">");
        }
        String body = opening + "</w:tc></w:tr></w:tbl>".repeat(depth);
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body
                + "</w:body></w:document>";
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Override PartName=\"/word/document.bin\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";
        String relationships = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"word/document.bin\"/></Relationships>";
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String[] part : new String[][] {
                    {"[Content_Types].xml", contentTypes}, {"_rels/.rels", relationships}, {"word/document.bin", document}}) {
                zip.putNextEntry(new java.util.zip.ZipEntry(part[0]));
                zip.write(part[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }

        // On a thread with a stack of a known, modest size, so that the outcome does not depend on the machine: the
        // default stack differs by operating system and processor, and on a large one this depth might just fit.
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
        Thread reader = new Thread(null, () -> {
            try {
                extractor.extract(new java.io.ByteArrayInputStream(bytes.toByteArray()));
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        }, "small-stack-reader", 512 * 1024);
        reader.start();
        try {
            reader.join(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        io.github.vihuynh72.brownie.core.document.DocxParseException refused = org.junit.jupiter.api.Assertions.assertInstanceOf(
                io.github.vihuynh72.brownie.core.document.DocxParseException.class, thrown.get());
        // Not some other refusal that happens to come first: the stack really was exhausted, and that is what was caught.
        org.junit.jupiter.api.Assertions.assertInstanceOf(StackOverflowError.class, refused.getCause());
    }

    @Test
    void trackedChangeIsFlaggedUnsupported() throws IOException {
        assertUnsupported(DocxFixtures.withTrackedChange(), UnsupportedDocxFeature.TRACKED_CHANGES);
    }

    @Test
    void commentIsFlaggedUnsupported() throws IOException {
        assertUnsupported(DocxFixtures.withComment(), UnsupportedDocxFeature.UNRESOLVED_COMMENT);
    }

    /** Graph version 3 keeps a floating shape as it is: the graph is complete without it, and filling does not touch it. */
    @Test
    void floatingShapeIsKeptAsIs() throws IOException {
        assertKeptAsIs(DocxFixtures.withFloatingShape(), UnsupportedDocxFeature.FLOATING_SHAPE);
    }

    /** Graph version 3 keeps a table inside a table as it is; its inner table has no node ids, as before. */
    @Test
    void nestedTableIsKeptAsIs() throws IOException {
        assertKeptAsIs(DocxFixtures.withNestedTable(), UnsupportedDocxFeature.NESTED_TABLE);
    }

    @Test
    void linkedExternalImageIsFlaggedUnsupported() throws IOException {
        assertUnsupported(DocxFixtures.withLinkedExternalImage(), UnsupportedDocxFeature.LINKED_EXTERNAL_IMAGE);
    }

    /** An object that names no program is not on the allowed list, so it still stops the read, now as an unsafe one. */
    @Test
    void embeddedObjectOfNoKnownProgramIsFlaggedUnsupported() throws IOException {
        assertUnsupported(DocxFixtures.withEmbeddedObject(), UnsupportedDocxFeature.UNSAFE_EMBEDDED_OBJECT);
    }

    /** A merge field is one of a form's own blanks: graph version 3 keeps it as it is, named by its kind of field. */
    @Test
    void mergeFieldIsKeptAsIsAsADynamicField() throws IOException {
        var keptAsIs = assertKeptAsIs(DocxFixtures.withUnsupportedField(), UnsupportedDocxFeature.DYNAMIC_FIELD);
        assertEquals("MERGEFIELD", keptAsIs.getFirst().fieldKeyword());
    }

    @Test
    void aFieldThatFetchesAnotherFileIsFlaggedUnsupported() throws IOException {
        assertUnsupported(DocxFixtures.withComplexField(" INCLUDETEXT \"C:\\\\share\\\\clause.docx\" "), UnsupportedDocxFeature.UNSUPPORTED_FIELD);
        assertUnsupported(DocxFixtures.withComplexField(" DDEAUTO c:\\\\windows\\\\system32\\\\cmd.exe \"/k calc\" "),
                UnsupportedDocxFeature.UNSUPPORTED_FIELD);
        assertUnsupported(DocxFixtures.withComplexField(" SOMETHINGUNKNOWN "), UnsupportedDocxFeature.UNSUPPORTED_FIELD);
    }

    /** A page number is supported as before; graph version 3 also names it as a field the file keeps as it is. */
    @Test
    void pageFieldIsNotFlagged() throws IOException {
        DocxExtractionOutcome outcome = extract(DocxFixtures.withPageField());
        assertTrue(outcome instanceof DocxExtractionOutcome.Supported, "expected supported, got " + outcome);
        var keptAsIs = ((DocxExtractionOutcome.Supported) outcome).keptAsIs().findings();
        assertEquals(1, keptAsIs.size(), keptAsIs::toString);
        assertEquals(UnsupportedDocxFeature.DYNAMIC_FIELD, keptAsIs.getFirst().feature());
        assertEquals("PAGE", keptAsIs.getFirst().fieldKeyword());
    }

    @Test
    void repeatedHeadingParagraphsGetDistinctCorrectlyAddressedNodesRatherThanColliding() throws IOException {
        var graph = supported(DocxFixtures.repeatedHeadingsDocument());
        var body = partOfKind(graph.parts(), DocumentPartKind.MAIN_DOCUMENT).root();

        StructuralNode firstHeading = body.children().get(0);
        StructuralNode firstBody = body.children().get(1);
        StructuralNode secondHeading = body.children().get(2);
        StructuralNode secondBody = body.children().get(3);

        // Identical heading text at two different, distinct node paths --
        // each keeps its own correct following content, proving they were
        // never merged or had their content swapped.
        assertEquals("Agenda", firstHeading.children().get(0).text());
        assertEquals("Agenda", secondHeading.children().get(0).text());
        assertEquals("p0", firstHeading.nodeId());
        assertEquals("p2", secondHeading.nodeId());
        assertEquals("Approve last meeting's minutes.", firstBody.children().get(0).text());
        assertEquals("Discuss the budget.", secondBody.children().get(0).text());
    }

    @Test
    void multiRowTableCellsAreDistinctlyAddressedWithCorrectText() throws IOException {
        var graph = supported(DocxFixtures.multiRowTableDocument());
        StructuralNode table = partOfKind(graph.parts(), DocumentPartKind.MAIN_DOCUMENT).root().children().get(0);
        assertEquals(StructuralNodeKind.TABLE, table.kind());
        assertEquals(3, table.children().size(), "expected three rows");

        String[][] expectedText = {{"Task", "Owner"}, {"Draft agenda", "Jordan Lee"}, {"Book the room", "Priya Nair"}};
        java.util.Set<String> nodeIds = new java.util.HashSet<>();
        for (int row = 0; row < 3; row++) {
            StructuralNode rowNode = table.children().get(row);
            assertEquals(StructuralNodeKind.TABLE_ROW, rowNode.kind());
            assertEquals(2, rowNode.children().size(), "expected two cells in row " + row);
            for (int cell = 0; cell < 2; cell++) {
                StructuralNode cellNode = rowNode.children().get(cell);
                assertEquals(StructuralNodeKind.TABLE_CELL, cellNode.kind());
                String cellText = cellNode.children().get(0).children().get(0).text();
                assertEquals(expectedText[row][cell], cellText, "row " + row + " cell " + cell);
                assertTrue(nodeIds.add(cellNode.nodeId()), "cell node id must be unique: " + cellNode.nodeId());
            }
        }
    }

    @Test
    void headerAndFooterTextContentIsCorrectlyExtractedNotJustTheirPartNames() throws IOException {
        var graph = supported(DocxFixtures.qualifiedDocument());
        StructuralNode headerBody = partOfKind(graph.parts(), DocumentPartKind.HEADER).root();
        StructuralNode footerBody = partOfKind(graph.parts(), DocumentPartKind.FOOTER).root();

        String headerText = headerBody.children().get(0).children().get(0).text();
        String footerText = footerBody.children().get(0).children().get(0).text();
        assertEquals("Brownie Meeting Minutes Template", headerText);
        assertEquals("Footer", footerText);
    }

    /**
     * A real, previously-latent bug: {@code CTColor.getVal()} returns
     * {@code Object} for its hex-color-or-"auto" union type, and its
     * actual runtime type is a raw {@code byte[]}; naively calling {@code
     * String.valueOf(val)} on that silently produced {@code
     * Object.toString()}'s own identity-hash-code text (for example
     * {@code "[B@42721fe"}), different on every independent extraction of
     * the identical color, rather than a real hex string. Never caught
     * before because nothing before the layout comparator
     * ever compared two independently extracted {@code ResolvedStyle}
     * values for equality. Asserts both the real hex value and that two
     * separately extracted runs sharing the same color compare equal --
     * the second assertion is what actually would have caught this bug.
     */
    @Test
    void colorHexResolvesToARealHexStringNotObjectToString() throws IOException {
        var graph = supported(DocxFixtures.documentWithTwoIdenticallyColoredRuns());
        StructuralNode paragraph = graph.parts().get(0).root().children().get(0);
        StructuralNode firstRun = paragraph.children().get(0);
        StructuralNode secondRun = paragraph.children().get(1);

        assertEquals("FF0000", firstRun.style().colorHex());
        assertEquals(firstRun.style(), secondRun.style());
    }

    /**
     * POI hands back {@code on} and {@code off} as the words themselves but
     * the other four as Booleans, and throws for a value outside the
     * schema. Every spelling must resolve by what it means, and one outside
     * the schema must read as off, the way the renderer draws it, rather
     * than fail the whole extraction.
     */
    @Test
    void everyOnOffSpellingOfBoldAndItalicResolvesByWhatItMeans() throws IOException {
        String[] values = {"on", "off", "1", "0", "true", "false", null, "On", "yes", ""};
        Boolean[] expected = {true, false, true, false, true, false, true, true, false, false};
        StringBuilder body = new StringBuilder();
        for (String value : values) {
            String attribute = value == null ? "" : " w:val=\"" + value + "\"";
            body.append("<w:p><w:r><w:rPr><w:b").append(attribute).append("/><w:i").append(attribute)
                    .append("/></w:rPr><w:t>x</w:t></w:r></w:p>");
        }
        body.append("<w:p><w:r><w:t>plain</w:t></w:r></w:p>");

        StructuralNode root = partOfKind(supported(minimalDocx(body.toString())).parts(), DocumentPartKind.MAIN_DOCUMENT).root();

        for (int i = 0; i < values.length; i++) {
            StructuralNode run = root.children().get(i).children().getFirst();
            String described = values[i] == null ? "no w:val" : "w:val=\"" + values[i] + "\"";
            assertEquals(expected[i], run.style().bold(), "bold with " + described);
            assertEquals(expected[i], run.style().italic(), "italic with " + described);
        }
        StructuralNode plain = root.children().get(values.length).children().getFirst();
        assertNull(plain.style().bold());
        assertNull(plain.style().italic());
    }

    /** The built-in templates write every heading and label as {@code <w:b w:val="on"/>}, and leave the fill spots' own runs unset. */
    @Test
    void theBuiltInTemplatesHeadingsAndLabelsAreBoldAndTheirPlaceholdersAreNot() throws IOException {
        byte[] flowing;
        try (var in = new org.springframework.core.io.ClassPathResource("builtin-templates/flowing-meeting-minutes.docx").getInputStream()) {
            flowing = in.readAllBytes();
        }
        StructuralNode root = partOfKind(supported(flowing).parts(), DocumentPartKind.MAIN_DOCUMENT).root();

        StructuralNode title = root.children().get(1).children().getFirst();
        assertEquals("Meeting Minutes", title.text());
        assertEquals(Boolean.TRUE, title.style().bold());
        StructuralNode label = root.children().get(2).children().get(0);
        assertEquals("Title: ", label.text());
        assertEquals(Boolean.TRUE, label.style().bold());
        StructuralNode placeholder = root.children().get(2).children().get(1).children().getFirst();
        assertEquals("[meeting title]", placeholder.text());
        assertNull(placeholder.style().bold());
    }

    // --- graph version 3: what stops the read, and what is kept as it is ---

    /** Graph version 2 saw only insertions and deletions directly in a paragraph; every other tracked change to the words stops the read too. */
    @Test
    void everyKindOfTrackedChangeStopsTheRead() throws IOException {
        String track = " w:id=\"1\" w:author=\"A\"";
        List<String> bodies = List.of(
                "<w:p><w:pPr><w:rPr><w:del" + track + "/></w:rPr></w:pPr><w:r><w:t>x</w:t></w:r></w:p><w:p/>",
                "<w:tbl><w:tr><w:trPr><w:del" + track + "/></w:trPr><w:tc><w:p/></w:tc></w:tr></w:tbl>",
                "<w:tbl><w:tr><w:trPr><w:ins" + track + "/></w:trPr><w:tc><w:p/></w:tc></w:tr></w:tbl>",
                "<w:tbl><w:tr><w:tc><w:tcPr><w:cellDel" + track + "/></w:tcPr><w:p/></w:tc></w:tr></w:tbl>",
                "<w:p><w:moveFrom" + track + "><w:r><w:t>x</w:t></w:r></w:moveFrom></w:p>",
                "<w:p><w:moveTo" + track + "><w:r><w:t>x</w:t></w:r></w:moveTo></w:p>",
                "<w:p><w:hyperlink><w:ins" + track + "><w:r><w:t>x</w:t></w:r></w:ins></w:hyperlink></w:p>",
                "<w:p><w:smartTag w:element=\"x\"><w:del" + track + "><w:r><w:delText>x</w:delText></w:r></w:del></w:smartTag></w:p>");
        for (String body : bodies) {
            DocxExtractionOutcome outcome = extract(minimalDocx(body));
            var report = assertInstanceOf(DocxExtractionOutcome.Unsupported.class, outcome, body).featureReport();
            assertTrue(report.refused().stream().allMatch(f -> f.feature() == UnsupportedDocxFeature.TRACKED_CHANGES), body);
        }
    }

    /**
     * A tracked change to formatting alone leaves the words as they are, so it is kept as it is: graph version 2
     * never refused a file for one, and templates made from such files must keep drawing and validating.
     */
    @Test
    void aTrackedFormattingChangeIsKeptAsItIs() throws IOException {
        String track = " w:id=\"1\" w:author=\"A\"";
        List<String> bodies = List.of(
                "<w:p><w:r><w:rPr><w:b/><w:rPrChange" + track + "><w:rPr/></w:rPrChange></w:rPr><w:t>x</w:t></w:r></w:p>",
                "<w:p><w:pPr><w:jc w:val=\"center\"/><w:pPrChange" + track + "><w:pPr/></w:pPrChange></w:pPr><w:r><w:t>x</w:t></w:r></w:p>",
                "<w:tbl><w:tr><w:trPr><w:trPrChange" + track + "><w:trPr/></w:trPrChange></w:trPr><w:tc><w:p/></w:tc></w:tr></w:tbl>",
                "<w:p/><w:sectPr><w:sectPrChange" + track + "><w:sectPr/></w:sectPrChange></w:sectPr>");
        for (String body : bodies) {
            var kept = assertKeptAsIs(minimalDocx(body), UnsupportedDocxFeature.TRACKED_FORMATTING_CHANGE);
            assertTrue(kept.stream().allMatch(f -> f.feature() == UnsupportedDocxFeature.TRACKED_FORMATTING_CHANGE), body);
        }
    }

    @Test
    void aTrackedChangeInAHeaderOrAFootnoteStopsTheReadToo() throws IOException {
        String deletion = "<w:p><w:del w:id=\"1\" w:author=\"A\"><w:r><w:delText>x</w:delText></w:r></w:del></w:p>";
        byte[] inHeader = RawDocx.builder()
                .document("<w:p/><w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdHeader\"/></w:sectPr>")
                .part("word/header1.xml", RawDocx.HEADER_CONTENT_TYPE, RawDocx.wordRoot("hdr", deletion))
                .documentRelationship("rIdHeader", RawDocx.RELATIONSHIP_TYPE_BASE + "header", "header1.xml", false)
                .build();
        byte[] inFootnote = RawDocx.builder()
                .document("<w:p><w:r><w:footnoteReference w:id=\"1\"/></w:r></w:p>")
                .part("word/footnotes.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml",
                        RawDocx.wordRoot("footnotes", "<w:footnote w:id=\"1\">" + deletion + "</w:footnote>"))
                .documentRelationship("rIdNotes", RawDocx.RELATIONSHIP_TYPE_BASE + "footnotes", "footnotes.xml", false)
                .build();

        var header = assertInstanceOf(DocxExtractionOutcome.Unsupported.class, extract(inHeader)).featureReport().refused();
        assertEquals("word/header1.xml, p0", header.getFirst().location());
        var footnote = assertInstanceOf(DocxExtractionOutcome.Unsupported.class, extract(inFootnote)).featureReport().refused();
        assertEquals("word/footnotes.xml", footnote.getFirst().location());
    }

    @Test
    void findingsNameTheTopLevelNodeTheyAreIn() throws IOException {
        var report = assertInstanceOf(DocxExtractionOutcome.Unsupported.class, extract(minimalDocx(
                "<w:p/><w:tbl><w:tr><w:tc><w:p><w:del w:id=\"1\" w:author=\"A\"><w:r><w:delText>x</w:delText></w:r></w:del>"
                        + "</w:p></w:tc></w:tr></w:tbl>"))).featureReport();
        assertEquals(List.of("word/document.xml, tbl1"), report.refused().stream().map(f -> f.location()).distinct().toList());
    }

    @Test
    void aCommentReferenceWithoutACommentsPartStillStopsTheRead() throws IOException {
        assertUnsupported(minimalDocx("<w:p><w:commentRangeStart w:id=\"0\"/><w:r><w:t>x</w:t></w:r><w:commentRangeEnd w:id=\"0\"/>"
                + "<w:r><w:commentReference w:id=\"0\"/></w:r></w:p>"), UnsupportedDocxFeature.UNRESOLVED_COMMENT);
    }

    @Test
    void aFieldIsJudgedByItsWholeInstructionEvenWhenSplitOverRuns() throws IOException {
        String split = "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> %s</w:instrText></w:r>"
                + "<w:r><w:instrText>%s </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
                + "<w:r><w:t>shown</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>";
        assertUnsupported(minimalDocx(split.formatted("INCLUDE", "TEXT \"a.docx\"")), UnsupportedDocxFeature.UNSUPPORTED_FIELD);
        var kept = assertKeptAsIs(minimalDocx(split.formatted("PA", "GE")), UnsupportedDocxFeature.DYNAMIC_FIELD);
        assertEquals("PAGE", kept.getFirst().fieldKeyword());
    }

    /** A field in a field's code is judged on its own; the outer field keeps its own kind. */
    @Test
    void aFieldInsideAnotherFieldsCodeIsJudgedOnItsOwn() throws IOException {
        String nested = "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> IF </w:instrText></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> DDEAUTO x y </w:instrText></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"
                + "<w:r><w:instrText> = 1 \"yes\" \"no\" </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
                + "<w:r><w:t>yes</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>";
        var report = assertInstanceOf(DocxExtractionOutcome.Unsupported.class, extract(minimalDocx(nested))).featureReport();
        assertEquals(List.of("DDEAUTO"), report.refused().stream().map(f -> f.fieldKeyword()).toList());
        assertEquals(List.of("IF"), report.keptAsIs().stream().map(f -> f.fieldKeyword()).toList());
    }

    @Test
    void anEmbeddedDocumentOfAnAllowedProgramIsKeptAndAnyOtherObjectStopsTheRead() throws IOException {
        String object = "<w:p><w:r><w:object><v:shape id=\"s\" style=\"width:10pt;height:10pt\"/>"
                + "<o:OLEObject Type=\"%s\" ProgID=\"%s\" ShapeID=\"s\"/></w:object></w:r></w:p>";
        for (String progId : List.of("Excel.Sheet.12", "Excel.Chart.8", "Word.Document.12", "PowerPoint.Slide.12", "Visio.Drawing.15")) {
            assertKeptAsIs(minimalDocx(object.formatted("Embed", progId)), UnsupportedDocxFeature.EMBEDDED_OBJECT);
        }
        for (String progId : List.of("Package", "Equation.3", "Excel.SheetMacroEnabled.12", "Forms.TextBox.1", "Unknown.Thing")) {
            assertUnsupported(minimalDocx(object.formatted("Embed", progId)), UnsupportedDocxFeature.UNSAFE_EMBEDDED_OBJECT);
        }
        assertUnsupported(minimalDocx(object.formatted("Link", "Excel.Sheet.12")), UnsupportedDocxFeature.UNSAFE_EMBEDDED_OBJECT);
        assertUnsupported(minimalDocx("<w:p><w:r><w:object><v:shape id=\"s\"/><w:control r:id=\"rId9\" w:name=\"TextBox1\"/></w:object></w:r></w:p>"),
                UnsupportedDocxFeature.UNSAFE_EMBEDDED_OBJECT);
    }

    /** Word 2010 and later write a text box as a drawing with an older shape as its fallback, both of which the typed accessors miss. */
    @Test
    void aFloatingTextBoxIsKeptAsItIsAndAnInlineOldPictureIsNotReported() throws IOException {
        String textBox = "<w:p><w:r><mc:AlternateContent><mc:Choice Requires=\"wps\"><w:drawing><wp:anchor><wp:extent cx=\"1\" cy=\"1\"/>"
                + "</wp:anchor></w:drawing></mc:Choice><mc:Fallback><w:pict><v:shape id=\"t\" style=\"position: absolute; width:10pt\">"
                + "<v:textbox><w:txbxContent><w:p><w:r><w:t>boxed</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict>"
                + "</mc:Fallback></mc:AlternateContent></w:r></w:p>";
        List<io.github.vihuynh72.brownie.core.document.DocxFeatureFinding> kept = assertKeptAsIs(minimalDocx(textBox), UnsupportedDocxFeature.FLOATING_SHAPE);
        assertEquals(2, kept.size(), kept::toString);

        DocxExtractionOutcome inline = extract(minimalDocx("<w:p><w:r><w:pict><v:shape id=\"p\" style=\"width:10pt;height:10pt\">"
                + "<v:imagedata r:id=\"rId9\"/></v:shape></w:pict></w:r></w:p>"));
        assertTrue(assertInstanceOf(DocxExtractionOutcome.Supported.class, inline).keptAsIs().findings().isEmpty());
    }

    @Test
    void aPictureLinkedThroughAnExternalRelationshipStopsTheRead() throws IOException {
        byte[] linked = RawDocx.builder()
                .document("<w:p><w:r><w:pict><v:shape id=\"p\" style=\"width:10pt\"><v:imagedata r:id=\"rIdFar\"/></v:shape></w:pict></w:r></w:p>")
                .documentRelationship("rIdFar", RawDocx.RELATIONSHIP_TYPE_BASE + "image", "file://server/share/logo.png", true)
                .build();
        assertUnsupported(linked, UnsupportedDocxFeature.LINKED_EXTERNAL_IMAGE);
    }

    @Test
    void aSignatureStopsTheRead() throws IOException {
        byte[] signed = RawDocx.builder()
                .document("<w:p/>")
                .part("_xmlsignatures/origin.sigs", "application/vnd.openxmlformats-package.digital-signature-origin", new byte[0])
                .packageRelationship("rIdSig", "http://schemas.openxmlformats.org/package/2006/relationships/digital-signature/origin",
                        "_xmlsignatures/origin.sigs")
                .build();
        assertUnsupported(signed, UnsupportedDocxFeature.PACKAGE_SIGNATURE);
    }

    /** Graph version 2 searched for a drawing's picture from the start of the part, so every picture after the first named the first one's. */
    @Test
    void eachPictureNamesItsOwnRelationship() throws IOException {
        XWPFDocumentBuilder builder = new XWPFDocumentBuilder();
        var graph = supported(builder.twoPictures());
        var first = partOfKind(graph.parts(), DocumentPartKind.MAIN_DOCUMENT).root().children().get(0).children().getFirst();
        var second = partOfKind(graph.parts(), DocumentPartKind.MAIN_DOCUMENT).root().children().get(1).children().getFirst();
        assertEquals(StructuralNodeKind.IMAGE, second.kind());
        assertNotNull(first.imageRelationshipId());
        assertNotNull(second.imageRelationshipId());
        assertFalse(first.imageRelationshipId().equals(second.imageRelationshipId()), first + " / " + second);
    }

    /** Builds a document with two different pictures, each in its own paragraph. */
    private static final class XWPFDocumentBuilder {
        byte[] twoPictures() throws IOException {
            try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
                for (String name : List.of("one.png", "two.png")) {
                    var run = doc.createParagraph().createRun();
                    byte[] png = java.util.Base64.getDecoder().decode(name.startsWith("one")
                            ? "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
                            : "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
                    try (var in = new ByteArrayInputStream(png)) {
                        run.addPicture(in, org.apache.poi.common.usermodel.PictureType.PNG, name,
                                org.apache.poi.util.Units.pixelToEMU(1), org.apache.poi.util.Units.pixelToEMU(1));
                    } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
                        throw new IOException(e);
                    }
                }
                var out = new java.io.ByteArrayOutputStream();
                doc.write(out);
                return out.toByteArray();
            }
        }
    }

    @Test
    void corruptPackageThrowsDocxParseException() {
        assertThrows(DocxParseException.class, () -> extractor.extract(new ByteArrayInputStream(DocxFixtures.corruptPackage())));
    }

    private io.github.vihuynh72.brownie.core.document.DocxStructuralGraph supported(byte[] bytes) throws IOException {
        DocxExtractionOutcome outcome = extract(bytes);
        assertTrue(outcome instanceof DocxExtractionOutcome.Supported, "expected supported, got " + outcome);
        return ((DocxExtractionOutcome.Supported) outcome).graph();
    }

    private void assertUnsupported(byte[] bytes, UnsupportedDocxFeature expected) throws IOException {
        DocxExtractionOutcome outcome = extract(bytes);
        assertTrue(outcome instanceof DocxExtractionOutcome.Unsupported, "expected unsupported, got " + outcome);
        var report = ((DocxExtractionOutcome.Unsupported) outcome).featureReport();
        assertFalse(report.isSupported());
        assertTrue(
                report.findings().stream().anyMatch(f -> f.feature() == expected),
                "expected " + expected + " among " + report.findings());
    }

    private List<io.github.vihuynh72.brownie.core.document.DocxFeatureFinding> assertKeptAsIs(byte[] bytes, UnsupportedDocxFeature expected)
            throws IOException {
        DocxExtractionOutcome outcome = extract(bytes);
        assertTrue(outcome instanceof DocxExtractionOutcome.Supported, "expected supported, got " + outcome);
        var keptAsIs = ((DocxExtractionOutcome.Supported) outcome).keptAsIs();
        assertTrue(keptAsIs.findings().stream().anyMatch(f -> f.feature() == expected), "expected " + expected + " among " + keptAsIs.findings());
        assertTrue(keptAsIs.refused().isEmpty());
        return keptAsIs.findings().stream().filter(f -> f.feature() == expected).toList();
    }

    private DocxExtractionOutcome extract(byte[] bytes) throws IOException {
        return extractor.extract(new ByteArrayInputStream(bytes));
    }

    /** The smallest package a reader accepts: a main document holding {@code bodyXml}, and no styles or settings part. */
    private static byte[] minimalDocx(String bodyXml) throws IOException {
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document" + RawDocx.NAMESPACES + "><w:body>" + bodyXml
                + "</w:body></w:document>";
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";
        String relationships = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"word/document.xml\"/></Relationships>";
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String[] part : new String[][] {
                    {"[Content_Types].xml", contentTypes}, {"_rels/.rels", relationships}, {"word/document.xml", document}}) {
                zip.putNextEntry(new java.util.zip.ZipEntry(part[0]));
                zip.write(part[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static DocumentPart partOfKind(List<DocumentPart> parts, DocumentPartKind kind) {
        return parts.stream()
                .filter(p -> p.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no part of kind " + kind + " in " + parts));
    }
}
