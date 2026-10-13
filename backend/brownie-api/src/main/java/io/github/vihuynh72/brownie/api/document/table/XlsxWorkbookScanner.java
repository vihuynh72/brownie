package io.github.vihuynh72.brownie.api.document.table;

import io.github.vihuynh72.brownie.core.batch.TableUnreadableException;
import io.github.vihuynh72.brownie.core.batch.TableUnreadableException.Reason;
import org.apache.poi.openxml4j.opc.PackagePart;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a workbook's own index -- its sheets in the order a person sees them,
 * whether each is hidden, and which of the two date systems its dates are
 * stored in -- straight from workbook.xml, so the reader never builds the
 * whole workbook model to learn it. A workbook.xml without a sheets list is
 * not a workbook at all.
 */
final class XlsxWorkbookScanner {

    /** The sheet refs, in workbook order, and the workbook's date system. */
    record Contents(List<XlsxSheet> sheets, boolean date1904) {}

    private XlsxWorkbookScanner() {}

    static Contents read(PackagePart workbookPart) {
        List<XlsxSheet> sheets = new ArrayList<>();
        boolean date1904 = false;
        try (InputStream stream = workbookPart.getInputStream()) {
            XMLStreamReader reader = factory().createXMLStreamReader(stream);
            try {
                while (reader.hasNext()) {
                    if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    String name = localName(reader.getLocalName());
                    if ("workbookPr".equals(name)) {
                        String flag = attribute(reader, "date1904");
                        date1904 = "1".equals(flag) || "true".equalsIgnoreCase(flag);
                    } else if ("sheet".equals(name)) {
                        String state = attribute(reader, "state");
                        boolean hidden = "hidden".equalsIgnoreCase(state) || "veryHidden".equalsIgnoreCase(state);
                        sheets.add(new XlsxSheet(attribute(reader, "name"), attribute(reader, "id"), hidden));
                    }
                }
            } finally {
                reader.close();
            }
        } catch (TableUnreadableException e) {
            throw e;
        } catch (Exception e) {
            throw damaged("The workbook's own index could not be read", e);
        }
        List<XlsxSheet> found = sheets.stream()
                .filter(sheet -> sheet.name() != null && sheet.relationshipId() != null && !sheet.relationshipId().isEmpty())
                .toList();
        if (found.isEmpty()) {
            throw damaged("The workbook names no sheets", null);
        }
        return new Contents(found, date1904);
    }

    private static TableUnreadableException damaged(String why, Exception cause) {
        TableUnreadableException e = new TableUnreadableException(Reason.DAMAGED,
                why + "; the file cannot be read as an Excel workbook.");
        if (cause != null) {
            e.initCause(cause);
        }
        return e;
    }

    private static String attribute(XMLStreamReader reader, String wanted) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (wanted.equalsIgnoreCase(localName(reader.getAttributeLocalName(i)))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    /** With namespace awareness off, a prefixed name arrives whole; the part after the colon is the name. */
    private static String localName(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static XMLInputFactory factory() {
        // The JDK's own reader, configured as the package inspector's is: nothing external is ever fetched.
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        factory.setProperty(XMLInputFactory.IS_VALIDATING, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External resources are never fetched.");
        });
        return factory;
    }
}
