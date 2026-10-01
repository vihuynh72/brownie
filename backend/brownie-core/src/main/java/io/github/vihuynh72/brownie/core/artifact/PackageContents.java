package io.github.vihuynh72.brownie.core.artifact;

import io.github.vihuynh72.brownie.core.artifact.UnsupportedArtifactTypeException.Reason;
import io.github.vihuynh72.brownie.core.prepare.ConvertibleFormat;

import javax.xml.stream.XMLStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What {@link ArtifactContentInspector}'s one bounded walk of a ZIP
 * package learns on the way through, and the decision made from it once
 * the walk is over. Three families of word-processing file are ZIP
 * packages, each identified by what it declares about itself rather than by
 * its name:
 *
 * <ul>
 *   <li>Office Open XML, by the content type of the part the package's own
 *   relationships name as its main document. A workbook or a presentation
 *   is the same kind of package with a different main part, so this is also
 *   where one is told apart from a Word document.</li>
 *   <li>OpenDocument, by the {@code mimetype} entry the format requires to
 *   come first, and by whether its manifest says any part is encrypted.</li>
 *   <li>Pages, by the application that wrote the document part (see
 *   {@link IworkDocumentHeader}); or, for the older format, by the root of
 *   its {@code index.xml}. A Pages document is a folder on a Mac, so one may
 *   also arrive as a ZIP of that folder, with every entry under it.</li>
 * </ul>
 */
final class PackageContents {

    private static final String OOXML_CONTENT_TYPES = "[Content_Types].xml";
    private static final String ROOT_RELATIONSHIPS = "_rels/.rels";
    private static final String ODF_MIMETYPE = "mimetype";
    private static final String ODF_MANIFEST = "META-INF/manifest.xml";
    private static final String MAC_RESOURCE_FORKS = "__MACOSX/";
    private static final int MIMETYPE_MAX_BYTES = 128;

    private final List<String> entryNames = new ArrayList<>();
    private final Map<String, String> contentTypeOverrides = new HashMap<>();
    private final Map<String, String> contentTypeDefaults = new HashMap<>();
    private final List<String> mainDocumentTargets = new ArrayList<>();
    private final Map<String, String> xmlRootNames = new HashMap<>();
    private final Map<String, IworkHead> iworkHeads = new HashMap<>();
    private String mimetype;
    private boolean encryptedParts;
    private boolean malformedPartHeld;

    /** Called for each entry before it is read, in archive order. */
    void entry(String name) {
        entryNames.add(name);
    }

    /** Whether the entries so far make this an OpenDocument package, which holds the rest of its judgement for the manifest. */
    boolean isOpenDocument() {
        return !entryNames.isEmpty() && ODF_MIMETYPE.equals(entryNames.get(0));
    }

    /** Whether {@code name}'s document type, if it has one, is the kind an OpenDocument manifest may carry. */
    boolean mayDeclareDocumentType(String name) {
        return isOpenDocument() && ODF_MANIFEST.equals(name);
    }

    /**
     * Whether a part of this name that is not well-formed is held for the
     * manifest instead of refused at once: an encrypted OpenDocument part is
     * not XML, and the manifest (usually the last entry) says which are.
     */
    boolean holdsMalformed(String name) {
        return isOpenDocument() && !ODF_MANIFEST.equals(name);
    }

    void malformedPartHeld() {
        malformedPartHeld = true;
    }

    /** Whether the walk should keep the first bytes of this entry, which is not XML, for {@link #keep}. */
    boolean wantsHead(String name) {
        return (entryNames.size() == 1 && ODF_MIMETYPE.equals(name)) || isIworkDocumentPart(name);
    }

    int headBytes(String name) {
        return ODF_MIMETYPE.equals(name) ? MIMETYPE_MAX_BYTES : IworkDocumentHeader.HEAD_BYTES;
    }

    void keep(String name, byte[] head, int length) {
        if (ODF_MIMETYPE.equals(name)) {
            mimetype = new String(head, 0, length, StandardCharsets.US_ASCII).trim();
        } else {
            iworkHeads.put(name, new IworkHead(head, length));
        }
    }

    /** What to collect while the named XML part is read. */
    PackagePartInspector.ElementListener listenerFor(String name) {
        if (OOXML_CONTENT_TYPES.equals(name)) {
            return this::contentType;
        }
        if (ROOT_RELATIONSHIPS.equals(name)) {
            return this::rootRelationship;
        }
        if (ODF_MANIFEST.equals(name)) {
            return (reader, depth) -> {
                if ("encryption-data".equals(PackagePartInspector.localName(reader.getLocalName()))) {
                    encryptedParts = true;
                }
            };
        }
        if (isIndexXml(name)) {
            return (reader, depth) -> {
                if (depth == 1) {
                    xmlRootNames.put(name, reader.getLocalName());
                }
            };
        }
        return PackagePartInspector.ElementListener.NONE;
    }

