package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.core.prepare.WorkingCopyPreparationException;
import org.apache.poi.ooxml.POIXMLTypeLoader;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One Word package opened for cleaning. Parts are always looked up by name:
 * the library swaps a part for a new object when it is written, so a part
 * object held across a write would be stale. The XML of a part is read
 * once, untyped, the first time a step asks for it, and every part read is
 * written back once, after all the steps, before the package is saved.
 *
 * <p>Relationship types are matched by their last segment ({@code
 * .../header}), so the transitional and the strict spellings of a type are
 * the same type here.
 */
final class WordPackage implements AutoCloseable {

    /** The story parts: the ones holding text a person reads, all walked by the steps. */
    private static final List<String> STORY_RELATIONSHIPS = List.of("header", "footer", "footnotes", "endnotes");

    private final OPCPackage opc;
    private final PackagePartName main;
    private final Map<PackagePartName, XmlObject> xml = new LinkedHashMap<>();
    private String mainContentType;

    private WordPackage(OPCPackage opc, PackagePartName main) {
        this.opc = opc;
        this.main = main;
    }

    static WordPackage open(byte[] bytes) {
        OPCPackage opc;
        try {
            opc = OPCPackage.open(new ByteArrayInputStream(bytes));
        } catch (IOException | InvalidFormatException | RuntimeException e) {
            throw new WorkingCopyPreparationException(
                    WorkingCopyPreparationException.Reason.DAMAGED, "The file could not be opened as a Word package.", e);
        }
        PackageRelationship officeDocument = null;
        for (PackageRelationship relationship : opc.getRelationships()) {
            if (relationship.getTargetMode() != TargetMode.EXTERNAL && hasType(relationship, "officeDocument")) {
                officeDocument = relationship;
                break;
            }
        }
        PackagePart mainPart = officeDocument == null ? null : opc.getPart(officeDocument);
        if (mainPart == null) {
            opc.revert();
            throw new WorkingCopyPreparationException(
                    WorkingCopyPreparationException.Reason.DAMAGED, "The package has no main document.");
        }
        return new WordPackage(opc, mainPart.getPartName());
    }

    static boolean hasType(PackageRelationship relationship, String lastSegment) {
        String type = relationship.getRelationshipType();
        return type != null && type.endsWith("/" + lastSegment);
    }

    OPCPackage opc() {
        return opc;
    }

    PackagePart main() {
        return part(main);
    }

    PackagePart part(PackagePartName name) {
        return opc.getPart(name);
    }

    /** The main document, then its headers, footers, footnotes and endnotes. */
    List<PackagePart> storyParts() {
        List<PackagePart> parts = new ArrayList<>();
        parts.add(main());
        for (String type : STORY_RELATIONSHIPS) {
            parts.addAll(relatedParts(main(), type));
        }
        return parts;
    }

    /** The parts {@code source} reaches through internal relationships of the type. */
    List<PackagePart> relatedParts(PackagePart source, String type) {
        List<PackagePart> parts = new ArrayList<>();
        for (PackageRelationship relationship : relationships(source)) {
            if (relationship.getTargetMode() != TargetMode.EXTERNAL && hasType(relationship, type)) {
                PackagePart target = related(source, relationship);
                if (target != null && !parts.contains(target)) {
                    parts.add(target);
                }
            }
        }
        return parts;
    }

    PackagePart related(PackagePart source, PackageRelationship relationship) {
        try {
            return source == null ? opc.getPart(relationship) : source.getRelatedPart(relationship);
        } catch (InvalidFormatException | RuntimeException e) {
            return null;
        }
    }

    /** A copy of the relationships of {@code source}, or of the package itself when it is null. */
    List<PackageRelationship> relationships(PackagePart source) {
        List<PackageRelationship> relationships = new ArrayList<>();
        try {
            (source == null ? opc.getRelationships() : source.getRelationships()).forEach(relationships::add);
        } catch (InvalidFormatException e) {
            throw new WorkingCopyPreparationException(
                    WorkingCopyPreparationException.Reason.DAMAGED, "A part's relationships could not be read.", e);
        }
        return relationships;
    }

