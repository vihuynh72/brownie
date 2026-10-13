package io.github.vihuynh72.brownie.core.batch;

import io.github.vihuynh72.brownie.core.batch.TableUnreadableException.Reason;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules both table readers answer to, kept in one place so a CSV and a
 * workbook can never disagree about what a table is: which row names the
 * columns, what displays as a date or a number, which rows count as data,
 * and what is worth noticing. A reader hands cells in row order to a
 * {@link Collector}, which decides the header at the first non-blank row and
 * refuses the row past the limit without reading further; {@link #assemble}
 * then names and types the columns and writes the notices.
 */
public final class TableRules {

    /** How many duplicated-row groups a notice names before it stops listing. */
    private static final int MAX_DUPLICATE_GROUPS = 20;

    /** ISO dates, and only in the padded two-digit form a date picker writes. */
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    /** Numeric dates with a four-digit year and one of / . - as separator. */
    private static final Pattern NUMERIC_DATE = Pattern.compile("(\\d{1,2})([/.-])(\\d{1,2})\\2(\\d{4})");
    /** "January 5, 2025" and friends, month name first. */
    private static final Pattern ENGLISH_MONTH_FIRST = Pattern.compile(
            "([A-Za-z]+)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,\\s*(\\d{4})");
    /** "5 January 2025" and friends, day number first. */
    private static final Pattern ENGLISH_DAY_FIRST = Pattern.compile(
            "(\\d{1,2})(?:st|nd|rd|th)?\\s+([A-Za-z]+)\\.?,?\\s+(\\d{4})");
    /** A plain number, with or without thousands separators. */
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\d{1,3}(,\\d{3})+(\\.\\d+)?)");

    private static final Map<String, Integer> ENGLISH_MONTHS = englishMonths();

    private TableRules() {}

    /** The letter a spreadsheet shows for a 0-based column index: 0 is A, 26 is AA. */
    public static String columnLetter(int index) {
        StringBuilder letter = new StringBuilder();
        int n = index;
        do {
            letter.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return letter.toString();
    }

    /** Whether the display reads as a number -- for header detection and column typing, never for value filling. */
    public static boolean isNumber(String display) {
        return display != null && NUMBER.matcher(display.trim()).matches();
    }

    /** Whether a row reads as names: every non-blank cell is text -- not a number, not a date -- and no name repeats. */
    public static boolean looksLikeHeader(List<TableCell> cells) {
        Set<String> names = new LinkedHashSet<>();
        boolean anyText = false;
        for (TableCell cell : cells) {
            if (cell.problem() != null) {
                return false;
            }
            String name = cell.display().trim();
            if (name.isEmpty() && cell.date() == null) {
                continue;
            }
            if (cell.date() != null || isNumber(name) || !names.add(name.toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
            anyText = true;
        }
        return anyText;
    }

    /**
     * The date a display reads as, or null when it is not a date. A numeric
     * date that survives reading both ways is {@code ambiguous}; a numeric
     * date whose day and month do not fit the order is not a date at all.
     */
    static ParsedDate parseTextDate(String display, DateOrder order) {
        String text = display.trim();
        Matcher iso = ISO_DATE.matcher(text);
        if (iso.matches()) {
            LocalDate date = date(iso.group(1), iso.group(2), iso.group(3));
            return date == null ? null : new ParsedDate(date, false);
        }
        Matcher numeric = NUMERIC_DATE.matcher(text);
        if (numeric.matches()) {
            int first = Integer.parseInt(numeric.group(1));
            int second = Integer.parseInt(numeric.group(3));
            int month = order == DateOrder.DMY ? second : first;
            int day = order == DateOrder.DMY ? first : second;
            LocalDate date = date(numeric.group(4), month, day);
            if (date == null) {
                return null;
            }
            boolean ambiguous = first <= 12 && second <= 12 && first != second;
            return new ParsedDate(date, ambiguous);
        }
        Matcher monthFirst = ENGLISH_MONTH_FIRST.matcher(text);
        if (monthFirst.matches()) {
            Integer month = ENGLISH_MONTHS.get(monthFirst.group(1).toLowerCase(java.util.Locale.ROOT));
            LocalDate date = month == null ? null : date(monthFirst.group(3), month, Integer.parseInt(monthFirst.group(2)));
            return date == null ? null : new ParsedDate(date, false);
        }
        Matcher dayFirst = ENGLISH_DAY_FIRST.matcher(text);
        if (dayFirst.matches()) {
            Integer month = ENGLISH_MONTHS.get(dayFirst.group(2).toLowerCase(java.util.Locale.ROOT));
            LocalDate date = month == null ? null : date(dayFirst.group(3), month, Integer.parseInt(dayFirst.group(1)));
            return date == null ? null : new ParsedDate(date, false);
        }
        return null;
    }

    private static LocalDate date(String year, int month, int day) {
        try {
            return LocalDate.of(Integer.parseInt(year), month, day);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static LocalDate date(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static Map<String, Integer> englishMonths() {
        String[] full = {"january", "february", "march", "april", "may", "june",
                "july", "august", "september", "october", "november", "december"};
        String[] short_ = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
        Map<String, Integer> months = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            months.put(full[i], i + 1);
            months.put(short_[i], i + 1);
        }
        months.put("sept", 9);
        return months;
    }

    /** A text date parsed, with whether it could have been read the other way round. */
    record ParsedDate(LocalDate date, boolean ambiguous) {}

    /**
     * Collects a reader's rows in order: analyzes each cell, finds the header
     * at the first non-blank row (or takes the caller's word for it), and
     * refuses the data row past the limit so a reader can stop reading.
     * Blank rows are never data on their own: buffered by number only, they
     * become blank data rows when a later non-blank row proves they sit
     * between data, and are dropped when nothing follows them. Only the
     * between-data kind is capped -- a file with a data row on both sides of
     * a huge gap is refused for it, while a tail of styled-empty rows is no
     * table's business at all.
     */
    public static final class Collector {

        private final Boolean declaredHeader;
        private final DateOrder dateOrder;
        private final int maxDataRows;
        private final List<Integer> pendingBlankRows = new ArrayList<>();
        private final List<TableRow> dataRows = new ArrayList<>();
        private final Set<Integer> ambiguousDateRows = new LinkedHashSet<>();
        private TableRow header;
        private boolean decided;
        private int blankRows;
        private int dataRowCount;

        public Collector(Boolean declaredHeader, DateOrder dateOrder, int maxDataRows) {
            this.declaredHeader = declaredHeader;
            this.dateOrder = dateOrder == null ? DateOrder.MDY : dateOrder;
            this.maxDataRows = maxDataRows;
            this.decided = Boolean.FALSE.equals(declaredHeader);
        }

        /**
         * Takes one row of cells as read -- display text, a native date when
         * the cell's type gives one, a problem when it could not be read --
         * and turns text that parses as a date into the date it is.
         */
        public void add(int rowNumber, List<TableCell> cells) {
            List<TableCell> analyzed = new ArrayList<>(cells.size());
            for (TableCell cell : cells) {
                analyzed.add(analyze(cell, rowNumber));
            }
            boolean blank = analyzed.stream().allMatch(TableCell::isBlank);
            if (blank) {
                addBlank(rowNumber);
                return;
            }
            if (!decided) {
                decided = true;
                if (declaredHeader == null ? looksLikeHeader(analyzed) : declaredHeader) {
                    header = new TableRow(rowNumber, analyzed, false);
                    pendingBlankRows.clear();
                    return;
                }
                // No header, so the blanks the file opened with are data too.
                flushBlankRows();
            }
            flushBlankRows();
            addData(new TableRow(rowNumber, analyzed, false));
        }

        /**
         * A blank row might still sit between data rows, so it is held back
         * by number until a non-blank row proves it does -- or nothing comes
         * and it is simply never written, which is what a trailing run of
         * blanks means. Nothing is refused here: how many there are is only
         * known to matter once data follows them, and a styled-empty tail
         * must stay cheap all the way to the end of the file.
         */
        private void addBlank(int rowNumber) {
            pendingBlankRows.add(rowNumber);
        }

        /**
         * The buffered blanks a just-read data row proves are between data
         * rows. They never count toward the data-row limit, but a file that
         * is mostly emptiness between its rows is still no table, so their
         * total is refused past the same bound.
         */
        private void flushBlankRows() {
            for (int number : pendingBlankRows) {
                blankRows++;
                if (blankRows > maxDataRows) {
                    throw new TableUnreadableException(Reason.TOO_MANY_ROWS,
                            "The file holds more than " + maxDataRows + " empty rows between data rows.", number);
                }
                dataRows.add(new TableRow(number, List.of(), true));
            }
            pendingBlankRows.clear();
        }

        private TableCell analyze(TableCell cell, int rowNumber) {
            if (cell.problem() != null || cell.date() != null || cell.display().isBlank()) {
                return cell;
            }
            ParsedDate parsed = parseTextDate(cell.display(), dateOrder);
            if (parsed == null) {
                return cell;
            }
            if (parsed.ambiguous()) {
                ambiguousDateRows.add(rowNumber);
            }
            return new TableCell(cell.display(), parsed.date(), null);
        }

        private void addData(TableRow row) {
            if (dataRowCount >= maxDataRows) {
                throw new TableUnreadableException(Reason.TOO_MANY_ROWS,
                        "The file holds more than " + maxDataRows + " data rows.", row.rowNumber());
            }
            dataRowCount++;
            dataRows.add(row);
        }

        public TableRow header() {
            return header;
        }

        public boolean hasHeaderRow() {
            return header != null;
        }

        public List<TableRow> dataRows() {
            return dataRows;
        }

        Set<Integer> ambiguousDateRows() {
            return ambiguousDateRows;
        }
    }

    /**
     * Names and types the columns and writes the notices for what a
     * {@link Collector} gathered. Every row is padded to the widest row, so
     * columns line up whether the reader kept blanks or not. A file that
     * leaves no data rows -- including one that was only a header -- is
     * {@link Reason#EMPTY}.
     */
    public static TableReading assemble(
            TableFormat format,
            String readerVersion,
            List<String> sheetNames,
            String sheetName,
            Collector collector,
            TableLimits limits) {
        List<TableRow> dataRows = collector.dataRows();
        if (dataRows.isEmpty()) {
            throw new TableUnreadableException(Reason.EMPTY, "The file holds no data rows to fill from.");
        }
        TableRow header = collector.header();
        int width = header == null ? 0 : header.cells().size();
        for (TableRow row : dataRows) {
            width = Math.max(width, row.cells().size());
        }
        int tableWidth = width;
        List<TableRow> paddedRows = dataRows.stream().map(row -> padded(row, tableWidth)).toList();

        List<String> names = columnNames(header, width, limits);
        List<TableColumn> columns = new ArrayList<>(width);
        for (int column = 0; column < width; column++) {
            columns.add(column(column, names.get(column), paddedRows));
        }
        return new TableReading(
                format,
                readerVersion,
                sheetNames,
                sheetName,
                collector.hasHeaderRow(),
                !collector.ambiguousDateRows().isEmpty(),
                columns,
                paddedRows,
                notices(paddedRows, collector.ambiguousDateRows()));
    }

    private static TableRow padded(TableRow row, int width) {
        if (row.cells().size() >= width) {
            return row;
        }
        List<TableCell> cells = new ArrayList<>(width);
        cells.addAll(row.cells());
        while (cells.size() < width) {
            cells.add(TableCell.blank());
        }
        return new TableRow(row.rowNumber(), cells, row.blank());
    }

    private static List<String> columnNames(TableRow header, int width, TableLimits limits) {
        List<String> names = new ArrayList<>(width);
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int column = 0; column < width; column++) {
            String name = "";
            if (header != null && column < header.cells().size()) {
                name = header.cells().get(column).display().trim();
            }
            if (!name.isEmpty()) {
                String key = name.toLowerCase(java.util.Locale.ROOT);
                int times = seen.merge(key, 1, Integer::sum);
                if (times > 1) {
                    name = name + " (" + times + ")";
                }
            }
            if (name.isEmpty()) {
                name = "Column " + columnLetter(column);
            }
            if (name.length() > limits.maxHeaderChars()) {
                name = name.substring(0, limits.maxHeaderChars());
            }
            names.add(name);
        }
        return names;
    }

    private static TableColumn column(int index, String name, List<TableRow> dataRows) {
        ColumnType type = null;
        int blanks = 0;
        for (TableRow row : dataRows) {
            TableCell cell = row.cells().get(index);
            if (cell.isBlank() && cell.problem() == null) {
                blanks++;
                continue;
            }
            ColumnType cellType = cell.problem() != null ? ColumnType.TEXT
                    : cell.date() != null ? ColumnType.DATE
                    : isNumber(cell.display()) ? ColumnType.NUMBER
                    : ColumnType.TEXT;
            type = type == null ? cellType : type == cellType ? type : ColumnType.TEXT;
        }
        return new TableColumn(index, columnLetter(index), name, type == null ? ColumnType.EMPTY : type, blanks);
    }

    private static List<TableNotice> notices(List<TableRow> dataRows, Set<Integer> ambiguousDateRows) {
        List<Integer> blankRows = new ArrayList<>();
        List<Integer> formulaRows = new ArrayList<>();
        List<Integer> errorRows = new ArrayList<>();
        Map<List<String>, List<Integer>> byDisplay = new LinkedHashMap<>();
        for (TableRow row : dataRows) {
            if (row.blank()) {
                blankRows.add(row.rowNumber());
                continue;
            }
            List<String> displays = row.cells().stream().map(cell -> cell.display().trim()).toList();
            byDisplay.computeIfAbsent(displays, key -> new ArrayList<>()).add(row.rowNumber());
            for (TableCell cell : row.cells()) {
                if (cell.problem() == CellProblem.FORMULA_WITHOUT_RESULT) {
                    formulaRows.add(row.rowNumber());
                    break;
                }
                if (cell.problem() == CellProblem.ERROR_VALUE) {
                    errorRows.add(row.rowNumber());
                    break;
                }
            }
        }
        List<TableNotice> notices = new ArrayList<>();
        List<Integer> duplicates = new ArrayList<>();
        int groups = 0;
        for (List<Integer> rows : byDisplay.values()) {
            if (rows.size() > 1) {
                duplicates.addAll(rows);
                if (++groups >= MAX_DUPLICATE_GROUPS) {
                    break;
                }
            }
        }
        if (!duplicates.isEmpty()) {
            notices.add(new TableNotice(TableNotice.DUPLICATE_ROWS, duplicates));
        }
        if (!blankRows.isEmpty()) {
            notices.add(new TableNotice(TableNotice.BLANK_ROWS, blankRows));
        }
        if (!formulaRows.isEmpty()) {
            notices.add(new TableNotice(TableNotice.FORMULAS_WITHOUT_RESULTS, formulaRows));
        }
        if (!errorRows.isEmpty()) {
            notices.add(new TableNotice(TableNotice.ERROR_VALUES, errorRows));
        }
        if (!ambiguousDateRows.isEmpty()) {
            notices.add(new TableNotice(TableNotice.AMBIGUOUS_DATES, List.copyOf(ambiguousDateRows)));
        }
        return notices;
    }
}
