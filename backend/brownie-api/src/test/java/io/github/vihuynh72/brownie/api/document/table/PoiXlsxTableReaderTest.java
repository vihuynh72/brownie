package io.github.vihuynh72.brownie.api.document.table;

import io.github.vihuynh72.brownie.core.batch.CellProblem;
import io.github.vihuynh72.brownie.core.batch.ColumnType;
import io.github.vihuynh72.brownie.core.batch.TableCell;
import io.github.vihuynh72.brownie.core.batch.TableLimits;
import io.github.vihuynh72.brownie.core.batch.TableNotice;
import io.github.vihuynh72.brownie.core.batch.TableReadRequest;
import io.github.vihuynh72.brownie.core.batch.TableReading;
import io.github.vihuynh72.brownie.core.batch.TableUnreadableException;
import io.github.vihuynh72.brownie.core.batch.TableUnreadableException.Reason;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workbook reader end to end: real files built by POI's document model
 * for everything a spreadsheet program would write, and hand-built packages
 * for what it would not (inline strings, a sheet that is not XML). Every
 * read goes through {@link PoiXlsxTableReader} on the saved bytes.
 */
class PoiXlsxTableReaderTest {

    private final PoiXlsxTableReader reader = new PoiXlsxTableReader();
    private final TableLimits limits = TableLimits.ofRows(10_000);
    private final TableReadRequest auto = new TableReadRequest(null, null, null);

    @Test
    void sharedStringsNumbersAndDatesReadAsADisplaysTable() throws IOException {
        byte[] file = workbook(wb -> {
            Sheet sheet = wb.createSheet("Members");
            CellStyle dateStyle = wb.createCellStyle();
            DataFormat formats = wb.createDataFormat();
            dateStyle.setDataFormat(formats.getFormat("yyyy-mm-dd"));
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Joined");
            header.createCell(2).setCellValue("Score");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Ana");
            Cell date = row.createCell(1);
            date.setCellValue(LocalDate.of(2025, 1, 5));
            date.setCellStyle(dateStyle);
            Cell score = row.createCell(2);
            score.setCellValue(1250);
            CellStyle number = wb.createCellStyle();
            number.setDataFormat(formats.getFormat("#,##0.00"));
            score.setCellStyle(number);
        });
        TableReading table = reader.read(new ByteArrayInputStream(file), auto, limits);
        assertEquals("Members", table.sheetName());
        assertTrue(table.hasHeaderRow());
        assertEquals(List.of("Name", "Joined", "Score"), names(table));
        assertEquals("Ana", cell(table, 2, 0).display());
        assertEquals(LocalDate.of(2025, 1, 5), cell(table, 2, 1).date());
        assertEquals("1,250.00", cell(table, 2, 2).display());
        assertEquals(ColumnType.NUMBER, table.columns().get(2).inferredType());
    }

    @Test
    void inlineStringsAndAnUntypedSheetReadToo() throws IOException {
        TableReading table = reader.read(new ByteArrayInputStream(handBuilt(
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Name</t></is></c>"
                        + "<c r=\"B1\" t=\"inlineStr\"><is><t>Club</t></is></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Ana</t></is></c>"
                        + "<c r=\"B2\" t=\"str\"><v>Harbour</v></c></row>")),
                auto, limits);
        assertTrue(table.hasHeaderRow());
        assertEquals("Ana", cell(table, 2, 0).display());
        assertEquals("Harbour", cell(table, 2, 1).display());
    }

