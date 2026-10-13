package io.github.vihuynh72.brownie.api.document.table;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The files committed under {@code fixtures/public/} for batch filling --
 * the certificate template, the two-hundred-member table as CSV and as a
 * workbook, and the formula-cells pair -- made by {@link BatchFixtureFiles},
 * so they can be made again:
 *
 * <pre>./mvnw -o test -pl brownie-api -Dtest=PublicBatchFixturesTest -Dbrownie.writePublicBatchFixtures=true</pre>
 *
 * <p>A saved package carries a fresh timestamp each time, so the Office
 * files are compared by what they hold (sheets, cells, controls), not byte
 * for byte: a change to a builder that changes what a committed file holds
 * fails here until the file is made again. The CSVs compare byte for byte.
 */
class PublicBatchFixturesTest {

    private interface Builder {
        byte[] build() throws IOException;
    }

    private static final Map<String, Builder> FIXTURES = new LinkedHashMap<>();

    static {
        FIXTURES.put("templates/certificate-of-participation.docx", BatchFixtureFiles::certificateDocx);
        FIXTURES.put("tables/members-200.csv", BatchFixtureFiles::membersCsv);
        FIXTURES.put("tables/members-200.xlsx", BatchFixtureFiles::membersXlsx);
        FIXTURES.put("tables/formula-cells.csv", BatchFixtureFiles::formulaCellsCsv);
        FIXTURES.put("tables/formula-cells.xlsx", BatchFixtureFiles::formulaCellsXlsx);
    }

    @Test
    @EnabledIfSystemProperty(named = "brownie.writePublicBatchFixtures", matches = "true")
    void write() throws IOException {
        Path directory = repositoryRoot().resolve("fixtures/public");
        for (Map.Entry<String, Builder> fixture : FIXTURES.entrySet()) {
            Path file = directory.resolve(fixture.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, fixture.getValue().build());
        }
    }

    @Test
    void theCommittedFilesHoldWhatTheBuildersMake() throws IOException {
        for (Map.Entry<String, Builder> fixture : FIXTURES.entrySet()) {
            Path committed = repositoryRoot().resolve("fixtures/public").resolve(fixture.getKey());
            assertTrue(Files.isRegularFile(committed),
                    "missing " + committed + "; make it with the command in this class's javadoc");
            assertEquals(contentsOf(fixture.getKey(), fixture.getValue().build()),
                    contentsOf(fixture.getKey(), Files.readAllBytes(committed)), fixture.getKey());
        }
    }

    /** What a person or a browser test would notice: rows and cells for a workbook, text for a CSV, text and tags for a template. */
    private static List<String> contentsOf(String name, byte[] bytes) throws IOException {
        if (name.endsWith(".csv")) {
            return List.of(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\\R", -1));
        }
        if (name.endsWith(".xlsx")) {
            return workbookContents(bytes);
        }
        return documentContents(bytes);
    }

    private static List<String> workbookContents(byte[] bytes) throws IOException {
        List<String> contents = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            contents.add("sheets " + workbook.getNumberOfSheets());
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                XSSFSheet sheet = workbook.getSheetAt(s);
                contents.add("sheet " + sheet.getSheetName() + " rows " + sheet.getLastRowNum());
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder("row " + row.getRowNum());
                    for (Cell cell : row) {
                        line.append(" | ").append(cell.getColumnIndex()).append(':');
                        if (cell.getCellType() == CellType.FORMULA) {
                            line.append("f=").append(cell.getCellFormula()).append(" cached=").append(cachedType(cell));
                            continue;
                        }
                        switch (cell.getCellType()) {
                            case NUMERIC -> line.append(cell.getNumericCellValue()).append(' ').append(cell.getCellStyle().getDataFormatString());
                            case BOOLEAN -> line.append(cell.getBooleanCellValue());
                            case ERROR -> line.append("error ").append(cell.getErrorCellValue());
                            default -> line.append('"').append(cell.getStringCellValue()).append('"');
                        }
                    }
                    contents.add(line.toString());
                }
            }
        }
        return contents;
    }

    private static String cachedType(Cell cell) {
        return switch (cell.getCachedFormulaResultType()) {
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case ERROR -> "error " + cell.getErrorCellValue();
            case STRING -> '"' + cell.getStringCellValue() + '"';
            default -> "none";
        };
    }

    private static List<String> documentContents(byte[] bytes) throws IOException {
        List<String> contents = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                StringBuilder line = new StringBuilder("paragraph \"").append(paragraph.getText()).append('"');
                for (Object child : paragraph.getCTP().getSdtList()) {
                    CTSdtRun sdt = (CTSdtRun) child;
                    if (sdt.isSetSdtPr() && sdt.getSdtPr().isSetTag()) {
                        line.append(" <").append(sdt.getSdtPr().getTag().getVal()).append('>');
                    }
                }
                contents.add(line.toString());
            }
        }
        return contents;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("fixtures/public/templates"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("could not locate the repository root from the test working directory");
    }
}
