package io.github.vihuynh72.brownie.core.artifact;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
            // Highly compressible, deliberately larger than the tiny test cap below.
            zip.write("A".repeat(10_000).getBytes());
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
