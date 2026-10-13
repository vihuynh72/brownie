package io.github.vihuynh72.brownie.api.document.table;

import io.github.vihuynh72.brownie.core.batch.TableFormat;
import io.github.vihuynh72.brownie.core.batch.TableLimits;
import io.github.vihuynh72.brownie.core.batch.TableReadRequest;
import io.github.vihuynh72.brownie.core.batch.TableReading;
import io.github.vihuynh72.brownie.core.batch.TableRules;
import io.github.vihuynh72.brownie.core.batch.TableUnreadableException;
import io.github.vihuynh72.brownie.core.batch.TableUnreadableException.Reason;
import io.github.vihuynh72.brownie.core.batch.XlsxTableReader;
import org.apache.poi.Version;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.List;
import java.util.Locale;

/**
 * Reads a workbook through Apache POI's event API, so a sheet is a stream of
 * rows and a big file is refused at its bound rather than after it is all in
 * memory. Formula results are whatever the workbook last stored -- the
 * reader never evaluates one. The version this reader names includes POI's,
 * because a library change can change what a file reads as, and a batch
 * whose file reads differently must say so rather than fill differently.
 */
public class PoiXlsxTableReader implements XlsxTableReader {

    private static final String XLSX_MAIN_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";

    private final String readerVersion = "brownie-xlsx-v1+poi-" + Version.getVersion();

    @Override
    public String readerVersion() {
        return readerVersion;
    }

    @Override
    public TableReading read(InputStream content, TableReadRequest request, TableLimits limits) throws IOException {
        PushbackInputStream sniffed = new PushbackInputStream(content, 4);
        if (!looksLikeZip(sniffed)) {
            throw new TableUnreadableException(Reason.NOT_A_SPREADSHEET,
                    "The file's bytes are not an Excel workbook at all.");
        }
        try (OPCPackage pkg = OPCPackage.open(sniffed)) {
            PackagePart workbookPart = workbookPart(pkg);
            XlsxWorkbookScanner.Contents workbook = XlsxWorkbookScanner.read(workbookPart);
            XlsxSheet chosen = choose(workbook.sheets(), request.sheetName());
            try (InputStream sheet = sheetStream(pkg, workbookPart, chosen)) {
                XSSFReader reader = new XSSFReader(pkg);
                // Phonetic runs annotate a string, they are not its text; the
                // read-only table reads without them, and the sheet handler
                // skips the same runs inside inline strings.
                SharedStrings sharedStrings = new ReadOnlySharedStringsTable(pkg, false);
                TableRules.Collector collector = new TableRules.Collector(
                        request.hasHeaderRow(), request.effectiveDateOrder(), limits.maxDataRows());
                XlsxSheetXmlHandler handler = new XlsxSheetXmlHandler(
                        sharedStrings, reader.getStylesTable(), new DataFormatter(Locale.US),
                        workbook.date1904(), collector, limits);
                XMLReader xml = XMLHelper.newXMLReader();
                xml.setContentHandler(handler);
                xml.parse(new InputSource(sheet));
                return TableRules.assemble(
                        TableFormat.XLSX, readerVersion, sheetNames(workbook.sheets()), chosen.name(),
                        collector, limits);
            }
        } catch (TableUnreadableException | IOException e) {
            throw e;
        } catch (Exception e) {
            throw new TableUnreadableException(Reason.DAMAGED,
                    "The file could not be read as an Excel workbook: " + firstLine(e));
        }
    }

    private static XlsxSheet choose(List<XlsxSheet> sheets, String wanted) {
        if (wanted != null) {
            return sheets.stream()
                    .filter(sheet -> sheet.name().equals(wanted))
                    .findFirst()
                    .orElseThrow(() -> new TableUnreadableException(Reason.SHEET_NOT_FOUND,
                            "The workbook has no sheet named \"" + wanted + "\"."));
        }
        return sheets.stream().filter(sheet -> !sheet.hidden()).findFirst().orElse(sheets.get(0));
    }

    private static List<String> sheetNames(List<XlsxSheet> sheets) {
        // Every sheet is listed, hidden ones too -- a person picks one by name, so hiding only steers the default.
        return sheets.stream().map(XlsxSheet::name).toList();
    }

    /** The part the sheet's relationship names, resolved the way the workbook's own relationships point. */
    private static InputStream sheetStream(OPCPackage pkg, PackagePart workbookPart, XlsxSheet sheet) throws IOException {
        try {
            PackageRelationship relationship = workbookPart.getRelationship(sheet.relationshipId());
            if (relationship == null) {
                throw damaged("A sheet's part is missing");
            }
            PackagePart part = pkg.getPart(PackagingURIHelper.createPartName(
                    PackagingURIHelper.resolvePartUri(workbookPart.getPartName().getURI(), relationship.getTargetURI())));
            if (part == null) {
                throw damaged("A sheet's part is missing");
            }
            return part.getInputStream();
        } catch (TableUnreadableException e) {
            throw e;
        } catch (Exception e) {
            throw damaged("A sheet's part could not be read", e);
        }
    }

    /**
     * The part that is the workbook: the package's core document, which must
     * be a spreadsheet main part -- a word processor's document inside the
     * same kind of package is still not a table.
     */
    private static PackagePart workbookPart(OPCPackage pkg) {
        try {
            PackageRelationship core = pkg.getRelationshipsByType(PackageRelationshipTypes.CORE_DOCUMENT)
                    .getRelationship(0);
            if (core == null) {
                throw damaged("The package names no workbook");
            }
            PackagePart part = pkg.getPart(core);
            if (part == null) {
                throw damaged("The package names no workbook");
            }
            if (!XLSX_MAIN_TYPE.equals(part.getContentType())) {
                throw new TableUnreadableException(Reason.NOT_A_SPREADSHEET,
                        "The package holds a document, not an Excel workbook.");
            }
            return part;
        } catch (TableUnreadableException e) {
            throw e;
        } catch (Exception e) {
            throw damaged("The package's workbook could not be found", e);
        }
    }

    /** A ZIP package starts with PK; anything else was never a workbook. */
    private static boolean looksLikeZip(PushbackInputStream stream) throws IOException {
        byte[] head = new byte[4];
        int read = stream.read(head);
        stream.unread(head, 0, Math.max(read, 0));
        return read >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    }

    private static TableUnreadableException damaged(String why) {
        return new TableUnreadableException(Reason.DAMAGED,
                why + "; the file cannot be read as an Excel workbook.");
    }

    private static TableUnreadableException damaged(String why, Exception cause) {
        TableUnreadableException e = damaged(why);
        e.initCause(cause);
        return e;
    }

    private static String firstLine(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }
}