    @Test
    void theNineteenFourDateSystemReadsTheSameSerialDifferently() throws IOException {
        // One serial number, two workbooks: the reader must take the
        // workbook's date system, agreeing with DateUtil's own reading.
        double serial = 45185;
        List<LocalDate> read = new java.util.ArrayList<>();
        for (boolean date1904 : List.of(false, true)) {
            byte[] file = workbook(wb -> {
                wb.getCTWorkbook().getWorkbookPr().setDate1904(date1904);
                Sheet sheet = wb.createSheet();
                CellStyle dateStyle = wb.createCellStyle();
                dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
                sheet.createRow(0).createCell(0).setCellValue("Day");
                Cell cell = sheet.createRow(1).createCell(0);
                cell.setCellValue(serial);
                cell.setCellStyle(dateStyle);
            });
            TableReading table = reader.read(new ByteArrayInputStream(file), auto, limits);
            assertTrue(wb1904Flag(table, file, date1904));
            LocalDate expected = org.apache.poi.ss.usermodel.DateUtil.getLocalDateTime(serial, date1904).toLocalDate();
            assertEquals(expected, cell(table, 2, 0).date(), "date1904=" + date1904);
            read.add(cell(table, 2, 0).date());
        }
        assertFalse(read.get(0).equals(read.get(1)), "the two date systems must disagree");
    }

