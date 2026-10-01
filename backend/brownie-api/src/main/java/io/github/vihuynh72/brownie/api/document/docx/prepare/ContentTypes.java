package io.github.vihuynh72.brownie.api.document.docx.prepare;

import org.apache.poi.ooxml.POIXMLTypeLoader;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;

import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Sets one part's content type in a saved package's {@code [Content_Types].xml}, leaving every other entry as it was. */
final class ContentTypes {

    static final String ENTRY = "[Content_Types].xml";
    private static final String NAMESPACE = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final QName PART_NAME = new QName("", "PartName");
    private static final QName CONTENT_TYPE = new QName("", "ContentType");

    private ContentTypes() {
    }

    /** {@code partName} as the package names it, with its leading slash. */
    static byte[] withOverride(byte[] packageBytes, String partName, String contentType) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(packageBytes));
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                byte[] content = in.readAllBytes();
                if (ENTRY.equals(entry.getName())) {
                    content = override(content, partName, contentType);
                }
                zip.putNextEntry(new ZipEntry(entry.getName()));
                zip.write(content);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] override(byte[] types, String partName, String contentType) throws IOException {
        XmlObject document;
        try {
            document = XmlObject.Factory.parse(new ByteArrayInputStream(types), new XmlOptions(POIXMLTypeLoader.DEFAULT_XML_OPTIONS));
        } catch (XmlException e) {
            throw new IOException("The package's content types could not be read.", e);
        }
        try (XmlCursor cursor = document.newCursor()) {
            cursor.toFirstChild();
            try (XmlCursor child = cursor.newCursor()) {
                if (child.toFirstChild()) {
                    do {
                        if ("Override".equals(child.getName().getLocalPart()) && partName.equalsIgnoreCase(child.getAttributeText(PART_NAME))) {
                            child.setAttributeText(CONTENT_TYPE, contentType);
                            return save(document);
                        }
                    } while (child.toNextSibling());
                }
            }
            cursor.toEndToken();
            cursor.beginElement(new QName(NAMESPACE, "Override"));
            cursor.insertAttributeWithValue(PART_NAME, partName);
            cursor.insertAttributeWithValue(CONTENT_TYPE, contentType);
        }
        return save(document);
    }

    private static byte[] save(XmlObject document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out, new XmlOptions().setCharacterEncoding("UTF-8"));
        return out.toByteArray();
    }
}