    void removeRelationship(PackagePart source, String id) {
        if (source == null) {
            opc.removeRelationship(id);
        } else {
            source.removeRelationship(id);
        }
    }

    /**
     * Removes a part, every relationship any part (or the package) has to
     * it, and the parts it reaches whose relationship type {@code
     * alsoRemoved} names (a macro project's data, a control's binary, a
     * signature's certificate). Returns how many parts went.
     */
    int removePart(PackagePart part, List<String> alsoRemoved) {
        return removePart(part, alsoRemoved, new HashSet<>());
    }

    private int removePart(PackagePart part, List<String> alsoRemoved, Set<PackagePartName> visited) {
        if (part == null || !visited.add(part.getPartName())) {
            return 0;
        }
        PackagePartName name = part.getPartName();
        int removed = 0;
        for (PackageRelationship relationship : relationships(part)) {
            if (relationship.getTargetMode() != TargetMode.EXTERNAL && alsoRemoved.stream().anyMatch(type -> hasType(relationship, type))) {
                removed += removePart(related(part, relationship), alsoRemoved, visited);
            }
        }
        PackagePart current = part(name);
        if (current == null) {
            return removed;
        }
        removeRelationshipsTo(name);
        xml.remove(name);
        opc.removePart(name);
        return removed + 1;
    }

    private void removeRelationshipsTo(PackagePartName target) {
        List<PackagePart> sources = new ArrayList<>();
        sources.add(null);
        sources.addAll(parts());
        for (PackagePart source : sources) {
            if (source != null && !source.hasRelationships()) {
                continue;
            }
            for (PackageRelationship relationship : relationships(source)) {
                if (relationship.getTargetMode() == TargetMode.EXTERNAL) {
                    continue;
                }
                PackagePart related = related(source, relationship);
                if (related != null && related.getPartName().equals(target)) {
                    removeRelationship(source, relationship.getId());
                }
            }
        }
    }

    /** Every part but the relationship parts. */
    List<PackagePart> parts() {
        try {
            return opc.getParts().stream().filter(candidate -> !candidate.isRelationshipPart()).toList();
        } catch (InvalidFormatException e) {
            throw new WorkingCopyPreparationException(
                    WorkingCopyPreparationException.Reason.DAMAGED, "The package's parts could not be listed.", e);
        }
    }

    /** The part's XML, read once; changes to it are written when the package is saved. */
    XmlObject xml(PackagePart part) {
        return xml.computeIfAbsent(part.getPartName(), name -> parse(part));
    }

    private static XmlObject parse(PackagePart part) {
        XmlOptions options = new XmlOptions(POIXMLTypeLoader.DEFAULT_XML_OPTIONS);
        try (InputStream in = part.getInputStream()) {
            return XmlObject.Factory.parse(in, options);
        } catch (IOException | XmlException e) {
            throw new WorkingCopyPreparationException(
                    WorkingCopyPreparationException.Reason.DAMAGED, "Part " + part.getPartName() + " is not well-formed XML.", e);
        }
    }

    /**
     * The main part's content type is set in the saved package's content
     * types, not through the library: asked to change one part's type, the
     * library can rewrite the default for every part with the same
     * extension, which would give the file's other XML parts the main
     * document's type.
     */
    void setMainContentType(String contentType) {
        this.mainContentType = contentType;
    }

    byte[] save() {
        XmlOptions options = new XmlOptions().setCharacterEncoding("UTF-8");
        try {
            for (Map.Entry<PackagePartName, XmlObject> entry : xml.entrySet()) {
                PackagePart part = part(entry.getKey());
                if (part == null) {
                    continue;
                }
                try (OutputStream out = part.getOutputStream()) {
                    entry.getValue().save(out, options);
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            opc.save(bytes);
            return mainContentType == null ? bytes.toByteArray() : ContentTypes.withOverride(bytes.toByteArray(), main.getName(), mainContentType);
        } catch (IOException e) {
            throw new IllegalStateException("The working copy could not be written in memory.", e);
        }
    }

    @Override
    public void close() {
        opc.revert();
    }
}
