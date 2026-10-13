package io.github.vihuynh72.brownie.api.document.table;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the committed batch fixtures under {@code fixtures/public/}:
 * the certificate template a two-hundred-member batch is filled from, the
 * table of members itself as CSV and as a workbook, and a small pair of
 * files exercising everything a cell can say that is not a value. Every
 * person here is synthetic -- the names are made up for the fixture and
 * belong to nobody.
 *
 * <p>{@code PublicBatchFixturesTest} makes these files again and fails
 * when a committed file holds something different; its javadoc names the
 * command that writes them.
 */
final class BatchFixtureFiles {

    /** The columns {@code members-200} carries, in order, as its header row writes them. */
    static final String[] MEMBER_COLUMNS = {"Member name", "Award", "Date awarded", "Club", "Citation notes"};

    static final int MEMBER_COUNT = 200;

    private static final String[] FIRST_NAMES = {
            "Amara", "Ben", "Celia", "Dan", "Elin", "Felix", "Gwen", "Hugo", "Iris", "Jonas",
            "Katya", "Leo", "Mira", "Nils", "Oda", "Petr", "Quinn", "Rosa", "Sten", "Tove"};
    private static final String[] LAST_NAMES = {
            "Alvesson", "Brink", "Carlsen", "Dahl", "Ekman", "Falk", "Gunnarsson", "Holm", "Iversen", "Johansen"};
    private static final String[] AWARDS = {
            "Star Contributor", "Service Pin", "Innovation Ribbon", "Team Spirit", "Merit Badge"};
    private static final String[] CLUBS = {
            "North Chapter", "Harbour Circle", "Meadow Society", "Summit Group", "Lantern Guild"};

    private BatchFixtureFiles() {
    }

    /**
     * The members table as CSV: one header record then {@value MEMBER_COUNT}
     * members, values held so a comma never needs quoting and the read stays
     * the plain kind a person exporting from a spreadsheet program writes.
     */
    static byte[] membersCsv() {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", MEMBER_COLUMNS)).append("\r\n");
        for (String[] row : memberRows()) {
            csv.append(String.join(",", row)).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The same table as a workbook, with the date column given a real date format. */
    static byte[] membersXlsx() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Members");
            CellStyle dateStyle = workbook.createCellStyle();
            DataFormat formats = workbook.createDataFormat();
            dateStyle.setDataFormat(formats.getFormat("yyyy-mm-dd"));
            Row header = sheet.createRow(0);
            for (int column = 0; column < MEMBER_COLUMNS.length; column++) {
                header.createCell(column).setCellValue(MEMBER_COLUMNS[column]);
            }
            List<String[]> rows = memberRows();
            for (int i = 0; i < rows.size(); i++) {
                String[] values = rows.get(i);
                Row row = sheet.createRow(i + 1);
                for (int column = 0; column < values.length; column++) {
                    Cell cell = row.createCell(column);
                    if (column == 2) {
                        cell.setCellValue(java.time.LocalDate.parse(values[column]));
                        cell.setCellStyle(dateStyle);
                    } else {
                        cell.setCellValue(values[column]);
                    }
                }
            }
            return written(workbook);
        }
    }

    /**
     * Cells that start with the characters a spreadsheet program treats as
     * formula openers -- {@code =}, {@code +}, {@code -}, {@code @} -- and a
     * leading tab, all of which a CSV must keep as the literal text they are.
     */
    static byte[] formulaCellsCsv() {
        String csv = "Name,Note\r\n"
                + "\"=SUM(1,2)\",starts with an equals sign\r\n"
                + "\"+42\",starts with a plus\r\n"
                + "\"-3\",starts with a minus\r\n"
                + "\"@handle\",starts with an at sign\r\n"
                + "\"\t=1+1\",starts with a tab\r\n";
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The same content as a workbook, plus the three things only a workbook
     * can say: a formula whose result was stored, a formula whose result was
     * not, and a cell whose last evaluation was an error.
     */
    static byte[] formulaCellsXlsx() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Notes");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Note");
            sheet.createRow(1).createCell(0).setCellValue("=SUM(1,2)");
            sheet.createRow(2).createCell(0).setCellValue("+42");
            sheet.createRow(3).createCell(0).setCellValue("-3");
            sheet.createRow(4).createCell(0).setCellValue("@handle");
            sheet.createRow(5).createCell(0).setCellValue("\t=1+1");
            // A formula written with a cached answer, as a spreadsheet program
            // leaves it; the reader takes the stored 3 and never evaluates.
            Row cached = sheet.createRow(6);
            Cell cachedFormula = cached.createCell(0);
            cachedFormula.setCellFormula("1+2");
            cachedFormula.setCellValue(3);
            // A formula saved before any evaluation stored no answer at all.
            sheet.createRow(7).createCell(0).setCellFormula("SUM(A2:A3)");
            // A formula whose stored answer was an error.
            sheet.createRow(8).createCell(0).setCellErrorValue(FormulaError.NAME.getCode());
            return written(workbook);
        }
    }

    /**
     * The certificate the two-hundred-member batch fills: five tagged
     * content controls a template's fields bind to -- the member, the award
     * and its date, the club, and a free-text citation.
     */
    static byte[] certificateDocx() throws IOException {
        XWPFDocument doc = new XWPFDocument();
        XWPFParagraph heading = doc.createParagraph();
        heading.createRun().setText("Certificate of Participation");

        XWPFParagraph body = doc.createParagraph();
        body.createRun().setText("This certifies that ");
        appendContentControl(body, "member.name", "[member name]");
        body.createRun().setText(" has earned the award ");
        appendContentControl(body, "award.title", "[award]");
        body.createRun().setText(", awarded on ");
        appendContentControl(body, "award.date", "[date]");
        body.createRun().setText(".");

        XWPFParagraph club = doc.createParagraph();
        club.createRun().setText("Presented by ");
        appendContentControl(club, "club.name", "[club]");

        XWPFParagraph citation = doc.createParagraph();
        citation.createRun().setText("Citation: ");
        appendContentControl(citation, "citation", "[citation]");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        doc.close();
        return out.toByteArray();
    }

    private static void appendContentControl(XWPFParagraph paragraph, String tag, String text) {
        CTSdtRun sdt = paragraph.getCTP().addNewSdt();
        CTSdtPr properties = sdt.addNewSdtPr();
        properties.addNewTag().setVal(tag);
        properties.addNewAlias().setVal(tag);
        CTSdtContentRun content = sdt.addNewSdtContent();
        CTR run = content.addNewR();
        run.addNewT().setStringValue(text);
    }

    /** The member rows, deterministic: synthetic names, one award, club and note each, ISO dates through 2025. */
    private static List<String[]> memberRows() {
        List<String[]> rows = new ArrayList<>(MEMBER_COUNT);
        for (int i = 0; i < MEMBER_COUNT; i++) {
            String name = FIRST_NAMES[i % FIRST_NAMES.length] + " "
                    + LAST_NAMES[(i / FIRST_NAMES.length + i) % LAST_NAMES.length] + " " + (i + 1);
            String award = AWARDS[i % AWARDS.length];
            String date = String.format(java.util.Locale.ROOT, "2025-%02d-%02d", i % 12 + 1, i % 28 + 1);
            String club = CLUBS[i % CLUBS.length];
            String note = "Recognized for service " + (i + 1);
            rows.add(new String[] {name, award, date, club, note});
        }
        return rows;
    }

    private static byte[] written(Workbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }
}
