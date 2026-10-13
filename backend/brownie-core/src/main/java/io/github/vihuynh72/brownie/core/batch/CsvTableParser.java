package io.github.vihuynh72.brownie.core.batch;

import io.github.vihuynh72.brownie.core.batch.TableUnreadableException.Reason;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a CSV into a {@link TableReading}, a record at a time so a file can
 * be refused as soon as it breaks a bound rather than after it is all in
 * memory. The parse is strict RFC 4180: a quote belongs at the start of a
 * field, doubles itself inside one, and only a delimiter, a line end, or the
 * end of the file may follow it; anything else, or a file that is not
 * well-formed UTF-8, means the file cannot be trusted to be what it looks
 * like. A field that starts with = or + stays text: a CSV is only ever what
 * it says, never a formula.
 */
public class CsvTableParser {

    public static final String READER_VERSION = "brownie-csv-v1";

    private static final char[] DELIMITERS = {',', ';', '\t'};

    public String readerVersion() {
        return READER_VERSION;
    }

    /**
     * Reads the stream as CSV under {@code limits}. The first non-blank
     * record fixes the record width; a later record may end early (padded
     * with blanks) but may not say more (trailing empty fields are dropped,
     * a non-empty one is {@link Reason#NOT_A_TABLE}).
     */
    public TableReading parse(InputStream content, TableReadRequest request, TableLimits limits) throws IOException {
        Cursor cursor = new Cursor(stripBom(content));
        TableRules.Collector collector =
                new TableRules.Collector(request.hasHeaderRow(), request.effectiveDateOrder(), limits.maxDataRows());
        Integer width = null;
        char delimiter = ',';
        int recordNumber = 0;
        String raw;
        while ((raw = cursor.readRawRecord()) != null) {
            recordNumber++;
            if (recordNumber == 1) {
                delimiter = delimiter(raw);
            }
            List<String> fields;
            if (width == null) {
                fields = split(raw, delimiter, recordNumber, limits);
                if (fields.stream().allMatch(String::isBlank)) {
                    collector.add(recordNumber, toCells(fields));
                    continue;
                }
                width = fields.size();
                if (width > limits.maxColumns()) {
                    throw new TableUnreadableException(Reason.TOO_MANY_COLUMNS,
                            "The file has " + width + " columns; a table may have at most " + limits.maxColumns() + ".",
                            recordNumber);
                }
            } else {
                fields = fit(split(raw, delimiter, recordNumber, limits), width, recordNumber);
            }
            collector.add(recordNumber, toCells(fields));
        }
        return TableRules.assemble(
                TableFormat.CSV, readerVersion(), List.of(), null, collector, limits);
    }