    private void contentType(XMLStreamReader reader, int depth) {
        String element = PackagePartInspector.localName(reader.getLocalName());
        if ("Override".equals(element)) {
            String partName = attribute(reader, "PartName");
            String type = attribute(reader, "ContentType");
            if (partName != null && type != null) {
                contentTypeOverrides.putIfAbsent(partName(partName), type.trim().toLowerCase(Locale.ROOT));
            }
        } else if ("Default".equals(element)) {
            String extension = attribute(reader, "Extension");
            String type = attribute(reader, "ContentType");
            if (extension != null && type != null) {
                contentTypeDefaults.putIfAbsent(extension.toLowerCase(Locale.ROOT), type.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Transitional and strict documents name their main part with relationship types that end the same way. */
    private void rootRelationship(XMLStreamReader reader, int depth) {
        if ("Relationship".equals(PackagePartInspector.localName(reader.getLocalName()))) {
            String type = attribute(reader, "Type");
            String target = attribute(reader, "Target");
            String mode = attribute(reader, "TargetMode");
            if (type != null && type.endsWith("/officeDocument") && target != null && !"External".equalsIgnoreCase(mode)) {
                mainDocumentTargets.add(partName(target));
            }
        }
    }

    /** The decision, once every entry has been read. */
    ContentInspection classify() {
        if (entryNames.contains(OOXML_CONTENT_TYPES)) {
            return classifyOfficeOpenXml();
        }
        if (isOpenDocument()) {
            return classifyOpenDocument();
        }
        return classifyIwork();
    }

    private ContentInspection classifyOfficeOpenXml() {
        if (mainDocumentTargets.isEmpty()) {
            throw refused(Reason.NOT_A_DOCUMENT, "Package is an Open Packaging archive with no main document part.");
        }
        String main = mainDocumentTargets.get(0);
        String type = contentTypeOverrides.get(main);
        if (type == null) {
            int dot = main.lastIndexOf('.');
            type = dot < 0 ? null : contentTypeDefaults.get(main.substring(dot + 1));
        }
        if (type == null) {
            throw refused(Reason.NOT_A_DOCUMENT, "Package does not declare the content type of its main document part.");
        }
        switch (type) {
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml":
                return ContentInspection.of(SupportedMediaType.DOCX);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml":
                return ContentInspection.of(SupportedMediaType.DOTX);
            case "application/vnd.ms-word.document.macroenabled.main+xml":
                return ContentInspection.of(SupportedMediaType.DOCM);
            case "application/vnd.ms-word.template.macroenabledtemplate.main+xml":
                return ContentInspection.of(SupportedMediaType.DOTM);
            default:
                break;
        }
        if (type.contains("spreadsheetml") || type.startsWith("application/vnd.ms-excel")) {
            throw refused(Reason.SPREADSHEET, "Package is a spreadsheet.");
        }
        if (type.contains("presentationml") || type.startsWith("application/vnd.ms-powerpoint")) {
            throw refused(Reason.PRESENTATION, "Package is a presentation.");
        }
        throw refused(Reason.NOT_A_DOCUMENT, "Package's main part is not a word-processing document.");
    }

    private ContentInspection classifyOpenDocument() {
        String type = mimetype == null ? "" : mimetype.toLowerCase(Locale.ROOT);
        boolean template = "application/vnd.oasis.opendocument.text-template".equals(type);
        if (!template && !"application/vnd.oasis.opendocument.text".equals(type)) {
            if (type.startsWith("application/vnd.oasis.opendocument.spreadsheet")) {
                throw refused(Reason.SPREADSHEET, "Package is an OpenDocument spreadsheet.");
            }
            if (type.startsWith("application/vnd.oasis.opendocument.presentation")) {
                throw refused(Reason.PRESENTATION, "Package is an OpenDocument presentation.");
            }
            throw refused(Reason.NOT_A_DOCUMENT, "Package is an OpenDocument file but not a text document.");
        }
        if (encryptedParts) {
            throw refused(Reason.PASSWORD_PROTECTED, "Package is an OpenDocument file whose parts are encrypted.");
        }
        if (malformedPartHeld) {
            throw refused(Reason.DAMAGED, "Package contains a part that is not well-formed XML.");
        }
        return new ContentInspection(
                SupportedMediaType.ODT, template ? ConvertibleFormat.ODT_TEMPLATE : ConvertibleFormat.ODT);
    }

    /**
     * An iWork document, read at the root or, when every entry sits in one
     * folder named like a document package, inside that folder. What is
     * inside decides; the folder's name is used only for the one layout that
     * keeps the document part in an inner archive.
     */
    private ContentInspection classifyIwork() {
        String folder = soleDocumentFolder();
        String prefix = folder == null ? "" : folder;
        for (String name : entryNames) {
            String inner = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
            if (".iwpv2".equals(inner) || ".iwph".equals(inner)) {
                throw refused(Reason.PASSWORD_PROTECTED, "Package is an iWork document locked with a password.");
            }
        }
        IworkHead head = iworkHeads.get(prefix + "Index/Document.iwa");
        if (head != null) {
            long type = IworkDocumentHeader.firstMessageType(head.bytes(), head.length());
            if (type == IworkDocumentHeader.PAGES_DOCUMENT) {
                return new ContentInspection(SupportedMediaType.PAGES, ConvertibleFormat.PAGES);
            }
            if (type == IworkDocumentHeader.UNRECOGNIZED) {
                throw refused(Reason.DAMAGED, "Package is an iWork document whose document part cannot be read.");
            }
            throw hasSlides(prefix)
                    ? refused(Reason.PRESENTATION, "Package is a Keynote presentation.")
                    : refused(Reason.SPREADSHEET, "Package is a Numbers spreadsheet.");
        }
        String rootName = xmlRootNames.get(prefix + "index.xml");
        if ("sl:document".equals(rootName)) {
            return new ContentInspection(SupportedMediaType.PAGES, ConvertibleFormat.PAGES);
        }
        if ("ls:document".equals(rootName)) {
            throw refused(Reason.SPREADSHEET, "Package is a Numbers spreadsheet.");
        }
        if (entryNames.contains(prefix + "index.apxl")) {
            throw refused(Reason.PRESENTATION, "Package is a Keynote presentation.");
        }
        if (folder != null && entryNames.contains(prefix + "Index.zip")) {
            String extension = folder.substring(folder.lastIndexOf('.') + 1, folder.length() - 1).toLowerCase(Locale.ROOT);
            switch (extension) {
                case "pages":
                    return new ContentInspection(SupportedMediaType.PAGES, ConvertibleFormat.PAGES);
                case "numbers":
                    throw refused(Reason.SPREADSHEET, "Package is a Numbers spreadsheet.");
                default:
                    throw refused(Reason.PRESENTATION, "Package is a Keynote presentation.");
            }
        }
        throw refused(Reason.NOT_A_DOCUMENT, "Package is a ZIP archive but not a recognized document.");
    }

    private boolean hasSlides(String prefix) {
        for (String name : entryNames) {
            if (name.startsWith(prefix + "Index/Slide") || name.startsWith(prefix + "Index/MasterSlide")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The one folder every entry sits in, when it is named like an iWork
     * document package ("Form.pages/"), with the resource forks a Mac adds
     * to an archive it makes left out; otherwise null.
     */
    private String soleDocumentFolder() {
        String folder = null;
        for (String name : entryNames) {
            if (name.startsWith(MAC_RESOURCE_FORKS)) {
                continue;
            }
            int slash = name.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            String first = name.substring(0, slash + 1);
            if (folder == null) {
                folder = first;
            } else if (!folder.equals(first)) {
                return null;
            }
        }
        if (folder == null) {
            return null;
        }
        String lower = folder.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pages/") || lower.endsWith(".numbers/") || lower.endsWith(".key/") ? folder : null;
    }

    /** The document part at the root, or one folder down (where a zipped package folder puts it). */
    private static boolean isIworkDocumentPart(String name) {
        return "Index/Document.iwa".equals(name) || isOneFolderDown(name, "/Index/Document.iwa");
    }

    private static boolean isIndexXml(String name) {
        return "index.xml".equals(name) || isOneFolderDown(name, "/index.xml");
    }

    private static boolean isOneFolderDown(String name, String rest) {
        return name.endsWith(rest) && name.indexOf('/') == name.length() - rest.length();
    }

    /** A part name as a relationship target or an override names it: from the package root, compared without case. */
    private static String partName(String name) {
        String trimmed = name.trim();
        while (trimmed.startsWith("/") || trimmed.startsWith("./")) {
            trimmed = trimmed.substring(trimmed.startsWith("/") ? 1 : 2);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String attribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(PackagePartInspector.localName(reader.getAttributeLocalName(i)))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    private static UnsupportedArtifactTypeException refused(Reason reason, String message) {
        return new UnsupportedArtifactTypeException(reason, message);
    }

    private record IworkHead(byte[] bytes, int length) {
    }
}
