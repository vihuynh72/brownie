package io.github.vihuynh72.brownie.core.artifact;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one XML part of an uploaded package the way an attacker hopes
 * nobody does: before any real parser sees it. It refuses a part that
 * declares a document type (no part of a Word document has one, and it is
 * the door to entity expansion and external entities), a part nested deeper
 * than any real document is, and a relationships part that points at
 * something on a network other than as an ordinary hyperlink, which is how
 * a document asks whoever opens it to fetch a remote template, object or
 * frame (in a file a person gave, that is: see {@link PackagePolicy}). A
 * relationship to a path on the author's own machine is left alone: nearly
 * every document written in Word carries one, naming the template it was
 * started from.
 *
 * <p>It understands no more of the format than that. It is not namespace
 * aware on purpose: all it needs is how deep the elements go and three
 * attributes of one element, and a part with an unusual prefix is not a
 * reason to refuse a file. What the caller wants to learn of a part (the
 * content types a package declares, say) it collects through an {@link
 * ElementListener} while the part is read here, so no part is read twice.
 */
final class PackagePartInspector {

    /** Real documents nest a few dozen levels (tables within tables within text boxes); this is far beyond any of them. */
    static final int MAX_ELEMENT_DEPTH = 256;

    private static final String HYPERLINK_TYPE_SUFFIX = "/hyperlink";

    private PackagePartInspector() {
    }

    /**
     * Which parts are read here is decided by name. Besides the two
     * endings every package uses, that is the two under which the .NET
     * packaging library stores document properties and signatures, which
     * is how a great many generated documents arrive. A part declared as
     * XML under any other name is not read here; the document library is
     * the only thing in this system that would read it, and it refuses a
     * document type and entity expansion itself. Going by how a part
     * begins instead was tried and given up: it takes an embedded SVG
     * picture, which may carry a document type quite innocently, for part
     * of the document.
     */
    static boolean isNamedAsXml(String entryName) {
        String name = entryName.toLowerCase(Locale.ROOT);
        return name.endsWith(".xml") || name.endsWith(".rels") || name.endsWith(".psmdcp") || name.endsWith(".psdsxs");
    }

