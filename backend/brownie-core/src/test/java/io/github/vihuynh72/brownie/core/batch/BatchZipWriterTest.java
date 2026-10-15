package io.github.vihuynh72.brownie.core.batch;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive writer's own contract: safe unique entry names, a CSV every
 * spreadsheet app opens harmlessly, and a hard stop the moment a stored
 * file no longer matches the hash its receipt recorded.
 */
class BatchZipWriterTest {

    @Test
    void aTitleIsMadeSafeForAnyFilesystem() {
        // The exact name is less important than what cannot survive.
        String evil = BatchZipWriter.sanitizeComponent("../../evil");
        assertFalse(evil.contains("/"));
        assertFalse(evil.contains("\\"));
        assertFalse(evil.startsWith("."));
        assertEquals("a_b_c", BatchZipWriter.sanitizeComponent("a/b\\c"));
        assertEquals("CON", BatchZipWriter.sanitizeComponent("CON"));
        assertEquals("a_b", BatchZipWriter.sanitizeComponent("a\u0007b"));
        assertEquals("spaced out", BatchZipWriter.sanitizeComponent("  spaced   out  "));
        assertEquals("dots", BatchZipWriter.sanitizeComponent("...dots..."));
        assertEquals("document", BatchZipWriter.sanitizeComponent(""));
        assertEquals("document", BatchZipWriter.sanitizeComponent("   "));
        assertEquals(80, BatchZipWriter.sanitizeComponent("x".repeat(300)).length());
        assertEquals("document", BatchZipWriter.sanitizeComponent(null));
    }

    @Test
    void entryNamesAreUniqueAndCarryTheRowNumber() {
        Set<String> used = new LinkedHashSet<>();
        String first = BatchService.uniqueEntryName(used, 7, "../../evil", "pdf");
        assertTrue(first.startsWith("007 "));
        assertTrue(first.endsWith(".pdf"));
        assertFalse(first.contains("/"));
        assertFalse(first.contains("\\"));
        // Two rows, one title: the same sanitized component, never the same entry.
        String again = BatchService.uniqueEntryName(used, 7, "../../evil", "pdf");
        assertNotEquals(first, again);
        assertTrue(again.endsWith(".pdf"));
        assertEquals("1234 Fine.docx", BatchService.uniqueEntryName(used, 1234, "Fine", "docx"));
    }

    @Test
    void rowsCsvQuotesByRfc4180AndNeutralizesFormulaCells() {
        String csv = BatchZipWriter.rowsCsv(List.of(
                List.of("2", "Ana Lima", "EXPORTED", "002 Ana.pdf", "", "abc", "", ""),
                List.of("3", "=SUM(1,2)", "BLOCKED", "", "", "", "", "EMPTY_REQUIRED:name"),
                List.of("4", "+44", "FAILED", "", "", "", "", "a,b\"c"),
                List.of("5", "-5", "FAILED", "", "", "", "", "\ttabbed"),
                List.of("6", "@cmd", "FAILED", "", "", "", "", "plain")));
        String[] lines = csv.split("\r\n");
        assertEquals(BatchZipWriter.ROWS_CSV_HEADER, lines[0]);
        assertEquals("2,Ana Lima,EXPORTED,002 Ana.pdf,,abc,,", lines[1]);
        assertTrue(lines[2].contains("'=SUM(1,2)"));
        assertTrue(lines[3].contains("'+44"));
        assertTrue(lines[4].contains("'-5"));
        assertTrue(lines[4].contains("'\ttabbed"));
        assertTrue(lines[5].contains("'@cmd"));
        // The quoting itself: a comma- or quote-bearing cell is one field.
        assertTrue(lines[3].endsWith("\"a,b\"\"c\""));
    }

    @Test
    void everyCsvCellBeginningWithAFormulaCharacterIsPrefixed() {
        for (String hostile : List.of("=x", "+x", "-x", "@x", "\tx", "\rx")) {
            assertTrue(
                    BatchZipWriter.rowsCsv(List.of(List.of("1", hostile, "x", "", "", "", "", "")))
                            .contains("'" + hostile),
                    hostile);
        }
    }

    @Test
    void aWrittenArchiveCarriesEachVerifiedFileThenRowsCsv() throws IOException, NoSuchAlgorithmException {
        byte[] pdfBytes = "%PDF-real".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(pdfBytes);
        Map<Long, byte[]> store = Map.of(9L, pdfBytes);
        BatchExportZip zip = new BatchExportZip(
                "The batch",
                List.of(new BatchExportZip.Entry("002 Ana.pdf", 9L, hash)),
                List.of(List.of("2", "Ana", "EXPORTED", "002 Ana.pdf", "", "", hash, "")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BatchZipWriter.write(zip, store::get, out);

        List<String> names = new ArrayList<>();
        Map<String, byte[]> contents = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
                contents.put(entry.getName(), in.readAllBytes());
            }
        }
        assertEquals(List.of("002 Ana.pdf", "rows.csv"), names);
        assertArrayEquals(pdfBytes, contents.get("002 Ana.pdf"));
        assertTrue(new String(contents.get("rows.csv"), StandardCharsets.UTF_8)
                .startsWith(BatchZipWriter.ROWS_CSV_HEADER));
    }

