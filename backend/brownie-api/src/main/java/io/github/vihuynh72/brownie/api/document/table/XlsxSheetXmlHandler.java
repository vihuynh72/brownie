package io.github.vihuynh72.brownie.api.document.table;

import io.github.vihuynh72.brownie.core.batch.CellProblem;
import io.github.vihuynh72.brownie.core.batch.TableCell;
import io.github.vihuynh72.brownie.core.batch.TableLimits;
import io.github.vihuynh72.brownie.core.batch.TableRules;
import io.github.vihuynh72.brownie.core.batch.TableUnreadableException;
import io.github.vihuynh72.brownie.core.batch.TableUnreadableException.Reason;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.RichTextString;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Turns one sheet's XML into rows for the {@link TableRules.Collector}, one
 * {@code <row>} at a time, so the sheet is never held whole. What a cell
 * offers is what was stored: a formula's cached result is read as the value
 * it last computed, a formula with no stored result is a cell that cannot be
 * read ({@link CellProblem#FORMULA_WITHOUT_RESULT}) -- the reader never
 * evaluates one -- and an error result is an error, not a guess. Merged
 * cells need no handling: a covered cell is simply absent from its row, so
 * only the anchor carries a value, which is what reading a merge means.
 */
final class XlsxSheetXmlHandler extends DefaultHandler {

    private final SharedStrings strings;
    private final StylesTable styles;
    private final DataFormatter formatter;
    private final boolean date1904;
    private final TableRules.Collector collector;
    private final TableLimits limits;

    private TreeMap<Integer, TableCell> rowCells;
    private int rowNumber;
    private int runningRow;
    private int expectedColumn;

    private int cellColumn = -1;
    private String cellType;
    private int cellStyle = -1;
    private boolean formulaSeen;
    private boolean valueElementSeen;
    private boolean inlineSeen;
    private boolean inValue;
    private boolean inInline;
    private int phoneticDepth;
    private final StringBuilder valueText = new StringBuilder();
    private final StringBuilder inlineText = new StringBuilder();

    XlsxSheetXmlHandler(
            SharedStrings strings,
            StylesTable styles,
            DataFormatter formatter,
            boolean date1904,
            TableRules.Collector collector,
            TableLimits limits) {
        this.strings = strings;
        this.styles = styles;
        this.formatter = formatter;
        this.date1904 = date1904;
        this.collector = collector;
        this.limits = limits;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
        switch (localName.isEmpty() ? qName : localName) {
            case "row" -> {
                rowCells = new TreeMap<>();
                rowNumber = rowNumber(attributes);
                expectedColumn = 0;
            }
            case "c" -> {
                cellColumn = columnIndex(attributes);
                cellType = attributes.getValue("t");
                cellStyle = styleIndex(attributes.getValue("s"));
                formulaSeen = false;
                valueElementSeen = false;
                inlineSeen = false;
                inValue = false;
                inInline = false;
                phoneticDepth = 0;
                valueText.setLength(0);
                inlineText.setLength(0);
            }
            case "v" -> {
                valueElementSeen = true;
                inValue = true;
            }
            case "f" -> formulaSeen = true;
            case "is" -> {
                inlineSeen = true;
                inInline = true;
            }
            case "rPh" -> phoneticDepth++;
            default -> { }
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        switch (localName.isEmpty() ? qName : localName) {
            case "v" -> inValue = false;
            case "is" -> inInline = false;
            case "rPh" -> phoneticDepth--;
            case "c" -> {
                if (rowCells == null) {
                    throw damaged("A cell arrives outside its row", null);
                }
                // A styled empty cell holds no value and reports no problem:
                // it is not a column of the table at all.
                if (!valueElementSeen && !inlineSeen && !formulaSeen && !"e".equals(cellType)) {
                    break;
                }
                if (cellColumn >= limits.maxColumns()) {
                    throw new TableUnreadableException(Reason.TOO_MANY_COLUMNS,
                            "Row " + rowNumber + " reaches column " + TableRules.columnLetter(cellColumn)
                                    + "; a table may have at most " + limits.maxColumns() + " columns.",
                            rowNumber);
                }
                rowCells.put(cellColumn, cell());
            }
            case "row" -> {
                if (rowCells != null) {
                    // Cells can be absent anywhere in the row; each kept one
                    // must land on its own column, blanks filling the gaps.
                    collector.add(rowNumber, dense(rowCells));
                }
                rowCells = null;
            }
            default -> { }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        StringBuilder target = phoneticDepth > 0 ? null : inValue ? valueText : inInline ? inlineText : null;
        if (target == null) {
            return;
        }
        if (target.length() + length > limits.maxCellChars()) {
            throw new TableUnreadableException(Reason.CELL_TOO_LONG,
                    "A cell in row " + rowNumber + " holds more than " + limits.maxCellChars() + " characters.",
                    rowNumber);
        }
        target.append(ch, start, length);
    }

    /**
     * The cell just closed, from what the element stored. A formula that
     * stored no result is unreadable whatever its type; an error result is
     * an error whatever it would have shown. The row's own column letter
     * order decides placement: cells may arrive sparse, and a covered
     * (merged) cell simply never arrives.
     */
    private TableCell cell() {
        String value = valueText.toString();
        if (formulaSeen && !valueElementSeen) {
            return new TableCell("", null, CellProblem.FORMULA_WITHOUT_RESULT);
        }
        if (cellType == null) {
            cellType = "n";
        }
        switch (cellType) {
            case "s" -> {
                return new TableCell(shared(value), null, null);
            }
            case "inlineStr" -> {
                return new TableCell(inlineText.toString(), null, null);
            }
            case "str" -> {
                return new TableCell(value, null, null);
            }
            case "d" -> {
                return isoDateCell(value);
            }
            case "b" -> {
                return value.isEmpty() ? TableCell.blank() : new TableCell("1".equals(value) ? "TRUE" : "FALSE", null, null);
            }
            case "e" -> {
                return new TableCell("", null, CellProblem.ERROR_VALUE);
            }
            default -> {
                return numberCell(value);
            }
        }
    }

    /** The row's cells placed at their own columns: absent cells are blanks, so A1 and C1 land at 0 and 2, not 0 and 1. */
    private static List<TableCell> dense(TreeMap<Integer, TableCell> cells) {
        if (cells.isEmpty()) {
            return List.of();
        }
        int width = cells.lastKey() + 1;
        List<TableCell> row = new ArrayList<>(java.util.Collections.nCopies(width, TableCell.blank()));
        cells.forEach(row::set);
        return row;
    }

    /** An ISO 8601 date or date-time cell ({@code t="d"}): the date it names, displayed as the plain ISO date. */
    private static TableCell isoDateCell(String value) {
        String text = value.trim();
        if (text.isEmpty()) {
            return TableCell.blank();
        }
        LocalDate date;
        try {
            date = LocalDate.parse(text);
        } catch (java.time.format.DateTimeParseException notADate) {
            try {
                date = java.time.LocalDateTime.parse(text).toLocalDate();
            } catch (java.time.format.DateTimeParseException notATime) {
                try {
                    date = java.time.OffsetDateTime.parse(text).toLocalDate();
                } catch (java.time.format.DateTimeParseException nothing) {
                    // A d-typed cell holding none of the ISO forms is kept as
                    // the text it is; the file is still a table.
                    return new TableCell(text, null, null);
                }
            }
        }
        return new TableCell(date.toString(), date, null);
    }

    private TableCell numberCell(String value) {
        if (value.isEmpty()) {
            return TableCell.blank();
        }
        double number;
        try {
            number = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            // A stored number that is not one is kept as the text it is; the
            // file is still a table, the cell just is not numeric.
            return new TableCell(value, null, null);
        }
        int formatId = 0;
        String formatString = null;
        if (styles != null && cellStyle >= 0 && cellStyle < styles.getNumCellStyles()) {
            XSSFCellStyle style = styles.getStyleAt(cellStyle);
            if (style != null) {
                formatId = style.getDataFormat();
                formatString = style.getDataFormatString();
            }
        }
        String display = formatter.formatRawCellContents(number, formatId, formatString, date1904);
        if (display.length() > limits.maxCellChars()) {
            throw new TableUnreadableException(Reason.CELL_TOO_LONG,
                    "A cell in row " + rowNumber + " displays more than " + limits.maxCellChars() + " characters.",
                    rowNumber);
        }
        LocalDate date = DateUtil.isADateFormat(formatId, formatString)
                ? DateUtil.getLocalDateTime(number, date1904).toLocalDate()
                : null;
        return new TableCell(display, date, null);
    }

    private String shared(String value) {
        if (value.isEmpty() || strings == null) {
            return "";
        }
        int index;
        try {
            index = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw damaged("A shared-string cell names no string", e);
        }
        RichTextString text = strings.getItemAt(index);
        return text == null ? "" : text.toString();
    }

    /** The row a {@code <row>} element's own r attribute names; absent, the next row after the last one seen. */
    private int rowNumber(Attributes attributes) {
        String ref = attributes.getValue("r");
        if (ref != null && !ref.isEmpty()) {
            try {
                runningRow = Integer.parseInt(ref.trim());
                return runningRow;
            } catch (NumberFormatException e) {
                throw damaged("A row is numbered " + ref, e);
            }
        }
        return ++runningRow;
    }

    /** The column a {@code <c>} element's r attribute names; absent, the column after the last cell in the row. */
    private int columnIndex(Attributes attributes) {
        String ref = attributes.getValue("r");
        if (ref != null && !ref.isEmpty()) {
            expectedColumn = columnIndex(ref) + 1;
            return expectedColumn - 1;
        }
        return expectedColumn++;
    }

    /** The 0-based index the leading letters of a reference like "BC12" give its column. */
    private static int columnIndex(String ref) {
        int column = 0;
        int i = 0;
        while (i < ref.length()) {
            char c = ref.charAt(i);
            if (c < 'A' || c > 'Z') {
                break;
            }
            column = column * 26 + (c - 'A' + 1);
            i++;
        }
        if (i == 0 || column < 1) {
            throw damaged("A cell is addressed " + ref, null);
        }
        return column - 1;
    }

    private static int styleIndex(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static TableUnreadableException damaged(String why, Exception cause) {
        TableUnreadableException e = new TableUnreadableException(Reason.DAMAGED,
                why + "; the file cannot be read as an Excel workbook.");
        if (cause != null) {
            e.initCause(cause);
        }
        return e;
    }
}