    /**
     * Reads the part to its end through {@code part}, which the caller has
     * already bounded, and leaves the stream open: closing it would close
     * the archive it is one entry of.
     *
     * <p>{@code documentTypeAllowed} is for the one part of one kind of
     * package that carries a document type innocently: the manifest of an
     * OpenDocument file written by OpenOffice 2, which names the manifest's
     * public DTD. Even there, a document type that declares anything of its
     * own (an internal subset, which is where entities are declared) is
     * refused; the named DTD is never fetched, here or anywhere.
     */
    static void inspect(
            String entryName,
            InputStream part,
            PackagePolicy policy,
            boolean documentTypeAllowed,
            ElementListener listener) throws IOException {
        boolean relationships = entryName.toLowerCase(Locale.ROOT).endsWith(".rels")
                && policy == PackagePolicy.UPLOAD;
        XMLStreamReader reader = null;
        try {
            reader = factory().createXMLStreamReader(new UncloseableInputStream(part));
            int depth = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD && !(documentTypeAllowed && declaresNothing(reader.getText()))) {
                    throw new UnsupportedArtifactTypeException(
                            UnsupportedArtifactTypeException.Reason.DAMAGED,
                            "Package contains a part that declares a document type.");
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth > MAX_ELEMENT_DEPTH) {
                        throw new UnsupportedArtifactTypeException(
                                UnsupportedArtifactTypeException.Reason.DAMAGED,
                                "Package contains a part nested more than " + MAX_ELEMENT_DEPTH + " elements deep.");
                    }
                    if (relationships && "Relationship".equals(localName(reader.getLocalName()))) {
                        requireInternalOrHyperlink(reader);
                    }
                    listener.startElement(reader, depth);
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                }
            }
        } catch (XMLStreamException e) {
            // Not the parser's message: it quotes the document.
            throw new MalformedPartException();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // Nothing was written and the underlying stream is not ours to close.
                }
            }
        }
    }

    private static void requireInternalOrHyperlink(XMLStreamReader reader) {
        String targetMode = null;
        String type = null;
        String target = null;
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String attribute = localName(reader.getAttributeLocalName(i));
            if ("TargetMode".equals(attribute)) {
                targetMode = reader.getAttributeValue(i);
            } else if ("Type".equals(attribute)) {
                type = reader.getAttributeValue(i);
            } else if ("Target".equals(attribute)) {
                target = reader.getAttributeValue(i);
            }
        }
        boolean hyperlink = type != null && type.endsWith(HYPERLINK_TYPE_SUFFIX);
        if ("External".equalsIgnoreCase(targetMode) && !hyperlink && isOnANetwork(target)) {
            throw new UnsupportedArtifactTypeException(
                    UnsupportedArtifactTypeException.Reason.REMOTE_CONTENT,
                    "Package refers to content on a network other than as an ordinary hyperlink.");
        }
    }

    /**
     * A document type with no internal subset only names a DTD, which is
     * never read; one with a subset declares entities or elements itself.
     * Whatever follows the name is refused if it opens a subset anywhere,
     * inside a quoted identifier included, which no real manifest has.
     */
    private static boolean declaresNothing(String documentType) {
        return documentType != null && documentType.indexOf('[') < 0;
    }

    /**
     * Anything that is not plainly a place on the author's own disk. It is
     * written as a list of what is allowed, not of what is refused, because
     * the ways of naming a network location are open-ended (a scheme inside
     * a scheme, as in {@code mhtml:http://...} or {@code ms-word:ofe|u|...};
     * a share written as {@code file:////host/share}; schemes nobody has
     * thought of yet) and a list of those would always be one short. Three
     * shapes are local: a {@code file:} URL with exactly one or exactly
     * three slashes (or naming {@code localhost}), a Windows drive path,
     * and a bare path that begins neither like a share nor like a scheme.
     * Escapes are undone and
     * backslashes read as slashes first, as the programs that follow these
     * targets do; a target whose escapes cannot be undone is not local.
     */
    static boolean isOnANetwork(String target) {
        if (target == null) {
            return false;
        }
        String value;
        try {
            value = URLDecoder.decode(target, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return true;
        }
        value = value.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        if (value.isEmpty()) {
            return false;
        }
        if (value.startsWith("file://localhost/")) {
            // This machine, by name; but a second slash after it begins a share all the same.
            return value.startsWith("file://localhost//");
        }
        if (value.startsWith("file:")) {
            int slashes = 0;
            while ("file:".length() + slashes < value.length() && value.charAt("file:".length() + slashes) == '/') {
                slashes++;
            }
            return slashes != 1 && slashes != 3;
        }
        if (value.startsWith("//")) {
            return true;
        }
        // Whatever begins like a scheme is somewhere else, unless the "scheme" is one letter, which is a drive. A
        // colon further in (a range of cells in a linked workbook, say) makes nothing a network address.
        Matcher scheme = SCHEME.matcher(value);
        return scheme.lookingAt() && scheme.end() != 2;
    }

    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]*:");

    /** Without namespace awareness a prefixed name arrives whole; only what follows the colon matters here. */
    static String localName(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static XMLInputFactory factory() {
        // The JDK's own reader, not whichever one the class path offers: what is refused must not depend on
        // which libraries the application was packaged with.
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        factory.setProperty(XMLInputFactory.IS_VALIDATING, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        // Left on so that a document type is reported as an event and refused, instead of being a parse error
        // indistinguishable from any other; nothing in it is ever resolved or expanded.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, true);
        // Without this the reader asks the resolver below for a named external DTD before it reports the document
        // type at all, so a manifest that names one could not be told from a part that is not XML.
        factory.setProperty(IGNORE_EXTERNAL_DTD, true);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External resources are never fetched.");
        });
        return factory;
    }

    /** The JDK reader's own switch; {@link #factory} always asks for the JDK's reader, so it is always understood. */
    private static final String IGNORE_EXTERNAL_DTD = "http://java.sun.com/xml/stream/properties/ignore-external-dtd";

    /**
     * Told of each element as it starts, with the reader positioned on it
     * and its depth (the root is 1), so the caller can read its name and
     * attributes. It must not move the reader.
     */
    interface ElementListener {

        ElementListener NONE = (reader, depth) -> {
        };

        void startElement(XMLStreamReader reader, int depth);
    }

    /**
     * A part that is not well-formed XML. Kept apart from the other
     * refusals because one kind of package stores some parts encrypted,
     * and whether a part is damaged or only encrypted is known only once
     * that package's manifest has been read.
     */
    static final class MalformedPartException extends UnsupportedArtifactTypeException {

        private MalformedPartException() {
            super(Reason.DAMAGED, "Package contains a part that is not well-formed XML.");
        }
    }

    private static final class UncloseableInputStream extends FilterInputStream {

        private UncloseableInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // The part is one entry of an archive the caller is still reading.
        }
    }
}
