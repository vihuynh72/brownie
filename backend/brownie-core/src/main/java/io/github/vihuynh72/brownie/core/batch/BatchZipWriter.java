package io.github.vihuynh72.brownie.core.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams a batch export's archive. Nothing is buffered: each file is
 * read whole (it must be -- its SHA-256 has to be proven before a byte of
 * it is written), checked against the receipt's hash, and only then put
 * on the wire. A file whose bytes no longer match what was approved is
 * never served: the write stops and the stream is left unfinished --
 * no central directory, no end record, the stream itself unclosed --
 * so the caller's answer aborts instead of ending on a partial archive
 * that still opens like a whole one.
 *
 * <p>The trailing {@code rows.csv} is written after every file entry;
 * every cell that could open as a spreadsheet formula is prefixed with
 * an apostrophe first, since a file meant to be opened in a spreadsheet
 * app must not carry executable-looking text out of it.
 */
public final class BatchZipWriter {

    /** The exact header the run's {@code rows.csv} always opens with. */
    public static final String ROWS_CSV_HEADER =
            "Row,Document,Outcome,Word file,PDF file,Word SHA-256,PDF SHA-256,Detail";
    public static final String ROWS_CSV_NAME = "rows.csv";

    private static final Logger log = LoggerFactory.getLogger(BatchZipWriter.class);
    private static final int MAX_TITLE_CHARS = 80;
    private static final String FALLBACK_TITLE = "document";

    /** Where the archive's bytes come from: one artifact id to its content. */
    @FunctionalInterface
    public interface ArtifactBytesReader {
        byte[] read(long artifactId) throws IOException;
    }

    /** Bytes that did not match the hash the export's receipt promised them. */
    public static final class IntegrityMismatchException extends IOException {

        IntegrityMismatchException(long artifactId) {
            super("Artifact " + artifactId + " no longer matches the hash its export receipt recorded.");
        }
    }

    private BatchZipWriter() {
    }

    public static void write(BatchExportZip zip, ArtifactBytesReader reader, OutputStream out) throws IOException {
        SealableOutputStream sealable = new SealableOutputStream(out);
        ZipOutputStream zipOut = new ZipOutputStream(sealable, StandardCharsets.UTF_8);
        try {
            for (BatchExportZip.Entry entry : zip.entries()) {
                byte[] bytes = reader.read(entry.artifactId());
                String actual = sha256Hex(bytes);
                if (!actual.equals(entry.expectedSha256())) {
                    log.error(
                            "Refusing to serve artifact {} in a batch export: its stored bytes no longer match the hash "
                                    + "the export receipt recorded (expected {}, found {}). The archive is aborted.",
                            entry.artifactId(), entry.expectedSha256(), actual);
                    throw new IntegrityMismatchException(entry.artifactId());
                }
                zipOut.putNextEntry(new ZipEntry(entry.name()));
                zipOut.write(bytes);
                zipOut.closeEntry();
            }
            zipOut.putNextEntry(new ZipEntry(ROWS_CSV_NAME));
            zipOut.write(rowsCsv(zip.csvRows()).getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();
        } catch (IOException | RuntimeException failure) {
            // Nothing may follow a failed write: sealing the stream drops
            // the central directory the ZipOutputStream's own close would
            // append, and leaves out itself open, so the container aborts
            // the response instead of ending it cleanly on a partial
            // archive. The close still runs -- it frees the Deflater --
            // and the failure that caused all this is the one thrown.
            sealable.seal();
            try {
                zipOut.close();
            } catch (IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        zipOut.close();
    }

    /**
     * A breakable pipe in front of the real stream: once sealed, writes
     * and closes go nowhere, so a closing {@code ZipOutputStream} cannot
     * append a finished archive's trailing records or end the response.
     */
    private static final class SealableOutputStream extends OutputStream {

        private final OutputStream delegate;
        private boolean sealed;

        SealableOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        void seal() {
            sealed = true;
        }

        @Override
        public void write(int b) throws IOException {
            if (!sealed) {
                delegate.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (!sealed) {
                delegate.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            if (!sealed) {
                delegate.flush();
            }
        }

        @Override
        public void close() throws IOException {
            if (!sealed) {
                delegate.close();
            }
        }
    }

    /**
     * One CSV file: the fixed header, then one line per item. Every cell
     * that begins with a character a spreadsheet would open as a formula
     * -- {@code =}, {@code +}, {@code -}, {@code @}, TAB or CR -- is
     * prefixed with an apostrophe first, and every cell is RFC 4180
     * quoted when it needs it.
     */
    static String rowsCsv(List<List<String>> rows) {
        StringBuilder csv = new StringBuilder(ROWS_CSV_HEADER).append("\r\n");
        for (List<String> row : rows) {
            boolean first = true;
            for (String cell : row) {
                if (!first) {
                    csv.append(',');
                }
                csv.append(quoteCsvCell(neutralizeCsvCell(cell)));
                first = false;
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    private static String neutralizeCsvCell(String cell) {
        if (cell.isEmpty()) {
            return cell;
        }
        char first = cell.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + cell;
        }
        return cell;
    }

    private static String quoteCsvCell(String cell) {
        if (cell.indexOf(',') < 0 && cell.indexOf('"') < 0 && cell.indexOf('\r') < 0 && cell.indexOf('\n') < 0) {
            return cell;
        }
        return '"' + cell.replace("\"", "\"\"") + '"';
    }

    /**
     * A document title made into a filename that is safe anywhere a ZIP
     * is opened: NFC, path and shell metacharacters and every control
     * character replaced with an underscore, whitespace collapsed, edge
     * dots and spaces stripped, and at most {@value #MAX_TITLE_CHARS}
     * characters. Windows' reserved device names cannot appear because an
     * entry name always prefixes the row number -- but the title itself
     * is still cleaned so it never hides something else in it.
     */
    public static String sanitizeComponent(String title) {
        String source = title == null ? "" : Normalizer.normalize(title, Normalizer.Form.NFC);
        StringBuilder cleaned = new StringBuilder(source.length());
        for (int i = 0; i < source.length() && cleaned.length() < MAX_TITLE_CHARS; i++) {
            char c = source.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') {
                cleaned.append('_');
            } else {
                cleaned.append(c);
            }
        }
        String collapsed = cleaned.toString().replaceAll("\\s+", " ");
        // Edge dots go first so a run of them is stripped, not kept as
        // underscores; then nothing that reads as a parent-directory hop
        // may survive either -- each remaining pair simply becomes one
        // underscore until none are left.
        String stripped = stripEdge(collapsed);
        while (stripped.contains("..")) {
            stripped = stripped.replace("..", "_");
        }
        stripped = stripEdge(stripped);
        if (stripped.length() > MAX_TITLE_CHARS) {
            stripped = stripEdge(stripped.substring(0, MAX_TITLE_CHARS));
        }
        return stripped.isEmpty() ? FALLBACK_TITLE : stripped;
    }

    private static String stripEdge(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && (text.charAt(start) == ' ' || text.charAt(start) == '.')) {
            start++;
        }
        while (end > start && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '.')) {
            end--;
        }
        return text.substring(start, end);
    }

    /** The archive's own filename: the batch's title sanitized the same way an entry is, with {@code .zip}. */
    public static String zipFilename(String batchTitle) {
        return sanitizeComponent(batchTitle) + ".zip";
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
