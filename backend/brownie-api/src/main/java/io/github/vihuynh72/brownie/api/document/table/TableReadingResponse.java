package io.github.vihuynh72.brownie.api.document.table;

import io.github.vihuynh72.brownie.core.batch.TableCell;
import io.github.vihuynh72.brownie.core.batch.TableColumn;
import io.github.vihuynh72.brownie.core.batch.TableNotice;
import io.github.vihuynh72.brownie.core.batch.TableReading;
import io.github.vihuynh72.brownie.core.batch.TableRow;

import java.util.List;

/**
 * A table read, as the answer to the table route: the workbook's visible
 * sheets, the sheet and header choice made, the columns named and typed, a
 * preview of the first rows, the notices, and the row limit a batch would
 * apply -- enough for a person to confirm the read before committing a
 * batch to it.
 */
record TableReadingResponse(
        long artifactId,
        String format,
        String readerVersion,
        List<String> sheetNames,
        String sheet,
        boolean hasHeaderRow,
        boolean ambiguousDates,
        List<ColumnResponse> columns,
        long rowCount,
        long blankRowCount,
        List<RowResponse> previewRows,
        List<NoticeResponse> notices,
        int maxRows) {

    private static final int PREVIEW_ROWS = 20;

    static TableReadingResponse from(long artifactId, TableReading reading, int maxRows) {
        return new TableReadingResponse(
                artifactId,
                reading.format().name(),
                reading.readerVersion(),
                reading.sheetNames(),
                reading.sheetName(),
                reading.hasHeaderRow(),
                reading.ambiguousDates(),
                reading.columns().stream().map(ColumnResponse::from).toList(),
                reading.nonBlankRowCount(),
                reading.rows().stream().filter(TableRow::blank).count(),
                reading.rows().stream().limit(PREVIEW_ROWS).map(RowResponse::from).toList(),
                reading.notices().stream().map(NoticeResponse::from).toList(),
                maxRows);
    }

    record ColumnResponse(int index, String letter, String name, String inferredType, int blankCount) {

        static ColumnResponse from(TableColumn column) {
            return new ColumnResponse(
                    column.index(), column.letter(), column.name(), column.inferredType().name(), column.blankCount());
        }
    }

    record RowResponse(int rowNumber, List<CellResponse> cells, boolean blank) {

        static RowResponse from(TableRow row) {
            return new RowResponse(
                    row.rowNumber(), row.cells().stream().map(CellResponse::from).toList(), row.blank());
        }
    }

    /** A cell as the person sees it: display text, its date when it holds one, and why it could not be read when it could not. */
    record CellResponse(String display, String date, String problem) {

        static CellResponse from(TableCell cell) {
            return new CellResponse(
                    cell.display(),
                    cell.date() == null ? null : cell.date().toString(),
                    cell.problem() == null ? null : cell.problem().name());
        }
    }

    record NoticeResponse(String code, List<Integer> rows) {

        static NoticeResponse from(TableNotice notice) {
            return new NoticeResponse(notice.code(), notice.rows());
        }
    }
}
