package io.github.vihuynh72.brownie.core.batch;

import io.github.vihuynh72.brownie.core.batch.TableUnreadableException.Reason;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The strict-CSV reading: delimiter detection, quoting, the bounds, and
 * every way a file can fail to be a table. The cells a CSV produces carry
 * only display text; dates and column types are the shared rules' work,
 * so their assertions here go through the assembled reading.
 */
class CsvTableParserTest {

    private final CsvTableParser parser = new CsvTableParser();
    private final TableLimits limits = TableLimits.ofRows(10_000);
    private final TableReadRequest noHeader = new TableReadRequest(null, false, null);
    private final TableReadRequest auto = new TableReadRequest(null, null, null);

    @Test
    void commaSemicolonAndTabAreEachDetectedFromTheFirstRecord() throws IOException {
        for (String delimiter : List.of(",", ";", "\t")) {
            TableReading table = parser.parse(stream("a" + delimiter + "b\n1" + delimiter + "2\n"), auto, limits);
            assertTrue(table.hasHeaderRow());
            assertEquals(List.of("a", "b"), names(table));
            assertEquals("1", cell(table, 2, 0).display());
            assertEquals("2", cell(table, 2, 1).display());
        }
        // Detection belongs to the first record alone: a later record's
        // semicolons are cell text, not a change of mind.
        TableReading table = parser.parse(stream("a,b\nc;d\n"), noHeader, limits);
        assertEquals("c;d", cell(table, 2, 0).display());
    }

    @Test
    void quotingHoldsDelimitersNewlinesAndDoubledQuotes() throws IOException {
        TableReading table = parser.parse(stream("name,note\n\"Ana, Jr\",\"said \"\"hi\"\"\nover two lines\"\n"), noHeader, limits);
        assertEquals(2, table.rows().size());
        assertEquals(1, table.rows().get(0).rowNumber());
        // The multi-line record is still the file's second row.
        assertEquals(2, table.rows().get(1).rowNumber());
        assertEquals("Ana, Jr", cell(table, 2, 0).display());
        assertEquals("said \"hi\"\nover two lines", cell(table, 2, 1).display());
    }

    @Test
    void theByteOrderMarkIsEatenAndCrlfLfAndCrAllEndARecord() throws IOException {
        byte[] bom = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        TableReading withMark = parser.parse(stream(bom), auto, limits);
        assertEquals(List.of("a", "b"), names(withMark));

        for (String ending : List.of("\r\n", "\n", "\r")) {
            TableReading table = parser.parse(stream("a,b" + ending + "1,2" + ending), noHeader, limits);
            assertEquals(2, table.rows().size(), "ending " + ending);
            assertEquals("1", cell(table, 2, 0).display());
        }
    }

    @Test
    void aQuoteMisplacedIsNotATableAtTheRowThatHeldIt() {
        // Unclosed quote: the record runs to the file's end and never closes.
        TableUnreadableException unclosed = assertThrows(TableUnreadableException.class,
                () -> parser.parse(stream("a,b\n1,\"two\n"), noHeader, limits));
        assertEquals(Reason.NOT_A_TABLE, unclosed.reason());
        assertEquals(2, unclosed.rowNumber());

        // A quote that is not a field's first character.
        TableUnreadableException inside = assertThrows(TableUnreadableException.class,
                () -> parser.parse(stream("a,b\n1,tw\"o\n"), noHeader, limits));
        assertEquals(Reason.NOT_A_TABLE, inside.reason());
        assertEquals(2, inside.rowNumber());

        // Something after a closing quote that is not the delimiter or the record's end.
        TableUnreadableException after = assertThrows(TableUnreadableException.class,
                () -> parser.parse(stream("a,b\n1,\"two\"x\n"), noHeader, limits));
        assertEquals(Reason.NOT_A_TABLE, after.reason());
        assertEquals(2, after.rowNumber());
    }

    @Test
    void aShortRowIsPaddedAndAnExtraCellMustBeEmpty() throws IOException {
        TableReading padded = parser.parse(stream("a,b,c\n1\n2,3,4\n"), noHeader, limits);
        assertEquals(3, padded.columns().size());
        assertTrue(cell(padded, 2, 1).isBlank());
        assertTrue(cell(padded, 2, 2).isBlank());

        // Trailing separators alone add no columns.
        TableReading dropped = parser.parse(stream("a,b\n1,2,,\n"), noHeader, limits);
        assertEquals(2, dropped.columns().size());

        TableUnreadableException extra = assertThrows(TableUnreadableException.class,
                () -> parser.parse(stream("a,b\n1,2,3\n"), noHeader, limits));
        assertEquals(Reason.NOT_A_TABLE, extra.reason());
        assertEquals(2, extra.rowNumber());
    }

