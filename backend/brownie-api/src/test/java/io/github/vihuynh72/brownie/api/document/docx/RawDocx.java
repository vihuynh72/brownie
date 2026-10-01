package io.github.vihuynh72.brownie.api.document.docx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a Word package straight from XML, for tests that need what the
 * library cannot build: revisions of every kind, comments with their newer
 * companion parts, macro and signature parts, ActiveX controls, and the
 * content types of templates and macro-enabled files. Every part named here
 * is declared in the content types, and every relationship is written to the
 * relationships part beside its source, the way Word lays a package out.
 */
public final class RawDocx {

    public static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    public static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    public static final String RELATIONSHIP_TYPE_BASE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    public static final String MAIN_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    public static final String HEADER_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml";
    public static final String FOOTER_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml";
    public static final String SETTINGS_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml";

    /** Every namespace the fixtures use, declared on each root the way Word declares them, with the newer ones ignorable. */
    public static final String NAMESPACES = " xmlns:w=\"" + W + "\""
            + " xmlns:r=\"" + R + "\""
            + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
            + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
            + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""
            + " xmlns:v=\"urn:schemas-microsoft-com:vml\""
            + " xmlns:o=\"urn:schemas-microsoft-com:office:office\""
            + " xmlns:w10=\"urn:schemas-microsoft-com:office:word\""
            + " xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\""
            + " xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\""
            + " xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\""
            + " xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\""
            + " mc:Ignorable=\"w14 w15\"";

    private RawDocx() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A whole XML part whose root is {@code w:<localName>}, holding {@code inner}. */
    public static String wordRoot(String localName, String inner) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:" + localName + NAMESPACES + ">" + inner
                + "</w:" + localName + ">";
    }

    public static final class Builder {

        private final Map<String, byte[]> parts = new LinkedHashMap<>();
        private final Map<String, String> overrides = new LinkedHashMap<>();
        private final Map<String, String> defaults = new LinkedHashMap<>();
        private final Map<String, List<String>> relationships = new LinkedHashMap<>();
        private String mainContentType = MAIN_CONTENT_TYPE;

        private Builder() {
            defaults.put("rels", "application/vnd.openxmlformats-package.relationships+xml");
            defaults.put("xml", "application/xml");
            defaults.put("png", "image/png");
            defaults.put("bin", "application/vnd.openxmlformats-officedocument.oleObject");
            relationship("", "rId1", RELATIONSHIP_TYPE_BASE + "officeDocument", "word/document.xml", false);
        }

        public Builder document(String bodyXml) {
            parts.put("word/document.xml", wordRoot("document", "<w:body>" + bodyXml + "</w:body>").getBytes(StandardCharsets.UTF_8));
            return this;
        }

        /** The main part's content type, for a template or a macro-enabled file. */
        public Builder mainContentType(String contentType) {
            this.mainContentType = contentType;
            return this;
        }

        public Builder part(String name, String contentType, String xml) {
            return part(name, contentType, xml.getBytes(StandardCharsets.UTF_8));
        }

        public Builder part(String name, String contentType, byte[] bytes) {
            parts.put(name, bytes);
            if (contentType != null) {
                overrides.put(name, contentType);
            }
            return this;
        }

        public Builder documentRelationship(String id, String type, String target, boolean external) {
            return relationship("word/document.xml", id, type, target, external);
        }

        public Builder packageRelationship(String id, String type, String target) {
            return relationship("", id, type, target, false);
        }

        /** {@code source} is a part name, or empty for the package's own relationships. */
        public Builder relationship(String source, String id, String type, String target, boolean external) {
            relationships.computeIfAbsent(source, ignored -> new ArrayList<>()).add(
                    "<Relationship Id=\"" + id + "\" Type=\"" + type + "\" Target=\"" + escapeAttribute(target) + "\""
                            + (external ? " TargetMode=\"External\"" : "") + "/>");
            return this;
        }

        public byte[] build() throws IOException {
            if (!parts.containsKey("word/document.xml")) {
                document("<w:p/>");
            }
            overrides.put("word/document.xml", mainContentType);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                write(zip, "[Content_Types].xml", contentTypes().getBytes(StandardCharsets.UTF_8));
                for (Map.Entry<String, List<String>> entry : relationships.entrySet()) {
                    write(zip, relationshipsPartName(entry.getKey()), relationshipsXml(entry.getValue()).getBytes(StandardCharsets.UTF_8));
                }
                for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                    write(zip, part.getKey(), part.getValue());
                }
            }
            return out.toByteArray();
        }

        private String contentTypes() {
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
            defaults.forEach((extension, type) ->
                    xml.append("<Default Extension=\"").append(extension).append("\" ContentType=\"").append(type).append("\"/>"));
            overrides.forEach((name, type) ->
                    xml.append("<Override PartName=\"/").append(name).append("\" ContentType=\"").append(type).append("\"/>"));
            return xml.append("</Types>").toString();
        }

        private static String relationshipsPartName(String source) {
            if (source.isEmpty()) {
                return "_rels/.rels";
            }
            int slash = source.lastIndexOf('/');
            return source.substring(0, slash + 1) + "_rels/" + source.substring(slash + 1) + ".rels";
        }

        private static String relationshipsXml(List<String> entries) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + String.join("", entries) + "</Relationships>";
        }

        private static void write(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(bytes);
            zip.closeEntry();
        }

        private static String escapeAttribute(String value) {
            return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
        }
    }
}