    @Test
    void aTamperedBlobAbortsTheArchiveAndServesNothingOfIt() throws NoSuchAlgorithmException {
        byte[] honest = "honest bytes".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "tampered!!".getBytes(StandardCharsets.UTF_8);
        Map<Long, byte[]> store = Map.of(9L, tampered);
        BatchExportZip zip = new BatchExportZip(
                "The batch",
                List.of(new BatchExportZip.Entry("002 Ana.pdf", 9L, sha256(honest))),
                List.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BatchZipWriter.IntegrityMismatchException thrown = assertThrows(
                BatchZipWriter.IntegrityMismatchException.class,
                () -> BatchZipWriter.write(zip, store::get, out));
        assertTrue(thrown.getMessage().contains("9"));
        assertFalse(
                new String(out.toByteArray(), StandardCharsets.ISO_8859_1)
                        .contains(new String(tampered, StandardCharsets.ISO_8859_1)),
                "not one byte of the wrong file was served");
    }

    @Test
    void aFailedReadLeavesAnUnfinishedArchiveAndTheStreamOpen() throws NoSuchAlgorithmException {
        // The second entry's read dies inside the stream's own write path:
        // whatever the failure is, the archive must not be finished behind
        // it -- no central directory, no end record, and the stream itself
        // left open so the caller aborts the answer rather than ending it.
        byte[] honest = "honest bytes".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(honest);
        BatchExportZip zip = new BatchExportZip(
                "The batch",
                List.of(
                        new BatchExportZip.Entry("001 Ana.pdf", 9L, hash),
                        new BatchExportZip.Entry("002 Ben.pdf", 10L, hash)),
                List.of());
        CloseAwareStream out = new CloseAwareStream();
        assertThrows(
                BatchZipWriter.IntegrityMismatchException.class,
                () -> BatchZipWriter.write(zip, artifactId -> {
                    if (artifactId == 10L) {
                        throw new BatchZipWriter.IntegrityMismatchException(artifactId);
                    }
                    return honest;
                }, out));
        String served = new String(out.bytes(), StandardCharsets.ISO_8859_1);
        assertTrue(served.contains("PK\u0003\u0004"), "the first entry's local header was served");
        assertTrue(served.contains("001 Ana.pdf"));
        assertFalse(served.contains("PK\u0001\u0002"), "a finished archive's central directory was written");
        assertFalse(served.contains("PK\u0005\u0006"), "a finished archive's end record was written");
        assertFalse(out.closed, "the stream must be left open so the response aborts");
    }

    @Test
    void aDyingStreamLeavesNoCentralDirectoryBehindIt() throws NoSuchAlgorithmException {
        byte[] honest = "honest bytes".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(honest);
        BatchExportZip zip = new BatchExportZip(
                "The batch",
                List.of(new BatchExportZip.Entry("001 Ana.pdf", 9L, hash)),
                List.of());
        // The stream dies mid-entry, on its eighth write: nothing written
        // after the failure may reach it, so the bytes it took contain no
        // finished-archive marker anywhere.
        CloseAwareStream out = new CloseAwareStream().failingOnWrite(8);
        assertThrows(
                IllegalStateException.class,
                () -> BatchZipWriter.write(zip, artifactId -> honest, out));
        String served = new String(out.bytes(), StandardCharsets.ISO_8859_1);
        assertFalse(served.contains("PK\u0001\u0002"), "a finished archive's central directory was written");
        assertFalse(served.contains("PK\u0005\u0006"), "a finished archive's end record was written");
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** A stream that remembers whether it was closed, and can be told to die on a chosen write. */
    private static final class CloseAwareStream extends OutputStream {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int writesUntilFailure = -1;
        private boolean closed;

        CloseAwareStream failingOnWrite(int writeNumber) {
            writesUntilFailure = writeNumber;
            return this;
        }

        @Override
        public void write(int b) throws IOException {
            failIfItIsTime();
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            failIfItIsTime();
            bytes.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            bytes.close();
        }

        private void failIfItIsTime() {
            if (--writesUntilFailure == 0) {
                throw new IllegalStateException("the wire died");
            }
        }

        byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}
