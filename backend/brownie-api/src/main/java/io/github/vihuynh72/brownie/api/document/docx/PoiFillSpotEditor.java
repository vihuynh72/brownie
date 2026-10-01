package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxParseException;
import io.github.vihuynh72.brownie.core.prepare.AnchorPlacement;
import io.github.vihuynh72.brownie.core.prepare.BlankLines;
import io.github.vihuynh72.brownie.core.prepare.DocxAnchor;
import io.github.vihuynh72.brownie.core.prepare.EditedDocx;
import io.github.vihuynh72.brownie.core.prepare.FillSpotEditor;
import io.github.vihuynh72.brownie.core.prepare.FillSpotPlacementException;
import io.github.vihuynh72.brownie.core.prepare.SpotEdit;
import io.github.vihuynh72.brownie.core.template.FieldIds;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;

import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The one editor that makes a place in a Word file a fill spot, and takes
 * a spot away again. A spot is always an inline content control ({@code
 * w:sdt}) that is a direct child of a paragraph of the main body or of a
 * top-level table cell, tagged with the field id ({@code w:tag}), titled
 * with its label ({@code w:alias}), given a fresh random id ({@code w:id},
 * unique in the file) and marked as plain text ({@code w:text}): that is
 * the one kind of control the filler writes into. A cell-level control is
 * never made, because the filler does not reach one.
 *
 * <p>Every place an edit names is found in the file as it was given, so
 * edits never see each other's changes: the anchors of one request are all
 * read from the same extraction. Spots are made first, each paragraph's
 * from its end backwards, so making one never moves another's place; then
 * the edits that name a control (Retag, Unwrap, Untag), in the order given.
 *
 * <p>How each {@link AnchorPlacement} is made:
 * <ul>
 * <li>{@code REPLACE}: the runs covering the text between {@code start}
 * and {@code end} are split where the place begins and ends (each half
 * keeping the run's formatting) and moved, as they are, into the control.
 * The filler writes a value into the first of them, so the value takes its
 * formatting: an underlined blank gives an underlined answer. When the
 * text is a bracketed prompt ({@code [Company]}), the control is marked as
 * showing its placeholder ({@code w:showingPlcHdr}), which the filler
 * clears once a value is written.</li>
 * <li>{@code AT}: one empty run goes into the control, formatted with the
 * font, size, colour and language of the run just before the place (or
 * just after it, at the start of a line, or the paragraph mark's when the
 * line is empty), but not its bold, italics, underline or capitals. When
 * the place is the end of a line that ends with a colon, or is just before
 * a tab and just after a word, one space is put before the control,
 * outside it, so a value does not run into its label.</li>
 * <li>{@code WHOLE_LINE}: a {@code REPLACE} of the whole line when it holds
 * only a blank (underscores, dots, dashes, a bracketed prompt, or nothing;
 * see {@link BlankLines}), and an {@code AT} at its end otherwise.</li>
 * <li>{@code EXISTING_CONTROL} and {@link SpotEdit.Retag}: the control's tag
 * and title are set, and nothing else about it changes.</li>
 * </ul>
 *
 * <p>{@link SpotEdit.Unwrap} puts a control's runs back where it was and
 * drops the empty run an {@code AT} made; {@link SpotEdit.Untag} takes a
 * control's tag and title off and leaves the control. Both act on every
 * control with the tag.
 *
 * <p>Refused, with nothing written ({@link FillSpotPlacementException}): a
 * place in a header or footer ({@code HEADER_FOOTER}); an anchor read from
 * another reader version, or from a paragraph whose text has changed since
 * ({@code ANCHOR_STALE}); a paragraph, control or tag that is not there
 * ({@code NOT_FOUND}); a place whose edge falls inside a link, or a
 * replaced text that holds one ({@code INSIDE_LINK}); a place inside a
 * field Word works out, or a replaced text holding a field's own marks
 * ({@code INSIDE_FIELD_CODE}); and a replaced text that holds a control or
 * a picture of the form's own ({@code PROTECTED}). A tag that is not a safe
 * field id is a caller's mistake ({@link IllegalArgumentException}).
 */
public final class PoiFillSpotEditor implements FillSpotEditor {

    private static final String W = RunText.W;
    private static final QName W_VAL = new QName(W, "val");
    private static final QName FLD_CHAR_TYPE = new QName(W, "fldCharType");
    /** The formatting an inserted spot takes from its neighbour, in the order a run's properties are written. */
    private static final List<String> NEIGHBOUR_PROPERTIES = List.of("rFonts", "color", "sz", "szCs", "lang");
    /** What may sit between the runs a replaced place covers and move into the control with them. */
    private static final Set<String> MOVABLE = Set.of("r", "bookmarkStart", "bookmarkEnd", "proofErr", "permStart", "permEnd");

    @Override
    public EditedDocx apply(byte[] docx, List<SpotEdit> edits) {
        XWPFDocument document;
        try {
            document = new XWPFDocument(new ByteArrayInputStream(docx));
        } catch (IOException | RuntimeException e) {
            throw new DocxParseException("The file to edit is not a readable Word document.", e);
        }
        try (document) {
            DocxNodeLocator locator = new DocxNodeLocator(document);
            List<PlannedInsert> inserts = new ArrayList<>();
            List<Runnable> controlEdits = new ArrayList<>();
            for (SpotEdit edit : edits) {
                switch (edit) {
                    case SpotEdit.Insert insert -> plan(locator, insert, inserts, controlEdits);
                    case SpotEdit.Retag retag -> controlEdits.add(retag(locator, retag.part(), retag.controlNodeId(), retag.tag(), retag.alias()));
                    case SpotEdit.Unwrap unwrap -> controlEdits.add(unwrap(locator, unwrap.tag()));
                    case SpotEdit.Untag untag -> controlEdits.add(untag(locator, untag.tag()));
                }
            }
            requireNoOverlap(inserts);
            Ids ids = new Ids(document);
            Map<CTP, Integer> order = new IdentityHashMap<>();
            inserts.forEach(insert -> order.putIfAbsent(insert.paragraph(), order.size()));
            inserts.sort(Comparator.<PlannedInsert>comparingInt(insert -> order.get(insert.paragraph()))
                    .thenComparing(Comparator.comparingInt(PlannedInsert::start).reversed())
                    .thenComparing(Comparator.comparingInt(PlannedInsert::end).reversed()));
            for (PlannedInsert insert : inserts) {
                carryOut(insert, ids);
            }
            controlEdits.forEach(Runnable::run);
            return new EditedDocx(toBytes(document));
        } catch (IOException e) {
            throw new DocxParseException("The edited Word document could not be written.", e);
        }
    }

    // ---------------------------------------------------------------- finding every place before changing anything

    /** One spot to make: its paragraph, where in its anchor text, and what to call it. */
    private record PlannedInsert(
            CTP paragraph, int start, int end, boolean replace, String tag, String alias, boolean placeholder, String anchorText) {
    }

    private void plan(DocxNodeLocator locator, SpotEdit.Insert insert, List<PlannedInsert> inserts, List<Runnable> controlEdits) {
        DocxAnchor anchor = insert.anchor();
        requireSafeTag(insert.tag());
        if (anchor.part() != DocumentPartKind.MAIN_DOCUMENT) {
            throw refused(FillSpotPlacementException.Reason.HEADER_FOOTER, "Spots are made only in the body of the document.");
        }
        if (!PoiDocxStructuralExtractor.PARSER_VERSION.equals(anchor.parserVersion())) {
            throw refused(FillSpotPlacementException.Reason.ANCHOR_STALE,
                    "The place was chosen on a reading of the document by " + anchor.parserVersion() + ".");
        }
        if (anchor.placement() == AnchorPlacement.EXISTING_CONTROL) {
            if (anchor.paragraphNodeId() != null && anchor.anchorTextHash() != null) {
                requireUnchanged(locator, anchor);
            }
            controlEdits.add(retag(locator, anchor.part(), anchor.controlNodeId(), insert.tag(), insert.alias()));
            return;
        }
        DocxNodeLocator.LocatedParagraph located = requireUnchanged(locator, anchor);
        String text = DocxNodeLocator.anchorText(located.paragraph());
        int length = text.codePointCount(0, text.length());
        if (anchor.end() > length) {
            throw refused(FillSpotPlacementException.Reason.ANCHOR_STALE, "The place is past the end of its line.");
        }
        int start = anchor.start();
        int end = anchor.end();
        boolean replace = anchor.placement() == AnchorPlacement.REPLACE && start < end;
        if (anchor.placement() == AnchorPlacement.WHOLE_LINE) {
            boolean onlyABlank = BlankLines.isOnlyABlank(text);
            start = onlyABlank ? 0 : length;
            end = length;
            replace = onlyABlank && length > 0;
        } else if (!replace) {
            end = start;
        }
        String covered = replace ? text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end)) : "";
        inserts.add(new PlannedInsert(located.paragraph().paragraph().getCTP(), start, end, replace, insert.tag(),
                insert.alias(), replace && BlankLines.isBracketed(covered), text));
    }

    private static DocxNodeLocator.LocatedParagraph requireUnchanged(DocxNodeLocator locator, DocxAnchor anchor) {
        DocxNodeLocator.LocatedParagraph located = locator.paragraph(DocumentPartKind.MAIN_DOCUMENT, anchor.paragraphNodeId())
                .orElseThrow(() -> refused(FillSpotPlacementException.Reason.NOT_FOUND,
                        "There is no paragraph " + anchor.paragraphNodeId() + "."));
        String text = DocxNodeLocator.anchorText(located.paragraph());
        if (!DocxAnchor.hashOf(text).equals(anchor.anchorTextHash())) {
            throw refused(FillSpotPlacementException.Reason.ANCHOR_STALE, "The line's text has changed since the place was chosen.");
        }
        return located;
    }

    private static void requireNoOverlap(List<PlannedInsert> inserts) {
        for (int i = 0; i < inserts.size(); i++) {
            for (int j = i + 1; j < inserts.size(); j++) {
                PlannedInsert a = inserts.get(i);
                PlannedInsert b = inserts.get(j);
                boolean overlap = a.paragraph() == b.paragraph()
                        && (a.replace() || b.replace())
                        && a.start() < b.end() && b.start() < a.end();
                if (overlap) {
                    throw new IllegalArgumentException("Two places overlap in one line; each place must be separate.");
                }
            }
        }
    }

    // ---------------------------------------------------------------- making one spot

    /** A run or link-run of the paragraph as it is now, where its text sits, and how deep in fields it is. */
    private record Segment(CTR run, XmlObject top, CTHyperlink link, int start, int end, int depthBefore, int depthAfter,
                           boolean fieldMarks) {

        boolean hasText() {
            return end > start;
        }
    }

    private void carryOut(PlannedInsert insert, Ids ids) {
        CTP paragraph = insert.paragraph();
        if (insert.replace()) {
            splitAt(paragraph, insert.end());
            splitAt(paragraph, insert.start());
            replace(paragraph, insert, ids);
        } else {
            at(paragraph, insert, ids);
        }
    }

    /** Splits the run the point falls strictly inside, if any, refusing a run in a link or inside a field. */
    private static void splitAt(CTP paragraph, int point) {
        for (Segment segment : segments(paragraph)) {
            if (segment.start() < point && point < segment.end()) {
                requireSplittable(segment);
                RunPieces.split(segment.run(), point - segment.start());
                return;
            }
        }
    }

    private static void requireSplittable(Segment segment) {
        if (segment.link() != null) {
            throw refused(FillSpotPlacementException.Reason.INSIDE_LINK, "The place starts or ends inside a link.");
        }
        if (segment.depthBefore() > 0 || segment.fieldMarks()) {
            throw refused(FillSpotPlacementException.Reason.INSIDE_FIELD_CODE, "The place starts or ends inside a field.");
        }
    }

    private void replace(CTP paragraph, PlannedInsert insert, Ids ids) {
        List<Segment> segments = segments(paragraph);
        List<Segment> covered = segments.stream()
                .filter(segment -> segment.hasText() && segment.start() >= insert.start() && segment.end() <= insert.end())
                .toList();
        if (covered.isEmpty()) {
            throw refused(FillSpotPlacementException.Reason.NOT_FOUND, "No text is where the place was chosen.");
        }
        Segment first = covered.getFirst();
        Segment last = covered.getLast();
        if (first.depthBefore() > 0) {
            throw refused(FillSpotPlacementException.Reason.INSIDE_FIELD_CODE, "The place is inside a field.");
        }
        List<XmlObject> moved = childrenBetween(paragraph, first.top(), last.top());
        for (XmlObject child : moved) {
            requireMovable(child);
        }
        CTSdtRun sdt = newControl(insert.tag(), insert.alias(), insert.placeholder(), ids);
        CTSdtRun placed = placeBefore(paragraph, first.top(), sdt);
        CTSdtContentRun content = placed.getSdtContent();
        try (XmlCursor into = content.newCursor()) {
            into.toEndToken();
            for (XmlObject child : moved) {
                try (XmlCursor from = child.newCursor()) {
                    from.moveXml(into);
                }
            }
        }
    }

    private static void requireMovable(XmlObject child) {
        QName name = nameOf(child);
        String local = W.equals(name.getNamespaceURI()) ? name.getLocalPart() : "";
        if (local.equals("hyperlink")) {
            throw refused(FillSpotPlacementException.Reason.INSIDE_LINK, "The place holds a link.");
        }
        if (local.equals("fldSimple")) {
            throw refused(FillSpotPlacementException.Reason.INSIDE_FIELD_CODE, "The place holds a field.");
        }
        if (!MOVABLE.contains(local)) {
            throw refused(FillSpotPlacementException.Reason.PROTECTED, "The place holds something that cannot go in a spot (" + local + ").");
        }
        if (child instanceof CTR run) {
            if (hasFieldMarks(run)) {
                throw refused(FillSpotPlacementException.Reason.INSIDE_FIELD_CODE, "The place holds a field.");
            }
            if (holds(run, "drawing", "pict", "object")) {
                throw refused(FillSpotPlacementException.Reason.PROTECTED, "The place holds a picture.");
            }
        }
    }

    private void at(CTP paragraph, PlannedInsert insert, Ids ids) {
        int point = insert.start();
        List<Segment> segments = segments(paragraph);
        Segment inside = null;
        for (Segment segment : segments) {
            if (segment.start() < point && point < segment.end()) {
                inside = segment;
            }
        }
        Segment before = null;
        Segment after = null;
        if (inside != null) {
            requireSplittable(inside);
            CTR right = RunPieces.split(inside.run(), point - inside.start());
            before = inside;
            after = new Segment(right, right, null, point, inside.end(), inside.depthAfter(), inside.depthAfter(), false);
        } else {
            for (Segment segment : segments) {
                if (segment.hasText() && segment.end() <= point) {
                    before = segment;
                }
                if (segment.hasText() && segment.start() >= point && after == null) {
                    after = segment;
                }
            }
            if (before != null && after != null && before.link() != null && before.link() == after.link()) {
                throw refused(FillSpotPlacementException.Reason.INSIDE_LINK, "The place is inside a link.");
            }
            int depth = before != null ? before.depthAfter() : startDepth(paragraph);
            if (depth > 0) {
                throw refused(FillSpotPlacementException.Reason.INSIDE_FIELD_CODE, "The place is inside a field.");
            }
        }

        XmlObject formatting = before != null ? before.run().getRPr() : after != null ? after.run().getRPr() : paragraphMark(paragraph);
        CTSdtRun sdt = newControl(insert.tag(), insert.alias(), false, ids);
        CTR empty = sdt.getSdtContent().addNewR();
        copyNeighbourFormatting(formatting, empty);
        CTText text = empty.addNewT();
        text.setStringValue("");
        RunPieces.preserveSpace(text);

        XmlObject next = before != null ? nextSibling(before.top()) : firstContent(paragraph);
        CTSdtRun placed = next != null ? placeBefore(paragraph, next, sdt) : placeAtEnd(paragraph, sdt);
        if (needsSpaceBefore(insert.anchorText(), point)) {
            CTR space = placeRunBefore(paragraph, placed);
            copyNeighbourFormatting(formatting, space);
            CTText spaceText = space.addNewT();
            spaceText.setStringValue(" ");
            RunPieces.preserveSpace(spaceText);
        }
    }

    /**
     * Whether a value made at {@code point} would run into the label before
     * it: at the end of a line after a colon, or just before a tab (which
     * draws its line from wherever the value ends) after anything but a
     * space.
     */
    private static boolean needsSpaceBefore(String line, int point) {
        if (point <= 0) {
            return false;
        }
        int at = line.offsetByCodePoints(0, point);
        int last = line.codePointBefore(at);
        if (at == line.length()) {
            return last == ':' || last == '\uFF1A';
        }
        return line.codePointAt(at) == '\t' && !Character.isWhitespace(last) && !Character.isSpaceChar(last);
    }

    // ---------------------------------------------------------------- controls the form already has

    private Runnable retag(DocxNodeLocator locator, DocumentPartKind part, String controlNodeId, String tag, String alias) {
        requireSafeTag(tag);
        if (part != DocumentPartKind.MAIN_DOCUMENT) {
            throw refused(FillSpotPlacementException.Reason.HEADER_FOOTER, "Spots are made only in the body of the document.");
        }
        CTSdtRun sdt = locator.control(DocumentPartKind.MAIN_DOCUMENT, controlNodeId)
                .orElseThrow(() -> refused(FillSpotPlacementException.Reason.NOT_FOUND, "There is no control " + controlNodeId + "."))
                .control().sdt();
        return () -> {
            CTSdtPr properties = sdt.isSetSdtPr() ? sdt.getSdtPr() : sdt.addNewSdtPr();
            (properties.isSetAlias() ? properties.getAlias() : properties.addNewAlias()).setVal(alias);
            (properties.isSetTag() ? properties.getTag() : properties.addNewTag()).setVal(tag);
        };
    }

    private Runnable unwrap(DocxNodeLocator locator, String tag) {
        List<CTSdtRun> controls = tagged(locator, tag);
        return () -> {
            for (CTSdtRun sdt : controls) {
                List<XmlObject> children = sdt.isSetSdtContent() ? childrenOf(sdt.getSdtContent()) : List.of();
                try (XmlCursor to = sdt.newCursor()) {
                    for (XmlObject child : children) {
                        if (child instanceof CTR run && isEmpty(run)) {
                            continue;
                        }
                        try (XmlCursor from = child.newCursor()) {
                            from.moveXml(to);
                        }
                    }
                }
                RunPieces.remove(sdt);
            }
        };
    }

    private Runnable untag(DocxNodeLocator locator, String tag) {
        List<CTSdtRun> controls = tagged(locator, tag);
        return () -> {
            for (CTSdtRun sdt : controls) {
                CTSdtPr properties = sdt.getSdtPr();
                properties.unsetTag();
                if (properties.isSetAlias()) {
                    properties.unsetAlias();
                }
            }
        };
    }

    private static List<CTSdtRun> tagged(DocxNodeLocator locator, String tag) {
        List<CTSdtRun> controls = locator.controlsTagged(tag).stream().map(located -> located.control().sdt()).toList();
        if (controls.isEmpty()) {
            throw refused(FillSpotPlacementException.Reason.NOT_FOUND, "No spot is tagged \"" + tag + "\".");
        }
        return controls;
    }

    // ---------------------------------------------------------------- reading a paragraph as it is now

    /**
     * The paragraph's runs and link runs with where their text sits and
     * how many fields they are inside. A field may begin in an earlier
     * paragraph (a table of contents spans many), which shows here as a
     * field end with no beginning; the paragraph then starts inside it.
     */
    private static List<Segment> segments(CTP paragraph) {
        record Raw(CTR run, XmlObject top, CTHyperlink link, int width, int change, boolean marks) {
        }
        List<Raw> raws = new ArrayList<>();
        for (XmlObject child : childrenOf(paragraph)) {
            if (child instanceof CTR run) {
                raws.add(new Raw(run, run, null, widthOf(run), fieldChange(run), hasFieldMarks(run)));
            } else if (child instanceof CTHyperlink link) {
                for (CTR run : link.getRArray()) {
                    raws.add(new Raw(run, link, link, widthOf(run), fieldChange(run), hasFieldMarks(run)));
                }
            }
        }
        int depth = 0;
        int lowest = 0;
        for (Raw raw : raws) {
            depth += raw.change();
            lowest = Math.min(lowest, depth);
        }
        List<Segment> segments = new ArrayList<>();
        int offset = 0;
        depth = -lowest;
        for (Raw raw : raws) {
            int after = depth + raw.change();
            segments.add(new Segment(raw.run(), raw.top(), raw.link(), offset, offset + raw.width(), depth, after, raw.marks()));
            offset += raw.width();
            depth = after;
        }
        return segments;
    }

    private static int startDepth(CTP paragraph) {
        List<Segment> segments = segments(paragraph);
        return segments.isEmpty() ? 0 : segments.getFirst().depthBefore();
    }

    private static int widthOf(CTR run) {
        String shown = DocxNodeLocator.shownText(run);
        return shown.codePointCount(0, shown.length());
    }

    /** How the run moves the field depth: +1 for each field start, -1 for each end. */
    private static int fieldChange(CTR run) {
        int change = 0;
        try (XmlCursor cursor = run.newCursor()) {
            if (cursor.toFirstChild()) {
                do {
                    if (isW(cursor.getName(), "fldChar")) {
                        String type = cursor.getAttributeText(FLD_CHAR_TYPE);
                        if ("begin".equals(type)) {
                            change++;
                        } else if ("end".equals(type)) {
                            change--;
                        }
                    }
                } while (cursor.toNextSibling());
            }
        }
        return change;
    }

    private static boolean hasFieldMarks(CTR run) {
        return holds(run, "fldChar", "instrText");
    }

    /** Whether the run has a child with one of the names; read from the XML, since the schema's typed accessors are not all present. */
    private static boolean holds(CTR run, String... localNames) {
        for (XmlObject child : childrenOf(run)) {
            for (String localName : localNames) {
                if (isW(nameOf(child), localName)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- building and placing

    private static CTSdtRun newControl(String tag, String alias, boolean placeholder, Ids ids) {
        CTP scratch = CTP.Factory.newInstance();
        CTSdtRun sdt = scratch.addNewSdt();
        CTSdtPr properties = sdt.addNewSdtPr();
        properties.addNewAlias().setVal(alias);
        properties.addNewTag().setVal(tag);
        properties.addNewId().setVal(BigInteger.valueOf(ids.next()));
        if (placeholder) {
            properties.addNewShowingPlcHdr();
        }
        properties.addNewText();
        sdt.addNewSdtContent();
        return sdt;
    }

    /** Moves the control built in a scratch paragraph to just before {@code next}, and returns it where it now is. */
    private static CTSdtRun placeBefore(CTP paragraph, XmlObject next, CTSdtRun sdt) {
        try (XmlCursor to = next.newCursor(); XmlCursor from = sdt.newCursor()) {
            from.moveXml(to);
            to.toPrevSibling();
            return (CTSdtRun) to.getObject();
        }
    }

    private static CTSdtRun placeAtEnd(CTP paragraph, CTSdtRun sdt) {
        try (XmlCursor to = paragraph.newCursor(); XmlCursor from = sdt.newCursor()) {
            to.toEndToken();
            from.moveXml(to);
            to.toPrevSibling();
            return (CTSdtRun) to.getObject();
        }
    }

    /** A new empty run directly before {@code next}. */
    private static CTR placeRunBefore(CTP paragraph, XmlObject next) {
        CTP scratch = CTP.Factory.newInstance();
        CTR run = scratch.addNewR();
        try (XmlCursor to = next.newCursor(); XmlCursor from = run.newCursor()) {
            from.moveXml(to);
            to.toPrevSibling();
            return (CTR) to.getObject();
        }
    }

    /** Copies the font, size, colour and language, and nothing else, from a run's or paragraph mark's properties. */
    private static void copyNeighbourFormatting(XmlObject properties, CTR run) {
        if (properties == null) {
            return;
        }
        List<XmlObject> wanted = new ArrayList<>();
        for (String name : NEIGHBOUR_PROPERTIES) {
            for (XmlObject child : childrenOf(properties)) {
                if (isW(nameOf(child), name)) {
                    wanted.add(child);
                    break;
                }
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        CTRPr target = run.isSetRPr() ? run.getRPr() : run.addNewRPr();
        try (XmlCursor to = target.newCursor()) {
            to.toEndToken();
            for (XmlObject child : wanted) {
                try (XmlCursor from = child.newCursor()) {
                    from.copyXml(to);
                }
            }
        }
    }

    private static XmlObject paragraphMark(CTP paragraph) {
        return paragraph.isSetPPr() && paragraph.getPPr().isSetRPr() ? paragraph.getPPr().getRPr() : null;
    }

    private static XmlObject firstContent(CTP paragraph) {
        for (XmlObject child : childrenOf(paragraph)) {
            if (!isW(nameOf(child), "pPr")) {
                return child;
            }
        }
        return null;
    }

    private static XmlObject nextSibling(XmlObject element) {
        try (XmlCursor cursor = element.newCursor()) {
            return cursor.toNextSibling() ? cursor.getObject() : null;
        }
    }

    private static List<XmlObject> childrenBetween(CTP paragraph, XmlObject first, XmlObject last) {
        List<XmlObject> between = new ArrayList<>();
        boolean inside = false;
        for (XmlObject child : childrenOf(paragraph)) {
            if (child == first) {
                inside = true;
            }
            if (inside) {
                between.add(child);
            }
            if (child == last) {
                break;
            }
        }
        return between;
    }

    private static List<XmlObject> childrenOf(XmlObject element) {
        List<XmlObject> children = new ArrayList<>();
        try (XmlCursor cursor = element.newCursor()) {
            if (cursor.toFirstChild()) {
                do {
                    children.add(cursor.getObject());
                } while (cursor.toNextSibling());
            }
        }
        return children;
    }

    /** A run that shows nothing and holds nothing but its properties and empty text: what an {@code AT} spot starts with. */
    private static boolean isEmpty(CTR run) {
        for (XmlObject child : childrenOf(run)) {
            QName name = nameOf(child);
            if (isW(name, "rPr")) {
                continue;
            }
            if (isW(name, "t") && ((CTText) child).getStringValue().isEmpty()) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static QName nameOf(XmlObject element) {
        try (XmlCursor cursor = element.newCursor()) {
            return cursor.getName();
        }
    }

    private static boolean isW(QName name, String localName) {
        return name != null && W.equals(name.getNamespaceURI()) && localName.equals(name.getLocalPart());
    }

    private static void requireSafeTag(String tag) {
        if (!FieldIds.isSafeId(tag)) {
            throw new IllegalArgumentException("\"" + tag + "\" is not a field id a spot can be tagged with.");
        }
    }

    private static FillSpotPlacementException refused(FillSpotPlacementException.Reason reason, String message) {
        return new FillSpotPlacementException(reason, message);
    }

    private static byte[] toBytes(XWPFDocument document) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.write(out);
            return out.toByteArray();
        }
    }

    /** Fresh control ids: random, positive, 31 bits, and not used by any control already in the file. */
    private static final class Ids {

        private final Set<Long> used = new HashSet<>();

        Ids(XWPFDocument document) {
            collect(document.getDocument());
            for (XWPFHeader header : document.getHeaderList()) {
                collect(header._getHdrFtr());
            }
            for (XWPFFooter footer : document.getFooterList()) {
                collect(footer._getHdrFtr());
            }
        }

        long next() {
            while (true) {
                long id = ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE);
                if (used.add(id)) {
                    return id;
                }
            }
        }

        private void collect(XmlObject root) {
            try (XmlCursor cursor = root.newCursor()) {
                while (cursor.hasNextToken()) {
                    if (cursor.toNextToken().isStart() && isW(cursor.getName(), "id") && isSdtProperties(cursor)) {
                        String value = cursor.getAttributeText(W_VAL);
                        try {
                            used.add(Long.parseLong(value.strip()));
                        } catch (NumberFormatException | NullPointerException ignored) {
                            // An id Word would not write names no id this can clash with.
                        }
                    }
                }
            }
        }

        private static boolean isSdtProperties(XmlCursor cursor) {
            try (XmlCursor parent = cursor.newCursor()) {
                return parent.toParent() && isW(parent.getName(), "sdtPr");
            }
        }
    }
}