    @Test
    void badUtf8IsRefusedWhetherTheWholeFileOrOneByteOfItIsBad() {
        byte[] latin1Only = "a,b\ncaf\u00e9,1\n".getBytes(java.nio.charset.Charset.forName("ISO-8859-1"));
        assertEquals(Reason.NOT_UTF8, assertThrows(TableUnreadableException.class,
                () -> parser.parse(stream(latin1Only), noHeader, limits)).reason());

        // One Latin-1 byte in an otherwise valid UTF-8 file still fails.
        byte[] mixed = concat("a,b\nok,1\ncaf".getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xE9});
        assertEquals(Reason.NOT_UTF8, assertThrows(TableUnreadableException.class,
                () -> parser.parse(stream(mixed), noHeader, limits)).reason());
    }

    @Test
    void theRowLimitStopsTheReadRatherThanReadingEverything() throws IOException {
        TableLimits small = TableLimits.ofRows(3);
        AtomicLong read = new AtomicLong();
        // Large enough that the decoder's buffer cannot have held it all.
        StringBuilder file = new StringBuilder("a,b\n");
        for (int i = 0; i < 5000; i++) {
            file.append(i).append(',').append(i).append('\n');
        }
        byte[] bytes = file.toString().getBytes(StandardCharsets.UTF_8);
        TableUnreadableException e = assertThrows(TableUnreadableException.class,
                () -> parser.parse(counting(stream(bytes), read), noHeader, small));
        assertEquals(Reason.TOO_MANY_ROWS, e.reason());
        assertEquals(4, e.rowNumber()); // the fourth record is the one past the limit
        assertTrue(read.get() < bytes.length / 2, "read " + read.get() + " of " + bytes.length + " bytes");
    }

    @Test
    void aRecordWiderThanTheColumnBoundOrACellLongerThanTheBoundIsRefused() {
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i <= TableLimits.MAX_COLUMNS; i++) {
            wide.append('c').append(i).append(i < TableLimits.MAX_COLUMNS ? ',' : '\n');
        }
        wide.append("x\n");
        assertEquals(Reason.TOO_MANY_COLUMNS, assertThrows(TableUnreadableException.class,
                () -> parser.parse(stream(wide.toString()), noHeader, limits)).reason());

        String longCell = "a\n" + "x".repeat(TableLimits.MAX_CELL_CHARS + 1) + "\n";
        TableUnreadableException cell = assertThrows(TableUnreadableException.class,
                () -> parser.parse(stream(longCell), noHeader, limits));
        assertEquals(Reason.CELL_TOO_LONG, cell.reason());
        assertEquals(2, cell.rowNumber());
    }

    @Test
    void aFileOfNothingOrOnlyAHeaderIsEmpty() {
        assertEquals(Reason.EMPTY, assertThrows(TableUnreadableException.class,
                () -> parser.parse(stream(""), noHeader, limits)).reason());
        assertEquals(Reason.EMPTY, assertThrows(TableUnreadableException.class,
                () -> parser.parse(stream("a,b\n"), auto, limits)).reason());
    }

    @Test
    void textThatLooksLikeAFormulaIsKeptVerbatim() throws IOException {
        TableReading table = parser.parse(stream(
                "n,t\n\"=SUM(1,2)\",x\n\"+42\",x\n\"-3\",x\n\"@h\",x\n\"\t=1\",x\n"), noHeader, limits);
        assertEquals("=SUM(1,2)", cell(table, 2, 0).display());
        assertEquals("+42", cell(table, 3, 0).display());
        assertEquals("-3", cell(table, 4, 0).display());
        assertEquals("@h", cell(table, 5, 0).display());
        assertEquals("\t=1", cell(table, 6, 0).display());
    }

    @Test
    void blankAndDuplicateRowsAreCountedAndNoted() throws IOException {
        TableReading table = parser.parse(stream("a,b\n1,2\n\n1,2\n3,4\n"), auto, limits);
        assertEquals(4, table.rows().size());
        assertTrue(table.rows().get(1).blank());
        assertTrue(table.notices().stream().anyMatch(n -> n.code().equals(TableNotice.BLANK_ROWS) && n.rows().equals(List.of(3))));
        assertTrue(table.notices().stream().anyMatch(n -> n.code().equals(TableNotice.DUPLICATE_ROWS) && n.rows().containsAll(List.of(2, 4))));
        assertEquals(3, table.nonBlankRowCount());
    }

    @Test
    void blankLinesAtTheFilesEndAreNothingNotRows() throws IOException {
        TableReading table = parser.parse(stream("name\nAna\nBo\n\n\n\n"), auto, limits);
        assertEquals(2, table.rows().size());
        assertTrue(table.rows().stream().noneMatch(TableRow::blank));
        assertFalse(table.notices().stream().anyMatch(n -> n.code().equals(TableNotice.BLANK_ROWS)));
    }

    @Test
    void blankRowsBetweenDataRowsStayRowsButDoNotCountAgainstTheLimit() throws IOException {
        StringBuilder file = new StringBuilder("name\n");
        for (int i = 0; i < 500; i++) {
            file.append("p").append(i).append('\n');
            if (i == 10 || i == 200 || i == 400) {
                file.append('\n');
            }
        }
        TableReading table = parser.parse(stream(file.toString()), auto, TableLimits.ofRows(500));
        assertEquals(503, table.rows().size());
        assertEquals(3, table.rows().stream().filter(TableRow::blank).count());
    }

    @Test
    void blankLinesAfterTheLastDataRowAreDroppedHoweverManyThereAre() throws IOException {
        StringBuilder file = new StringBuilder("name\n");
        for (int i = 0; i < 40; i++) {
            file.append("p").append(i).append('\n');
        }
        file.append("\n".repeat(1000));
        TableReading table = parser.parse(stream(file.toString()), auto, TableLimits.ofRows(500));
        assertEquals(40, table.rows().size());
        assertTrue(table.rows().stream().noneMatch(TableRow::blank));
    }

    @Test
    void aGapOfBlankLinesBetweenDataRowsIsStillRefused() {
        StringBuilder file = new StringBuilder("name\nx\n");
        file.append("\n".repeat(501));
        file.append("y\n");
        TableUnreadableException tooMany = assertThrows(TableUnreadableException.class,
                () -> parser.parse(stream(file.toString()), auto, TableLimits.ofRows(500)));
        assertEquals(Reason.TOO_MANY_ROWS, tooMany.reason());
    }

    @Test
    void theHeaderIsDetectedOrTakenAsDeclaredAndDatesParseUnderTheOrderGiven() throws IOException {
        TableReading detected = parser.parse(stream("Name,Joined\nAna,2025-01-05\nBo,\"March 4, 2025\"\nCy,03/06/2025\n"), auto, limits);
        assertTrue(detected.hasHeaderRow());
        assertEquals(List.of("Name", "Joined"), names(detected));
        assertEquals(LocalDate.of(2025, 1, 5), cell(detected, 2, 1).date());
        assertEquals(LocalDate.of(2025, 3, 4), cell(detected, 3, 1).date());
        // 03/06 reads month-first by default and was ambiguous.
        assertEquals(LocalDate.of(2025, 3, 6), cell(detected, 4, 1).date());
        assertTrue(detected.ambiguousDates());
        assertEquals(ColumnType.DATE, detected.columns().get(1).inferredType());

        TableReading dayFirst = parser.parse(stream("d\n03/06/2025\n"),
                new TableReadRequest(null, false, DateOrder.DMY), limits);
        assertEquals(LocalDate.of(2025, 6, 3), cell(dayFirst, 2, 0).date());

        // September's four-letter abbreviation reads like the rest.
        TableReading sept = parser.parse(stream("d\n\"Sept 3, 2025\"\n3 sept. 2025\n"), noHeader, limits);
        assertEquals(LocalDate.of(2025, 9, 3), cell(sept, 2, 0).date());
        assertEquals(LocalDate.of(2025, 9, 3), cell(sept, 3, 0).date());

        // An impossible date and a two-digit year are not dates at all.
        TableReading notDates = parser.parse(stream("d\n13/40/2025\n01/02/25\n"), noHeader, limits);
        assertNull(cell(notDates, 2, 0).date());
        assertNull(cell(notDates, 3, 0).date());

        // A declared header is used even when the row would not have been detected as one.
        TableReading declared = parser.parse(stream("1,2\n3,4\n"),
                new TableReadRequest(null, true, null), limits);
        assertTrue(declared.hasHeaderRow());
        assertEquals(1, declared.rows().size());
    }

    @Test
    void columnsAreTypedFromWhatTheirCellsHold() throws IOException {
        TableReading table = parser.parse(stream(
                "a,b,c,d,e\nhello,2025-01-05,1,\"1,250.00\",\nworld,2025-02-06,2,3,\n"), auto, limits);
        assertEquals(ColumnType.TEXT, table.columns().get(0).inferredType());
        assertEquals(ColumnType.DATE, table.columns().get(1).inferredType());
        assertEquals(ColumnType.NUMBER, table.columns().get(2).inferredType());
        assertEquals(ColumnType.NUMBER, table.columns().get(3).inferredType());
        assertEquals(ColumnType.EMPTY, table.columns().get(4).inferredType());
        assertEquals(2, table.columns().get(4).blankCount());
    }

    // ---- helpers ----

    private static InputStream stream(String text) {
        return stream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static InputStream counting(InputStream in, AtomicLong read) {
        return new FilterInputStream(in) {
            @Override
            public int read() throws IOException {
                int c = super.read();
                if (c >= 0) {
                    read.incrementAndGet();
                }
                return c;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    read.addAndGet(n);
                }
                return n;
            }
        };
    }

    private static List<String> names(TableReading table) {
        return table.columns().stream().map(TableColumn::name).toList();
    }

    private static TableCell cell(TableReading table, int rowNumber, int column) {
        return table.rows().stream()
                .filter(row -> row.rowNumber() == rowNumber)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + rowNumber))
                .cells()
                .get(column);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        return both;
    }
}