    private static boolean wb1904Flag(TableReading table, byte[] file, boolean wanted) throws IOException {
        // Sanity: the fixture really carried the flag the case intends.
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            return wb.isDate1904() == wanted;
        }
    }

    @Test
    void formulasGiveTheirCachedAnswerOrAProblemNeverAnEvaluation() throws IOException {
        byte[] file = workbook(wb -> {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("Value");
            Cell cached = sheet.createRow(1).createCell(0);
            cached.setCellFormula("1+2");
            cached.setCellValue(3);
            sheet.createRow(2).createCell(0).setCellFormula("SUM(A1:A2)");
            sheet.createRow(3).createCell(0).setCellErrorValue(FormulaError.NAME.getCode());
        });
        TableReading table = reader.read(new ByteArrayInputStream(file), auto, limits);
        // The stored answer is formatted, not evaluated.
        assertEquals("3", cell(table, 2, 0).display());
        assertNull(cell(table, 2, 0).problem());
        assertEquals(CellProblem.FORMULA_WITHOUT_RESULT, cell(table, 3, 0).problem());
        assertEquals("", cell(table, 3, 0).display());
        assertEquals(CellProblem.ERROR_VALUE, cell(table, 4, 0).problem());
        assertTrue(table.notices().stream().anyMatch(n -> n.code().equals(TableNotice.FORMULAS_WITHOUT_RESULTS)));
        assertTrue(table.notices().stream().anyMatch(n -> n.code().equals(TableNotice.ERROR_VALUES)));
    }

    @Test
    void theFirstVisibleSheetIsDefaultAndHiddenOnesAreListed() throws IOException {
        byte[] file = workbook(wb -> {
            Sheet hidden = wb.createSheet("Hidden");
            hidden.createRow(0).createCell(0).setCellValue("secret");
            hidden.createRow(1).createCell(0).setCellValue("kept out of sight");
            wb.setSheetVisibility(0, SheetVisibility.HIDDEN);
            Sheet shown = wb.createSheet("Shown");
            shown.createRow(0).createCell(0).setCellValue("name");
            shown.createRow(1).createCell(0).setCellValue("Ana");
            Sheet last = wb.createSheet("Last");
            last.createRow(0).createCell(0).setCellValue("other");
            last.createRow(1).createCell(0).setCellValue("Bo");
        });
        TableReading table = reader.read(new ByteArrayInputStream(file), auto, limits);
        // The hidden sheet is listed -- a person may still pick it by name -- but never chosen.
        assertEquals(List.of("Hidden", "Shown", "Last"), table.sheetNames());
        assertEquals("Shown", table.sheetName());
        assertEquals("Ana", cell(table, 2, 0).display());

        TableReading chosen = reader.read(new ByteArrayInputStream(file),
                new TableReadRequest("Last", null, null), limits);
        assertEquals("Last", chosen.sheetName());
        assertEquals("Bo", cell(chosen, 2, 0).display());

        TableReading hidden = reader.read(new ByteArrayInputStream(file),
                new TableReadRequest("Hidden", null, null), limits);
        assertEquals("Hidden", hidden.sheetName());

        TableUnreadableException unknown = assertThrows(TableUnreadableException.class,
                () -> reader.read(new ByteArrayInputStream(file), new TableReadRequest("Nope", null, null), limits));
        assertEquals(Reason.SHEET_NOT_FOUND, unknown.reason());
    }

    @Test
    void mergedCellsGiveTheirValueToTheAnchorOnly() throws IOException {
        byte[] file = workbook(wb -> {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("name");
            sheet.getRow(0).createCell(1).setCellValue("value");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Merged");
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 1));
        });
        TableReading table = reader.read(new ByteArrayInputStream(file), auto, limits);
        assertEquals("Merged", cell(table, 2, 0).display());
        assertTrue(cell(table, 2, 1).isBlank());
    }

    @Test
    void cellsAbsentFromTheSheetKeepTheFollowingCellsInTheirOwnColumns() throws IOException {
        // A row that names A2 and C2 but not B2 must read as [value, blank,
        // value], not [value, value] -- an absent cell is a column of
        // nothing, not a missing one.
        TableReading table = reader.read(new ByteArrayInputStream(handBuilt(
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>first</t></is></c>"
                        + "<c r=\"B1\" t=\"inlineStr\"><is><t>second</t></is></c>"
                        + "<c r=\"C1\" t=\"inlineStr\"><is><t>third</t></is></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Ana</t></is></c>"
                        + "<c r=\"C2\" t=\"inlineStr\"><is><t>hers</t></is></c></row>")),
                auto, limits);
        assertEquals("Ana", cell(table, 2, 0).display());
        assertTrue(cell(table, 2, 1).isBlank());
        assertEquals("hers", cell(table, 2, 2).display());
    }

    @Test
    void mergedCellsAbsentFromTheSheetStayCoveredWithoutShiftingTheRest() throws IOException {
        TableReading table = reader.read(new ByteArrayInputStream(handBuilt(
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>first</t></is></c>"
                        + "<c r=\"B1\" t=\"inlineStr\"><is><t>second</t></is></c>"
                        + "<c r=\"C1\" t=\"inlineStr\"><is><t>third</t></is></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Merged</t></is></c>"
                        + "<c r=\"C2\" t=\"inlineStr\"><is><t>own</t></is></c></row>",
                "<mergeCells count=\"1\"><mergeCell ref=\"A2:B2\"/></mergeCells>")),
                auto, limits);
        assertEquals("Merged", cell(table, 2, 0).display());
        assertTrue(cell(table, 2, 1).isBlank());
        assertEquals("own", cell(table, 2, 2).display());
    }

    @Test
    void aStyledEmptyCellIsNoColumnNoProblemAndNoRowWidening() throws IOException {
        // A <c> with a style but no value, no formula and no inline text is
        // formatting noise; even far past the column bound it is nothing.
        TableReading table = reader.read(new ByteArrayInputStream(handBuilt(
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>name</t></is></c><c r=\"EW1\" s=\"5\"/></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Ana</t></is></c><c r=\"EW2\" s=\"5\"/></row>")),
                auto, limits);
        assertEquals(List.of("name"), names(table));
        assertEquals(1, table.rows().get(0).cells().size());
        assertEquals("Ana", cell(table, 2, 0).display());

        // A cell that does hold a value past the bound still refuses.
        byte[] tooWide = handBuilt(
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>n</t></is></c>"
                        + "<c r=\"CW1\" t=\"inlineStr\"><is><t>x</t></is></c></row>");
        assertEquals(Reason.TOO_MANY_COLUMNS, assertThrows(TableUnreadableException.class,
                () -> reader.read(new ByteArrayInputStream(tooWide), auto, limits)).reason());
    }

    @Test
    void aFormulasEmptyStoredResultIsABlankCellNotAProblem() throws IOException {
        // <v></v> is the stored answer to =IF(A1="","",A1): present and empty.
        TableReading table = reader.read(new ByteArrayInputStream(handBuilt(
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>v</t></is></c>"
                        + "<c r=\"B1\" t=\"inlineStr\"><is><t>w</t></is></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"str\"><f>IF(A1=&quot;&quot;,&quot;&quot;,A1)</f><v></v></c>"
                        + "<c r=\"B2\" t=\"str\"><f>UNKNOWN()</f></c></row>")),
                auto, limits);
        assertTrue(cell(table, 2, 0).isBlank());
        assertNull(cell(table, 2, 0).problem());
        assertEquals(CellProblem.FORMULA_WITHOUT_RESULT, cell(table, 2, 1).problem());
    }

    @Test
    void styledEmptyRowsAfterTheDataAreNotRows() throws IOException {
        StringBuilder rows = new StringBuilder(
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>name</t></is></c></row>");
        for (int i = 2; i <= 41; i++) {
            rows.append("<row r=\"").append(i).append("\"><c r=\"A").append(i)
                    .append("\" t=\"inlineStr\"><is><t>person ").append(i).append("</t></is></c></row>");
        }
        for (int i = 42; i <= 1041; i++) {
            rows.append("<row r=\"").append(i).append("\"><c r=\"A").append(i).append("\" s=\"4\"/></row>");
        }
        TableReading table = reader.read(new ByteArrayInputStream(handBuilt(rows.toString())), auto,
                TableLimits.ofRows(500));
        assertEquals(40, table.rows().size());
        assertTrue(table.rows().stream().noneMatch(row -> row.blank()));
    }

    @Test
    void phoneticRunsAreAnnotationNotCellText() throws IOException {
        TableReading table = reader.read(new ByteArrayInputStream(handBuilt(
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>name</t></is></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is>"
                        + "<r><t>Kana</t></r>"
                        + "<rPh sb=\"0\" eb=\"1\"><t>ka-na</t></rPh>"
                        + "</is></c></row>")),
                auto, limits);
        assertEquals("Kana", cell(table, 2, 0).display());
    }

    @Test
    void phoneticRunsInSharedStringsAreAnnotationNotCellText() throws IOException {
        byte[] file = handBuiltParts(new String[][] {
                {"[Content_Types].xml", contentTypes()},
                {"_rels/.rels", packageRels()},
                {"xl/workbook.xml", workbookXml()},
                {"xl/_rels/workbook.xml.rels",
                        "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                                + "</Relationships>"},
                {"xl/sharedStrings.xml",
                        "<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                                + "<si><t>name</t></si>"
                                + "<si><r><t>Kana</t></r><rPh sb=\"0\" eb=\"1\"><t>ka-na</t></rPh></si></sst>"},
                {"xl/worksheets/sheet1.xml",
                        "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                                + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>"
                                + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c></row></sheetData></worksheet>"}});
        TableReading table = reader.read(new ByteArrayInputStream(file), auto, limits);
        assertEquals("Kana", cell(table, 2, 0).display());
    }

    @Test
    void anIsoDateTypedCellReadsAsADate() throws IOException {
        TableReading table = reader.read(new ByteArrayInputStream(handBuilt(
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>day</t></is></c>"
                        + "<c r=\"B1\" t=\"inlineStr\"><is><t>stamp</t></is></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"d\"><v>2025-03-05</v></c>"
                        + "<c r=\"B2\" t=\"d\"><v>2025-03-05T10:20:30</v></c></row>")),
                auto, limits);
        assertEquals(LocalDate.of(2025, 3, 5), cell(table, 2, 0).date());
        assertEquals("2025-03-05", cell(table, 2, 0).display());
        assertEquals(LocalDate.of(2025, 3, 5), cell(table, 2, 1).date());
        assertEquals("2025-03-05", cell(table, 2, 1).display());
    }

    @Test
    void theRowAndColumnBoundsStopTheRead() throws IOException {
        byte[] tooTall = workbook(wb -> {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("n");
            for (int i = 1; i <= 10_001; i++) {
                sheet.createRow(i).createCell(0).setCellValue(i);
            }
        });
        assertEquals(Reason.TOO_MANY_ROWS, assertThrows(TableUnreadableException.class,
                () -> reader.read(new ByteArrayInputStream(tooTall), auto, limits)).reason());

        byte[] tooWide = workbook(wb -> {
            Sheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            Row row = sheet.createRow(1);
            for (int i = 0; i <= TableLimits.MAX_COLUMNS; i++) {
                header.createCell(i).setCellValue("c" + i);
                row.createCell(i).setCellValue(i);
            }
        });
        assertEquals(Reason.TOO_MANY_COLUMNS, assertThrows(TableUnreadableException.class,
                () -> reader.read(new ByteArrayInputStream(tooWide), auto, limits)).reason());
    }

    @Test
    void whatIsNotAReadableWorkbookSaysSo() throws IOException {
        assertEquals(Reason.NOT_A_SPREADSHEET, assertThrows(TableUnreadableException.class,
                () -> reader.read(new ByteArrayInputStream("not a zip".getBytes(StandardCharsets.UTF_8)), auto, limits)).reason());

        ByteArrayOutputStream broken = new ByteArrayOutputStream();
        broken.write(new byte[] {'P', 'K', 3, 4});
        broken.write("then nothing a package needs".getBytes(StandardCharsets.UTF_8));
        assertEquals(Reason.DAMAGED, assertThrows(TableUnreadableException.class,
                () -> reader.read(new ByteArrayInputStream(broken.toByteArray()), auto, limits)).reason());

        // A workbook whose sheet part is not XML is damaged, not wrong.
        byte[] badSheet = handBuiltParts(
                new String[][] {
                        {"[Content_Types].xml", contentTypes()},
                        {"_rels/.rels", packageRels()},
                        {"xl/workbook.xml", workbookXml()},
                        {"xl/_rels/workbook.xml.rels", workbookRels()},
                        {"xl/worksheets/sheet1.xml", "\u0001 not xml \u0002"}});
        assertEquals(Reason.DAMAGED, assertThrows(TableUnreadableException.class,
                () -> reader.read(new ByteArrayInputStream(badSheet), auto, limits)).reason());
    }

    // ---- builders ----

    private interface Build {
        void build(XSSFWorkbook workbook);
    }

    private static byte[] workbook(Build build) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            build.build(workbook);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * A workbook assembled part by part: one visible sheet named Data whose
     * XML is {@code sheetXml} verbatim, so tests can write cells no document
     * model would produce.
     */
    private static byte[] handBuilt(String sheetXml) throws IOException {
        return handBuilt(sheetXml, "");
    }

    /** As {@link #handBuilt(String)}, with XML after the sheet data -- merges and the rest of a worksheet's tail. */
    private static byte[] handBuilt(String sheetXml, String afterSheetData) throws IOException {
        return handBuiltParts(new String[][] {
                {"[Content_Types].xml", contentTypes()},
                {"_rels/.rels", packageRels()},
                {"xl/workbook.xml", workbookXml()},
                {"xl/_rels/workbook.xml.rels", workbookRels()},
                {"xl/worksheets/sheet1.xml",
                        "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                                + "<sheetData>" + sheetXml + "</sheetData>" + afterSheetData + "</worksheet>"}});
    }

    private static byte[] handBuiltParts(String[][] parts) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (String[] part : parts) {
                zip.putNextEntry(new ZipEntry(part[0]));
                zip.write(part[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static String contentTypes() {
        return "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>"
                + "</Types>";
    }

    private static String packageRels() {
        return "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private static String workbookXml() {
        return "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Data\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
    }

    private static String workbookRels() {
        return "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "</Relationships>";
    }

    private static List<String> names(TableReading table) {
        return table.columns().stream().map(io.github.vihuynh72.brownie.core.batch.TableColumn::name).toList();
    }

    private static TableCell cell(TableReading table, int rowNumber, int column) {
        return table.rows().stream()
                .filter(row -> row.rowNumber() == rowNumber)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + rowNumber))
                .cells()
                .get(column);
    }
}
