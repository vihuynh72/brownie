package io.github.vihuynh72.brownie.api.document.docx;

import org.apache.poi.hpsf.ClassID;
import org.apache.poi.hpsf.ClassIDPredefined;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.xmlbeans.XmlCursor;

import javax.xml.namespace.QName;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads what an embedded object ({@code w:object}) is, and whether a file
 * may keep it. Only a document of one of a few everyday programs is kept:
 * a spreadsheet, a chart, a Word document, slides, a drawing -- none of
 * them their macro-enabled kinds. Anything else is not: a packaged file (a
 * program or script wrapped to open on a double-click), an equation from
 * the old equation editor (a long-known way into the computer that opens
 * it), a control (ActiveX), a link to a file elsewhere, and any program not
 * named. Those are what the working copy turns into their pictures.
 *
 * <p>The program named in the XML is only a label: Word starts an object
 * by what its embedded file says it is. So {@link #allowedIn} also opens
 * each file the object embeds. An Office Open XML document is kept only
 * when its declared type is a plain spreadsheet, Word document,
 * presentation, slide or drawing and it holds no macros, controls or
 * embedded files of its own; an older compound file only when its root
 * names Word 97-2003 and it holds no macros or objects of its own. Older
 * Excel, PowerPoint and Visio files, and Word's before 97, keep their macros
 * inside their main stream, where they cannot be seen from outside, so
 * those become pictures too.
 */
public final class EmbeddedObjects {

    static final String OFFICE = "urn:schemas-microsoft-com:office:office";
    static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static final QName PROG_ID = new QName("", "ProgID");
    private static final QName TYPE = new QName("", "Type");
    private static final QName RELATIONSHIP_ID = new QName(RELATIONSHIPS, "id");
    private static final QName W_PROG_ID = new QName(RunText.W, "progId");

    /** Program ids by their name without a version: {@code Excel.Sheet.12} is {@code Excel.Sheet}. */
    private static final Set<String> ALLOWED_PROGRAMS = Set.of(
            "excel.sheet", "excel.chart", "word.document", "powerpoint.show", "powerpoint.slide", "visio.drawing");
    /** The declared types of an embedded Office Open XML document that may stay; no macro-enabled type is one of them. */
    private static final Set<String> ALLOWED_PACKAGE_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.presentationml.slide",
            "application/vnd.ms-visio.drawing");
    /**
     * The program an older embedded compound file may name as its own: a Word 97-2003 document, which keeps its macros
     * and its own objects in storages that can be seen from outside. An older Excel workbook keeps its macro sheets
     * inside its main stream and its objects in storages named for each one, and Word 6 and 95 their macros inside
     * the document itself.
     */
    private static final Set<ClassIDPredefined> ALLOWED_CLASS_IDS = Set.of(ClassIDPredefined.WORD_V8);
    /** Storages of a compound file that hold macros, or objects and files of its own. */
    private static final List<String> ACTIVE_STORAGES = List.of(
            "Macros", "_VBA_PROJECT_CUR", "_VBA_PROJECT", "VBA", "ObjectPool", "\u0001Ole10Native");
    /** The start of the name of a storage in which an older Office file keeps one embedded object of its own. */
    private static final String OBJECT_STORAGE_PREFIX = "mbd";
    /** Words in an embedded package's part names or declared types that mean macros, controls or embedded files. */
    private static final List<String> ACTIVE_PACKAGE_WORDS = List.of("vbaproject", "activex", "macroenabled", "/embeddings/");
    private static final int MAX_PACKAGE_ENTRIES = 10_000;
    private static final int MAX_CONTENT_TYPES_BYTES = 1 << 20;

    private EmbeddedObjects() {
    }

    /**
     * {@code progId} is null when the object names no program. {@code
     * relationshipIds} are the object's own relationships (its embedded
     * file, its link, its control), not its preview picture's.
     */
    public record Description(String progId, boolean link, boolean control, List<String> relationshipIds) {

        public boolean allowed() {
            return !link && !control && isAllowedProgram(progId);
        }

        /** Words for a finding's detail: the program, and what makes the object one that is not kept. */
        public String detail() {
            String program = progId == null ? "an unnamed program" : progId;
            if (control) {
                return "A control (" + program + ").";
            }
            if (link) {
                return "An object linked from outside the file (" + program + ").";
            }
            return "An embedded object (" + program + ").";
        }
    }

    /** Describes the {@code w:object} the cursor is on; the cursor is left where it was. */
    public static Description describe(XmlCursor object) {
        String progId = null;
        boolean link = false;
        boolean control = false;
        List<String> relationshipIds = new ArrayList<>();
        try (XmlCursor cursor = object.newCursor()) {
            int depth = 0;
            while (true) {
                XmlCursor.TokenType token = cursor.toNextToken();
                if (token == XmlCursor.TokenType.NONE || token == XmlCursor.TokenType.ENDDOC) {
                    break;
                }
                if (token.isEnd()) {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                    continue;
                }
                if (!token.isStart()) {
                    continue;
                }
                depth++;
                QName name = cursor.getName();
                if (OFFICE.equals(name.getNamespaceURI()) && "OLEObject".equals(name.getLocalPart())) {
                    progId = firstNonNull(progId, cursor.getAttributeText(PROG_ID));
                    link |= "Link".equalsIgnoreCase(cursor.getAttributeText(TYPE));
                    addIfPresent(relationshipIds, cursor.getAttributeText(RELATIONSHIP_ID));
                } else if (RunText.W.equals(name.getNamespaceURI())) {
                    switch (name.getLocalPart()) {
                        case "objectEmbed" -> {
                            progId = firstNonNull(progId, cursor.getAttributeText(W_PROG_ID));
                            addIfPresent(relationshipIds, cursor.getAttributeText(RELATIONSHIP_ID));
                        }
                        case "objectLink" -> {
                            progId = firstNonNull(progId, cursor.getAttributeText(W_PROG_ID));
                            link = true;
                            addIfPresent(relationshipIds, cursor.getAttributeText(RELATIONSHIP_ID));
                        }
                        case "control" -> {
                            control = true;
                            addIfPresent(relationshipIds, cursor.getAttributeText(RELATIONSHIP_ID));
                        }
                        default -> {
                            // The shape, its picture and its properties say nothing about what the object is.
                        }
                    }
                }
            }
        }
        return new Description(progId, link, control, List.copyOf(relationshipIds));
    }

    /**
     * Whether an object in {@code source} (a story part) may stay: its
     * program is on the list, and every file it embeds really is a document
     * of an allowed kind with nothing in it that runs. An object that
     * embeds no file at all has nothing to start.
     */
    public static boolean allowedIn(PackagePart source, Description description) {
        if (!description.allowed()) {
            return false;
        }
        for (String relationshipId : description.relationshipIds()) {
            if (!embedsAnAllowedDocument(source, relationshipId)) {
                return false;
            }
        }
        return true;
    }

    private static boolean embedsAnAllowedDocument(PackagePart source, String relationshipId) {
        PackageRelationship relationship = source.getRelationship(relationshipId);
        if (relationship == null || relationship.getTargetMode() == TargetMode.EXTERNAL) {
            return false;
        }
        PackagePart target;
        try {
            target = source.getRelatedPart(relationship);
        } catch (InvalidFormatException | RuntimeException e) {
            return false;
        }
        if (target == null) {
            return false;
        }
        String type = relationship.getRelationshipType();
        if (type.endsWith("/package")) {
            return ALLOWED_PACKAGE_TYPES.contains(target.getContentType().strip().toLowerCase(Locale.ROOT)) && isPlainPackage(target);
        }
        if (type.endsWith("/oleObject")) {
            return isPlainCompoundFile(target);
        }
        return false;
    }

    /** An Office Open XML package whose part names and declared types say nothing of macros, controls or embedded files. */
    private static boolean isPlainPackage(PackagePart part) {
        boolean declaredTypes = false;
        try (InputStream content = part.getInputStream(); ZipInputStream zip = new ZipInputStream(content)) {
            int entries = 0;
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > MAX_PACKAGE_ENTRIES) {
                    return false;
                }
                String name = "/" + entry.getName().toLowerCase(Locale.ROOT);
                if (ACTIVE_PACKAGE_WORDS.stream().anyMatch(name::contains)) {
                    return false;
                }
                if (name.equals("/[content_types].xml")) {
                    byte[] types = zip.readNBytes(MAX_CONTENT_TYPES_BYTES);
                    String text = new String(types, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (zip.read() != -1 || ACTIVE_PACKAGE_WORDS.stream().anyMatch(text::contains)) {
                        return false;
                    }
                    declaredTypes = true;
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return declaredTypes;
    }

    /** An older compound file whose root names Word 97-2003 and that holds no macros, objects or packaged files. */
    private static boolean isPlainCompoundFile(PackagePart part) {
        try (InputStream content = part.getInputStream(); POIFSFileSystem container = new POIFSFileSystem(content)) {
            DirectoryEntry root = container.getRoot();
            ClassID classId = root.getStorageClsid();
            ClassIDPredefined program = classId == null ? null : ClassIDPredefined.lookup(classId);
            if (program == null || !ALLOWED_CLASS_IDS.contains(program)) {
                return false;
            }
            if (ACTIVE_STORAGES.stream().anyMatch(root::hasEntryCaseInsensitive)) {
                return false;
            }
            for (Entry entry : root) {
                if (entry.getName().toLowerCase(Locale.ROOT).startsWith(OBJECT_STORAGE_PREFIX)) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** True for a program on the allowed list, in any version; a macro-enabled kind has a name of its own and is not. */
    public static boolean isAllowedProgram(String progId) {
        if (progId == null) {
            return false;
        }
        String name = progId.strip().toLowerCase(Locale.ROOT);
        while (name.matches(".*\\.[0-9]+")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        return ALLOWED_PROGRAMS.contains(name);
    }

    private static String firstNonNull(String current, String candidate) {
        return current != null ? current : candidate;
    }

    private static void addIfPresent(List<String> ids, String id) {
        if (id != null && !id.isBlank()) {
            ids.add(id);
        }
    }
}
