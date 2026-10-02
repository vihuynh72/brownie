package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.core.prepare.PreparationMode;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Makes the package an ordinary Word document. A template or a
 * macro-enabled file becomes a document by its main part's content type
 * alone. Macros go (the project, its data, keyboard and toolbar
 * customizations, ActiveX controls), and so does a digital signature,
 * which the changes the other steps make would break anyway. Links to a
 * template, picture, object, frame or document outside the file are
 * removed when they point at a local or shared path; links to the internet
 * are removed only in a file the converter wrote, since an upload that has
 * them is refused before it gets here. Hyperlinks are ordinary text links
 * and always stay.
 */
final class OoxmlPackageNormalizer {

    static final String DOCUMENT_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";

    private static final Set<String> VARIANT_MAINS = Set.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml",
            "application/vnd.ms-word.document.macroEnabled.main+xml",
            "application/vnd.ms-word.template.macroEnabledTemplate.main+xml");

    /** Relationships from the main document to a macro part, and from a macro part to the parts that go with it. */
    private static final List<String> MACRO_RELATIONSHIPS = List.of(
            "vbaProject", "wordVbaData", "keyMapCustomizations", "attachedToolbars", "control", "activeXControlBinary");

    private static final Set<String> MACRO_CONTENT_TYPES = Set.of(
            "application/vnd.ms-office.vbaproject",
            "application/vnd.ms-word.vbadata+xml",
            "application/vnd.ms-word.keymapcustomizations+xml",
            "application/vnd.ms-word.attachedtoolbars",
            "application/vnd.ms-office.activex+xml",
            "application/vnd.ms-office.activex");

    private static final List<String> SIGNATURE_RELATIONSHIPS = List.of(
            "origin", "signature", "certificate");

    private static final Set<String> LINK_TYPES = Set.of("attachedTemplate", "image", "oleObject", "frame", "subDocument");

    private static final Pattern NETWORK_ADDRESS = Pattern.compile("(?i)^(https?|ftp)://.*");

    /** Elements that exist only to point at a link, removed with it. */
    private static final Set<String> LINK_ONLY_ELEMENTS = Set.of("attachedTemplate", "subDoc");

    record Result(int macroParts, int signatureParts, int links) {
    }

    private OoxmlPackageNormalizer() {
    }

    static Result normalize(WordPackage word, PreparationMode mode) {
        String mainType = word.main().getContentType();
        if (VARIANT_MAINS.contains(mainType)) {
            word.setMainContentType(DOCUMENT_MAIN);
        }
        int macroParts = removeMacros(word, mainType);
        int signatureParts = removeSignatures(word);
        int links = removeLinks(word, mode);
        return new Result(macroParts, signatureParts, links);
    }

    private static int removeMacros(WordPackage word, String mainType) {
        int removed = 0;
        List<PackagePart> sources = new ArrayList<>(word.storyParts());
        for (PackagePart source : sources) {
            for (PackageRelationship relationship : word.relationships(source)) {
                if (relationship.getTargetMode() != TargetMode.EXTERNAL
                        && MACRO_RELATIONSHIPS.stream().anyMatch(type -> WordPackage.hasType(relationship, type))) {
                    removed += word.removePart(word.related(source, relationship), MACRO_RELATIONSHIPS);
                }
            }
        }
        // Parts no relationship reaches, or reached under a name this list does not know, still carry macros.
        for (PackagePart part : word.parts()) {
            String contentType = part.getContentType().toLowerCase(Locale.ROOT);
            String name = part.getPartName().getName().toLowerCase(Locale.ROOT);
            if (MACRO_CONTENT_TYPES.contains(contentType) || name.endsWith("/vbaproject.bin") || name.startsWith("/word/activex/")) {
                removed += word.removePart(word.part(part.getPartName()), MACRO_RELATIONSHIPS);
            }
        }
        return removed;
    }

    private static int removeSignatures(WordPackage word) {
        int removed = 0;
        for (PackageRelationship relationship : word.relationships(null)) {
            String type = relationship.getRelationshipType();
            if (type != null && type.contains("digital-signature")) {
                removed += countSignatures(word, word.related(null, relationship));
                word.removePart(word.related(null, relationship), SIGNATURE_RELATIONSHIPS);
                word.removeRelationship(null, relationship.getId());
            }
        }
        for (PackagePart part : word.parts()) {
            String contentType = part.getContentType().toLowerCase(Locale.ROOT);
            if (contentType.contains("digital-signature") || part.getPartName().getName().startsWith("/_xmlsignatures/")) {
                word.removePart(word.part(part.getPartName()), SIGNATURE_RELATIONSHIPS);
                removed = Math.max(removed, 1);
            }
        }
        return removed;
    }

    /** A signature is counted by its signature parts; an origin with none still stands for one. */
    private static int countSignatures(WordPackage word, PackagePart origin) {
        if (origin == null) {
            return 1;
        }
        int signatures = (int) word.relationships(origin).stream().filter(relationship -> WordPackage.hasType(relationship, "signature")).count();
        return Math.max(signatures, 1);
    }

    private static int removeLinks(WordPackage word, PreparationMode mode) {
        int removed = 0;
        List<PackagePart> sources = new ArrayList<>();
        sources.add(null);
        sources.addAll(word.parts());
        for (PackagePart source : sources) {
            Set<String> removedIds = new HashSet<>();
            for (PackageRelationship relationship : word.relationships(source)) {
                if (relationship.getTargetMode() != TargetMode.EXTERNAL
                        || LINK_TYPES.stream().noneMatch(type -> WordPackage.hasType(relationship, type))) {
                    continue;
                }
                boolean network = NETWORK_ADDRESS.matcher(relationship.getTargetURI().toString()).matches();
                if (!network || mode == PreparationMode.CONVERTER_OUTPUT) {
                    word.removeRelationship(source, relationship.getId());
                    removedIds.add(relationship.getId());
                    removed++;
                }
            }
            if (!removedIds.isEmpty() && source != null && isXml(source)) {
                dropReferences(word.xml(source), removedIds);
            }
        }
        return removed;
    }

    private static boolean isXml(PackagePart part) {
        String contentType = part.getContentType().toLowerCase(Locale.ROOT);
        return contentType.endsWith("+xml") || contentType.endsWith("/xml");
    }

    /**
     * An element that exists only to point at a removed link goes with it;
     * elsewhere only the attribute naming the link goes, so a picture that
     * also holds its own copy keeps showing it.
     */
    static void dropReferences(XmlObject root, Set<String> removedIds) {
        List<XmlObject> referring = WordXml.elements(root, name -> true);
        for (int i = referring.size() - 1; i >= 0; i--) {
            XmlObject element = referring.get(i);
            List<QName> attributes = referringAttributes(element, removedIds);
            if (attributes.isEmpty()) {
                continue;
            }
            QName name = WordXml.nameOf(element);
            if (WordXml.W.equals(name.getNamespaceURI()) && LINK_ONLY_ELEMENTS.contains(name.getLocalPart())) {
                WordXml.remove(element);
                continue;
            }
            try (XmlCursor cursor = element.newCursor()) {
                for (QName attribute : attributes) {
                    cursor.removeAttribute(attribute);
                }
            }
        }
    }

    private static List<QName> referringAttributes(XmlObject element, Set<String> ids) {
        List<QName> attributes = new ArrayList<>();
        try (XmlCursor cursor = element.newCursor()) {
            if (cursor.toFirstAttribute()) {
                do {
                    QName name = cursor.getName();
                    if (WordXml.R.equals(name.getNamespaceURI()) && ids.contains(cursor.getTextValue())) {
                        attributes.add(name);
                    }
                } while (cursor.toNextAttribute());
            }
        }
        return attributes;
    }
}