    /**
     * The delimiter the first record uses: the one that appears most outside
     * quotes among comma, semicolon and tab, ties -- including no delimiter
     * at all -- reading as comma.
     */
    private static char delimiter(String rawRecord) {
        int[] counts = new int[DELIMITERS.length];
        boolean quoted = false;
        for (int i = 0; i < rawRecord.length(); i++) {
            char c = rawRecord.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < rawRecord.length() && rawRecord.charAt(i + 1) == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted) {
                for (int d = 0; d < DELIMITERS.length; d++) {
                    if (c == DELIMITERS[d]) {
                        counts[d]++;
                    }
                }
            }
        }
        int best = 0;
        for (int d = 1; d < counts.length; d++) {
            if (counts[d] > counts[best]) {
                best = d;
            }
        }
        return DELIMITERS[best];
    }

    /**
     * Splits one record into fields. A quote inside an unquoted field, text
     * after a closing quote, or a quote left open at the record's end is
     * {@link Reason#NOT_A_TABLE} at that record's number.
     */
    private static List<String> split(String raw, char delimiter, int recordNumber, TableLimits limits) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;   // inside a quoted field
        boolean afterQuote = false; // closing quote seen; only delimiter/end may follow
        boolean fresh = true;     // nothing but the opening quote seen so far in this field
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                        checkLength(field, recordNumber, limits);
                    } else {
                        quoted = false;
                        afterQuote = true;
                    }
                } else {
                    field.append(c);
                    checkLength(field, recordNumber, limits);
                }
            } else if (c == delimiter) {
                fields.add(field.toString());
                field.setLength(0);
                afterQuote = false;
                fresh = true;
            } else if (c == '"') {
                if (!fresh || afterQuote) {
                    throw new TableUnreadableException(Reason.NOT_A_TABLE,
                            "Record " + recordNumber + " is not a table row: a quote can only open a field.",
                            recordNumber);
                }
                quoted = true;
                fresh = false;
            } else {
                if (afterQuote) {
                    throw new TableUnreadableException(Reason.NOT_A_TABLE,
                            "Record " + recordNumber + " is not a table row: nothing may follow a closing quote.",
                            recordNumber);
                }
                field.append(c);
                fresh = false;
                checkLength(field, recordNumber, limits);
            }
        }
        if (quoted) {
            throw new TableUnreadableException(Reason.NOT_A_TABLE,
                    "Record " + recordNumber + " is not a table row: a quoted field is left open.", recordNumber);
        }
        fields.add(field.toString());
        return fields;
    }

    /** A record narrower than the table is padded with blanks; wider is only allowed when the extra fields are all empty. */
    private static List<String> fit(List<String> fields, int width, int recordNumber) {
        if (fields.size() <= width) {
            return fields;
        }
        List<String> extra = fields.subList(width, fields.size());
        if (extra.stream().allMatch(String::isBlank)) {
            return new ArrayList<>(fields.subList(0, width));
        }
        throw new TableUnreadableException(Reason.NOT_A_TABLE,
                "Record " + recordNumber + " is not a table row: it holds more fields than the rest of the table.",
                recordNumber);
    }

    private static void checkLength(StringBuilder field, int recordNumber, TableLimits limits) {
        if (field.length() > limits.maxCellChars()) {
            throw new TableUnreadableException(Reason.CELL_TOO_LONG,
                    "A cell in record " + recordNumber + " holds more than " + limits.maxCellChars() + " characters.",
                    recordNumber);
        }
    }

    private static List<TableCell> toCells(List<String> fields) {
        return fields.stream().map(field -> (TableCell) new TableCell(field, null, null)).toList();
    }

    /** Eats a UTF-8 byte-order mark when one is there, so it is not read as the start of a name. */
    private static InputStream stripBom(InputStream content) throws IOException {
        PushbackInputStream stream = new PushbackInputStream(content, 3);
        byte[] bom = new byte[3];
        int read = stream.read(bom);
        if (read == 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
            return stream;
        }
        stream.unread(bom, 0, Math.max(read, 0));
        return stream;
    }

    /**
     * One record's raw text at a time: characters up to an unquoted line end
     * (CR, LF or CRLF all ending a record; a quoted one staying inside its
     * field). Reads a strict UTF-8 stream -- malformed input is
     * {@link Reason#NOT_UTF8}, never a substitution character.
     */
    private static final class Cursor {

        private final Reader reader;
        private int pushed = -2;

        Cursor(InputStream content) {
            this.reader = new InputStreamReader(content, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT));
        }

        /** The record's raw text, or null at the end of the file. */
        String readRawRecord() throws IOException {
            StringBuilder raw = new StringBuilder();
            boolean quoted = false;
            int c = next();
            if (c < 0) {
                return null;
            }
            while (c >= 0) {
                if (c == '"') {
                    raw.append('"');
                    quoted = !quoted;
                } else if ((c == '\r' || c == '\n') && !quoted) {
                    if (c == '\r') {
                        int look = next();
                        if (look != '\n' && look >= 0) {
                            push(look);
                        }
                    }
                    return raw.toString();
                } else {
                    raw.append((char) c);
                }
                c = next();
            }
            return raw.toString();
        }

        private int next() throws IOException {
            if (pushed >= 0) {
                int c = pushed;
                pushed = -2;
                return c;
            }
            try {
                return reader.read();
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new TableUnreadableException(Reason.NOT_UTF8,
                        "The file is not UTF-8 text; save it as CSV UTF-8 and try again.");
            }
        }

        private void push(int c) {
            pushed = c;
        }
    }
}
