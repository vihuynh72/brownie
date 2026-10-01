package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.template.FieldBindingCandidateProposer;
import io.github.vihuynh72.brownie.core.template.FieldIds;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.TableCellLabels;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the places in a Word form that look meant for filling in, by rules
 * alone and generously: the naming step decides afterwards which to keep.
 * Pure and deterministic; it reads a {@link FormOutline} and changes
 * nothing.
 *
 * <p>What counts as a place:
 * <ul>
 * <li>a content control the form already has (kept when its tag is a safe,
 * unique field id; tagged or re-tagged otherwise), and a form's own field
 * for a blank (a form text box, a merge field, a prompt);</li>
 * <li>a line of three or more underscores, a date written as a mask
 * ({@code __/__/____}), underlined spaces or tabs, a tab that draws a line
 * to its stop after a label (the spot goes just before that tab), and four
 * or more dots;</li>
 * <li>a bracketed prompt such as {@code [Company]}, {@code <Name>},
 * {@code {{date}}} or {@code \u00ABName\u00BB}, but not a reference such as
 * {@code [1]}, {@code [a]} or {@code [sic]};</li>
 * <li>a short label ending the line with a colon, and an empty table cell
 * with a label beside it or above it.</li>
 * </ul>
 *
 * <p>The rules are sure of most of these ({@link SpotCandidate.Tier#HIGH});
 * a label ending its line, a run of dots, a bracket that reads more like a
 * note than a prompt, and anything under words that keep it for the office
 * ("For office use only") are weaker guesses the naming step may leave out.
 *
 * <p>A place in a table under a header row is named by its column's header,
 * with its row added when several rows share that header ({@link
 * TableCellLabels}): the words beside it there are one of the table's
 * values, not its name. When nothing beside a place names it, the words
 * before an earlier blank on its line, then the line just above it, do;
 * "Blank" and a number is the last resort.
 *
 * <p>Only the main body and its top-level table cells are the filler's to
 * write in, so only there does a place become a candidate; blanks in
 * headers, footers, text boxes and tables inside tables are counted for a
 * notice instead. Hidden text, text inside a link or a field Word works
 * out, and tables of contents and captions are never places. Checkboxes
 * are only counted, since a field cannot hold a tick yet, and a place for a
 * signature or initials is a candidate the rules decline.
 */
public final class FillSpotCandidateFinder {

    private static final Pattern DATE_MASK = Pattern.compile(
            "[_\\uFF3F]{1,4} ?[/.\\-] ?[_\\uFF3F]{1,4} ?[/.\\-] ?[_\\uFF3F]{2,4}");
    private static final Pattern UNDERSCORES = Pattern.compile("[_\\uFF3F]{3,}");
    private static final Pattern DOT_LEADER = Pattern.compile("\\.{4,}|\\u2026{2,}");
    /** Longer delimiters first, so {@code {{name}}} is one prompt and not a brace inside braces. */
    private static final List<Pattern> BRACKETS = List.of(
            Pattern.compile("\\{\\{([^{}\\n]{1,60})}}"),
            Pattern.compile("\\$\\{([^{}\\n]{1,60})}"),
            Pattern.compile("<<([^<>\\n]{1,60})>>"),
            Pattern.compile("\\[([^\\[\\]\\n]{1,60})]"),
            Pattern.compile("\\{([^{}\\n]{1,60})}"),
            Pattern.compile("<([^<>\\n]{1,60})>"),
            Pattern.compile("\\u00AB([^\\u00AB\\u00BB\\n]{1,60})\\u00BB"),
            Pattern.compile("\\u2039([^\\u2039\\u203A\\n]{1,60})\\u203A"),
            Pattern.compile("\\u3010([^\\u3010\\u3011\\n]{1,60})\\u3011"));
    private static final Pattern NOT_A_PROMPT = Pattern.compile(
            "(?i)sic!?|et al\\.?|ibid\\.?|emphasis (added|mine)|[ivxlcdm]{1,6}\\.?|\\p{L}\\p{N}+|\\p{N}+\\p{L}");
    private static final Set<String> PROMPT_WORDS = Set.of(
            "name", "date", "address", "insert", "enter", "type", "your", "company", "title", "number", "phone", "email",
            "signature", "amount", "city", "street", "role", "position", "here");
    private static final Pattern TABLE_OF_CONTENTS_OR_CAPTION = Pattern.compile("(?i)(toc\\b.*|toc\\d*|table of contents.*|caption.*)");
    private static final Set<String> GENERIC_PROMPTS = Set.of(
            "click or tap here to enter text.", "click here to enter text.", "click or tap to enter a date.",
            "click here to enter a date.", "choose an item.");
    private static final Set<Integer> CHECKBOX_GLYPHS = Set.of(0x2610, 0x2611, 0x2612, 0x25A1, 0x25A0);
    private static final Set<String> LEADERS = Set.of("dot", "underscore", "hyphen", "heavy", "middleDot");
    /** Words that keep a part of a form for the people who receive it ("For office use only", "Internal use"). */
    private static final Pattern OFFICE_USE = Pattern.compile("(?i)\\b(office|official|internal|admin|administrative|staff)\\s+use\\b");
    /** Column headers that only say "the answer goes here": the label beside the cell names it better. */
    private static final Set<String> ANSWER_HEADERS = Set.of(
            "answer", "answers", "your answer", "response", "value", "details", "entry", "input", "information", "reply");
    /** A header that tells the person what to do ("Please complete", "Write here") names no value either. */
    private static final Pattern INSTRUCTION_HEADER = Pattern.compile(
            "(?i)(please|kindly|enter|write|insert|provide|fill|complete)\\b.*");
    /** The longest value of a table kept to compare a name against: a longer one cannot be a label anyway. */
    private static final int MAX_TABLE_VALUE_LENGTH = 60;
    /** The longest tag kept as a field id: a kept spot's review is stored under its id, which is at most this long. */
    private static final int MAX_KEPT_TAG_LENGTH = 64;

    private final FormOutline outline;
    private final List<FormOutline.Paragraph> paragraphs;
    private final Map<String, Integer> tagCounts = new HashMap<>();
    private final Map<String, List<Integer>> cellParagraphs = new LinkedHashMap<>();
    private final Map<Integer, List<String>> tableValues = new HashMap<>();
    private final Set<String> repeatableRows = new HashSet<>();
    private final List<Found> found = new ArrayList<>();
    private int checkboxes;
    private int outsideBody;
    private int unnamed;

    private FillSpotCandidateFinder(FormOutline outline) {
        this.outline = outline;
        this.paragraphs = outline.paragraphs();
    }

    public static FoundSpots find(FormOutline outline) {
        return new FillSpotCandidateFinder(outline).run();
    }

    /** A place before it has an id: its paragraph, its character range in the anchor text, and what the rules saw there. */
    private record Found(
            int paragraph, int start, int end, SpotCandidate.Kind kind, boolean replace, boolean dateMask, String hint,
            FormOutline.Control control, SpotCandidate.Tier tier, String label) {
    }

    private FoundSpots run() {
        for (int i = 0; i < paragraphs.size(); i++) {
            FormOutline.Paragraph paragraph = paragraphs.get(i);
            if (paragraph.region() != FormOutline.Region.NESTED_TABLE && paragraph.region() != FormOutline.Region.TEXT_BOX) {
                for (FormOutline.Atom atom : paragraph.atoms()) {
                    if (atom instanceof FormOutline.Control control && control.tag() != null && !control.tag().isBlank()) {
                        tagCounts.merge(control.tag(), 1, Integer::sum);
                    }
                }
            }
            if (paragraph.cell() != null) {
                cellParagraphs.computeIfAbsent(cellKey(paragraph.cell()), key -> new ArrayList<>()).add(i);
            }
        }
        for (int i = 0; i < paragraphs.size(); i++) {
            FormOutline.Paragraph paragraph = paragraphs.get(i);
            if (isTableOfContentsOrCaption(paragraph) || continuesMerge(paragraph)) {
                continue;
            }
            if (fillable(paragraph)) {
                findIn(i, paragraph);
            } else {
                outsideBody += countBlanks(paragraph);
            }
        }
        findEmptyCells();
        found.removeIf(place -> place.kind() == SpotCandidate.Kind.LABEL_AT_END && rightCellBeginsWithAPlace(paragraphs.get(place.paragraph())));
        found.sort(Comparator.comparingInt(Found::paragraph).thenComparingInt(Found::start).thenComparingInt(Found::end));
        Map<String, List<FoundSpots.CollapsedRow>> collapsed = new LinkedHashMap<>();
        List<String> offered = offeredRows(collapsed);
        repeatableRows.addAll(offered);

        List<SpotCandidate> candidates = new ArrayList<>();
        Map<Found, String> ids = new HashMap<>();
        for (Found place : found) {
            SpotCandidate candidate = candidateFor(place, "c" + (candidates.size() + 1));
            ids.put(place, candidate.id());
            candidates.add(candidate);
        }

        List<PreparationNotice> notices = new ArrayList<>();
        if (outsideBody > 0) {
            notices.add(new PreparationNotice(PreparationNotice.BLANKS_OUTSIDE_BODY, outsideBody, null));
        }
        if (checkboxes > 0) {
            notices.add(new PreparationNotice(PreparationNotice.CHECKBOXES_LEFT, checkboxes, null));
        }
        long signatures = candidates.stream().filter(SpotCandidate::signatureLike).count();
        if (signatures > 0) {
            notices.add(new PreparationNotice(PreparationNotice.SIGNATURE_LINES_LEFT, (int) signatures, null));
        }
        return new FoundSpots(candidates, outlineLines(ids), offered, collapsed, notices, standingTags(candidates));
    }

    /** Every tag a control of the form still carries once the controls Brownie re-tags have their new ids. */
    private Set<String> standingTags(List<SpotCandidate> candidates) {
        Set<String> retagged = new HashSet<>();
        for (SpotCandidate candidate : candidates) {
            if (candidate.kind() == SpotCandidate.Kind.EXISTING_TAGGED_CONTROL && !candidate.keptAsTagged()) {
                retagged.add(candidate.anchor().part() + "#" + candidate.anchor().controlNodeId());
            }
        }
        Set<String> tags = new HashSet<>();
        for (FormOutline.Paragraph paragraph : paragraphs) {
            for (FormOutline.Atom atom : paragraph.atoms()) {
                if (atom instanceof FormOutline.Control control && control.tag() != null && !control.tag().isBlank()
                        && !retagged.contains(paragraph.part() + "#" + control.nodeId())) {
                    tags.add(control.tag());
                }
            }
        }
        return tags;
    }

    private static boolean fillable(FormOutline.Paragraph paragraph) {
        return paragraph.part() == DocumentPartKind.MAIN_DOCUMENT
                && paragraph.nodeId() != null
                && (paragraph.region() == FormOutline.Region.BODY || paragraph.region() == FormOutline.Region.TOP_TABLE_CELL);
    }

    /** A paragraph of a cell that continues a vertical merge: nothing there prints, so it is never a place. */
    private static boolean continuesMerge(FormOutline.Paragraph paragraph) {
        return paragraph.cell() != null && paragraph.cell().continuesMerge();
    }

    private static boolean isTableOfContentsOrCaption(FormOutline.Paragraph paragraph) {
        return paragraph.styleName() != null && TABLE_OF_CONTENTS_OR_CAPTION.matcher(paragraph.styleName().strip()).matches();
    }

    // ---------------------------------------------------------------- places in one paragraph

    private void findIn(int index, FormOutline.Paragraph paragraph) {
        String text = paragraph.anchorText();
        Mask mask = Mask.of(paragraph);
        boolean[] taken = new boolean[text.length()];
        for (int point : pointsOf(paragraph)) {
            // A control or an empty form field sits between two characters; no blank read from the text may run across it.
            mask.cutAt(charIndex(text, point));
        }

        for (FormOutline.Atom atom : paragraph.atoms()) {
            switch (atom) {
                case FormOutline.Control control -> controlPlace(index, text, control);
                case FormOutline.FormField field -> {
                    int start = charIndex(text, field.start());
                    int end = charIndex(text, field.end());
                    mark(taken, start, end);
                    found.add(new Found(index, start, end, SpotCandidate.Kind.FORM_FIELD, start < end, false,
                            fieldWords(field), null, SpotCandidate.Tier.HIGH, null));
                }
                case FormOutline.CheckboxField ignored -> checkboxes++;
                default -> {
                    // Runs, tabs and pictures are read below, as text.
                }
            }
        }

        for (Matcher matcher = DATE_MASK.matcher(text); matcher.find(); ) {
            addText(index, mask, taken, matcher.start(), matcher.end(), SpotCandidate.Kind.UNDERSCORES, true, null, SpotCandidate.Tier.HIGH);
        }
        for (Matcher matcher = UNDERSCORES.matcher(text); matcher.find(); ) {
            addText(index, mask, taken, matcher.start(), matcher.end(), SpotCandidate.Kind.UNDERSCORES, false, null, SpotCandidate.Tier.HIGH);
        }
        for (Pattern bracket : BRACKETS) {
            for (Matcher matcher = bracket.matcher(text); matcher.find(); ) {
                String inner = matcher.group(1);
                if (isPrompt(inner)) {
                    addText(index, mask, taken, matcher.start(), matcher.end(), SpotCandidate.Kind.BRACKET,
                            SpotLabels.namesADate(inner), inner, promptTier(inner));
                }
            }
        }
        for (Matcher matcher = DOT_LEADER.matcher(text); matcher.find(); ) {
            addText(index, mask, taken, matcher.start(), matcher.end(), SpotCandidate.Kind.DOT_LEADER, false, null, SpotCandidate.Tier.MEDIUM);
        }
        underlinedBlanks(index, text, mask, taken);
        tabLeaders(index, paragraph, text, mask, taken);
        labelAtEnd(index, paragraph, text, mask, taken);

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (CHECKBOX_GLYPHS.contains(codePoint) && mask.eligible(i)) {
                checkboxes++;
            }
            i += Character.charCount(codePoint);
        }
    }

    private void controlPlace(int index, String text, FormOutline.Control control) {
        if (control.kind() == FormOutline.ControlKind.CHECKBOX) {
            checkboxes++;
            return;
        }
        if (control.kind() == FormOutline.ControlKind.PICTURE) {
            return;
        }
        int at = charIndex(text, control.at());
        String tag = control.tag() == null || control.tag().isBlank() ? null : control.tag();
        SpotCandidate.Kind kind = tag == null ? SpotCandidate.Kind.EXISTING_UNTAGGED_CONTROL : SpotCandidate.Kind.EXISTING_TAGGED_CONTROL;
        String hint = control.alias() != null && !control.alias().isBlank() ? control.alias() : null;
        found.add(new Found(index, at, at, kind, false, false, hint, control, SpotCandidate.Tier.HIGH, null));
    }

    /** Where the paragraph's controls and empty form fields sit, in code points. */
    private static List<Integer> pointsOf(FormOutline.Paragraph paragraph) {
        List<Integer> points = new ArrayList<>();
        for (FormOutline.Atom atom : paragraph.atoms()) {
            if (atom instanceof FormOutline.Control control) {
                points.add(control.at());
            } else if (atom instanceof FormOutline.FormField field && field.start() == field.end()) {
                points.add(field.start());
            }
        }
        return points;
    }

    private void addText(int index, Mask mask, boolean[] taken, int start, int end, SpotCandidate.Kind kind, boolean dateMask,
                         String hint, SpotCandidate.Tier tier) {
        if (!mask.eligible(start, end) || mask.cutWithin(start, end) || anyTaken(taken, start, end)) {
            return;
        }
        mark(taken, start, end);
        found.add(new Found(index, start, end, kind, true, dateMask, hint, null, tier, null));
    }

    private void underlinedBlanks(int index, String text, Mask mask, boolean[] taken) {
        int i = 0;
        while (i < text.length()) {
            if (!(mask.eligible(i) && mask.underlined(i) && isBlankCharacter(text.charAt(i)) && !taken[i])) {
                i++;
                continue;
            }
            int end = i;
            boolean tab = false;
            while (end < text.length() && mask.eligible(end) && mask.underlined(end) && isBlankCharacter(text.charAt(end)) && !taken[end]
                    && (end == i || !mask.cutBefore(end))) {
                tab |= text.charAt(end) == '\t';
                end++;
            }
            if (end - i >= 3 || tab) {
                mark(taken, i, end);
                found.add(new Found(index, i, end, SpotCandidate.Kind.UNDERLINED_BLANK, true, false, null, null,
                        SpotCandidate.Tier.HIGH, null));
            }
            i = end;
        }
    }

    private void tabLeaders(int index, FormOutline.Paragraph paragraph, String text, Mask mask, boolean[] taken) {
        for (FormOutline.Atom atom : paragraph.atoms()) {
            if (!(atom instanceof FormOutline.Tab tab) || tab.leader() == null || !LEADERS.contains(tab.leader())) {
                continue;
            }
            int at = charIndex(text, tab.at());
            if (at >= text.length() || text.charAt(at) != '\t' || !mask.eligible(at) || taken[at]) {
                continue;
            }
            int lineStart = Math.max(text.lastIndexOf('\t', at - 1), text.lastIndexOf('\n', at - 1)) + 1;
            int next = nextBreak(text, at + 1);
            if (text.substring(lineStart, at).isBlank() || !text.substring(at + 1, next).isBlank()) {
                continue;
            }
            taken[at] = true;
            // The spot goes before the tab, so the value follows its label and the tab's line fills what is left.
            found.add(new Found(index, at, at, SpotCandidate.Kind.TAB_LEADER, false, false, null, null,
                    SpotCandidate.Tier.HIGH, SpotLabels.before(text, lineStart, at)));
        }
    }

    /** "Name:" ending the line, with nothing after the colon but spaces or tabs: the answer goes after it. */
    private void labelAtEnd(int index, FormOutline.Paragraph paragraph, String text, Mask mask, boolean[] taken) {
        if (paragraph.heading()) {
            return;
        }
        int last = text.length() - 1;
        while (last >= 0 && isBlankCharacter(text.charAt(last))) {
            last--;
        }
        if (last < 0 || (text.charAt(last) != ':' && text.charAt(last) != '\uFF1A') || !mask.eligible(last) || taken[last]) {
            return;
        }
        int from = 0;
        for (Found place : found) {
            if (place.paragraph() != index) {
                continue;
            }
            if (place.start() > last) {
                // Something after the colon is already the place for its answer.
                return;
            }
            if (place.end() <= last) {
                from = Math.max(from, place.end());
            }
        }
        String label = SpotLabels.before(text, from, last);
        if (label == null || rightCellIsEmpty(paragraph)) {
            return;
        }
        int end = text.length();
        found.add(new Found(index, end, end, SpotCandidate.Kind.LABEL_AT_END, false, false, null, null,
                SpotCandidate.Tier.MEDIUM, label));
    }

    private boolean rightCellIsEmpty(FormOutline.Paragraph paragraph) {
        FormOutline.Cell cell = paragraph.cell();
        if (cell == null) {
            return false;
        }
        List<Integer> right = cellParagraphs.get(cellKey(cell.table(), cell.row(), cell.column() + 1));
        return right != null && isEmptyCell(right);
    }

    /**
     * Whether the cell to the right of a label's cell begins with the
     * answer's place: a blank found there, or a control or form field of the
     * form's own, with nothing but spaces before it in that cell. The label
     * is then only a label, not a second place. A place further along has a
     * label of its own before it ("Phone: ____"), and a label ending the cell
     * ("Date:") is a place of its own; neither answers the label on the left,
     * which keeps its place.
     */
    private boolean rightCellBeginsWithAPlace(FormOutline.Paragraph paragraph) {
        FormOutline.Cell cell = paragraph.cell();
        List<Integer> right = cell == null ? null : cellParagraphs.get(cellKey(cell.table(), cell.row(), cell.column() + 1));
        if (right == null) {
            return false;
        }
        for (int index : right) {
            String text = paragraphs.get(index).anchorText();
            int firstWord = 0;
            while (firstWord < text.length() && isBlankCharacter(text.charAt(firstWord))) {
                firstWord++;
            }
            for (Found place : found) {
                if (place.paragraph() == index && place.kind() != SpotCandidate.Kind.LABEL_AT_END && place.start() <= firstWord) {
                    return true;
                }
            }
            for (FormOutline.Atom atom : paragraphs.get(index).atoms()) {
                int at = switch (atom) {
                    case FormOutline.Control control -> charIndex(text, control.at());
                    case FormOutline.FormField field -> charIndex(text, field.start());
                    case FormOutline.CheckboxField checkbox -> charIndex(text, checkbox.at());
                    default -> Integer.MAX_VALUE;
                };
                if (at <= firstWord) {
                    return true;
                }
            }
            if (firstWord < text.length()) {
                // The cell begins with words: a label of its own, or text that is no place.
                return false;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- empty table cells

    private void findEmptyCells() {
        for (Map.Entry<String, List<Integer>> entry : cellParagraphs.entrySet()) {
            List<Integer> cellIndexes = entry.getValue();
            FormOutline.Paragraph first = paragraphs.get(cellIndexes.getFirst());
            if (!fillable(first) || isTableOfContentsOrCaption(first) || continuesMerge(first) || !isEmptyCell(cellIndexes)) {
                continue;
            }
            FormOutline.Cell cell = first.cell();
            String left = cell.column() > 0 ? cellText(cell.table(), cell.row(), cell.column() - 1) : null;
            String header = cell.row() > 0 ? cellText(cell.table(), 0, cell.column()) : null;
            if (SpotLabels.clean(left) == null && SpotLabels.clean(header) == null) {
                continue;
            }
            // Named once every place is found, since a cell's name depends on the places in its column.
            int end = first.anchorText().length();
            found.add(new Found(cellIndexes.getFirst(), end, end, SpotCandidate.Kind.EMPTY_CELL, false, false,
                    SpotLabels.clean(left) != null ? left : header, null, SpotCandidate.Tier.HIGH, null));
        }
    }

    private boolean isEmptyCell(List<Integer> cellIndexes) {
        for (int index : cellIndexes) {
            FormOutline.Paragraph paragraph = paragraphs.get(index);
            if (!isBlank(paragraph.anchorText())) {
                return false;
            }
            for (FormOutline.Atom atom : paragraph.atoms()) {
                if (!(atom instanceof FormOutline.Run) && !(atom instanceof FormOutline.Tab)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A cell's shown text; for a cell that continues a merge, the text of the merged cell it belongs to. */
    private String cellText(int table, int row, int column) {
        List<Integer> indexes = cellParagraphs.get(cellKey(table, row, column));
        if (indexes == null) {
            return null;
        }
        if (continuesMerge(paragraphs.get(indexes.getFirst()))) {
            return row > 0 ? cellText(table, row - 1, column) : null;
        }
        StringBuilder text = new StringBuilder();
        for (int index : indexes) {
            String paragraphText = visibleText(paragraphs.get(index)).strip();
            if (!paragraphText.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(paragraphText);
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    // ---------------------------------------------------------------- naming each place

    private SpotCandidate candidateFor(Found place, String id) {
        FormOutline.Paragraph paragraph = paragraphs.get(place.paragraph());
        String text = paragraph.anchorText();
        String raw = rawWords(place, paragraph);
        String label;
        String context = place.hint();
        FieldType type;
        String keptTag = null;
        if (place.kind() == SpotCandidate.Kind.EXISTING_TAGGED_CONTROL && isKeptTag(place.control().tag())) {
            keptTag = place.control().tag();
            label = FieldIds.labelFor(keptTag);
        } else {
            Named named = labelFor(place, paragraph);
            label = named == null ? null : named.label();
            context = named == null || named.context() == null ? context : named.context();
        }
        boolean signature = keptTag == null && (SpotLabels.signatureLike(raw) || SpotLabels.signatureLike(label));
        if (keptTag == null && label == null) {
            if (place.kind() == SpotCandidate.Kind.UNDERSCORES && closesALetterAbove(place.paragraph())) {
                label = "Signature";
                signature = true;
            } else {
                label = nearestText(place, paragraph);
                signature = signature || SpotLabels.signatureLike(label);
                if (label == null) {
                    unnamed++;
                    label = "Blank " + unnamed;
                }
            }
        }
        boolean date = place.dateMask() || (place.control() != null && place.control().kind() == FormOutline.ControlKind.DATE);
        if (keptTag != null) {
            // A kept tag is typed by its own words, as a template made from the same file would type it.
            type = date ? FieldType.DATE : FieldBindingCandidateProposer.inferType(keptTag);
        } else {
            type = SpotLabels.typeFor(label, date || (place.kind() == SpotCandidate.Kind.BRACKET && SpotLabels.namesADate(place.hint())));
        }

        DocxAnchor anchor = anchorFor(place, paragraph);
        String blank = place.replace() ? blankTextOf(text.substring(place.start(), place.end())) : null;
        FormOutline.Cell cell = paragraph.cell();
        String rowKey = cell == null ? null : rowKey(cell.table(), cell.row());
        SpotCandidate.Tier tier = forOfficeUse(place.paragraph()) ? SpotCandidate.Tier.MEDIUM : place.tier();
        return new SpotCandidate(id, place.kind(), anchor, keptTag, blank, label, type, tier, rowKey, signature,
                context, paragraph.key(), cell == null ? List.of() : valuesOf(cell.table()));
    }

    private boolean isKeptTag(String tag) {
        return tag != null && FieldIds.isSafeId(tag) && tag.length() <= MAX_KEPT_TAG_LENGTH && tagCounts.getOrDefault(tag, 0) == 1;
    }

    /** The words the form wrote for this place, before any cleaning: what a signature line is recognised by. */
    private String rawWords(Found place, FormOutline.Paragraph paragraph) {
        if (place.label() != null && place.hint() == null) {
            return place.label();
        }
        if (place.hint() != null) {
            return place.hint();
        }
        String text = paragraph.anchorText();
        int from = previousEnd(place);
        String before = text.substring(from, place.start());
        return before.isBlank() ? null : before;
    }

    /** A place's name, and what the naming step is told about it when that is more than the words around it (or null). */
    private record Named(String label, String context) {
    }

    private Named labelFor(Found place, FormOutline.Paragraph paragraph) {
        if (place.label() != null) {
            return new Named(place.label(), null);
        }
        String text = paragraph.anchorText();
        String fromHint = switch (place.kind()) {
            case BRACKET -> SpotLabels.cleanKeepingInstructions(place.hint());
            case FORM_FIELD -> place.hint() == null ? null : SpotLabels.fromName(place.hint());
            case EXISTING_TAGGED_CONTROL -> place.hint() != null ? SpotLabels.cleanKeepingInstructions(place.hint())
                    : SpotLabels.fromName(place.control().tag());
            case EXISTING_UNTAGGED_CONTROL -> place.hint() != null ? SpotLabels.cleanKeepingInstructions(place.hint())
                    : placeholderLabel(place.control());
            default -> null;
        };
        if (fromHint != null) {
            return new Named(fromHint, null);
        }
        String before = SpotLabels.before(text, previousEnd(place), place.start());
        if (before != null) {
            return new Named(before, null);
        }
        FormOutline.Cell cell = paragraph.cell();
        if (cell != null && text.substring(0, place.start()).isBlank()) {
            Named inCell = cellName(cell);
            if (inCell != null) {
                return inCell;
            }
        }
        String after = SpotLabels.after(text, place.end(), nextStart(place));
        return after == null ? null : new Named(after, null);
    }

    // ---------------------------------------------------------------- naming a place in a table

    /**
     * The name of a place that begins its table cell. Under a header row its
     * column's header names it, with the row added when several rows of that
     * column hold places ({@link TableCellLabels}) and the row is not the one
     * that can repeat, and the naming step is told the column and the row. The label beside the cell names it
     * instead when it ends with a colon ("Phone:"), or when the header only
     * says that an answer goes there ("Answer", "Please complete"). With no
     * header row, the label beside the cell names it, then the words at the
     * top of its column. Null when nothing does.
     */
    private Named cellName(FormOutline.Cell cell) {
        String left = cell.column() > 0 ? cellText(cell.table(), cell.row(), cell.column() - 1) : null;
        String leftLabel = SpotLabels.clean(left);
        String headerText = cell.row() > 0 && hasHeaderRow(cell.table()) ? cellText(cell.table(), 0, cell.column()) : null;
        String header = SpotLabels.clean(headerText);
        boolean answerHeader = header != null && (ANSWER_HEADERS.contains(header.toLowerCase(Locale.ROOT))
                || INSTRUCTION_HEADER.matcher(headerText.strip()).matches());
        boolean labelBeside = leftLabel != null && (endsWithColon(left) || answerHeader);
        if (header != null && !labelBeside) {
            String first = cell.column() > 0 ? SpotLabels.clean(cellText(cell.table(), cell.row(), 0)) : null;
            // A row that can repeat is one item of many: its cells are the column's, with no row to tell apart.
            boolean severalRows = !repeatableRows.contains(rowKey(cell.table(), cell.row()))
                    && rowsWithPlaces(cell.table(), cell.column()) > 1;
            return new Named(TableCellLabels.label(header, first, cell.row(), severalRows),
                    TableCellLabels.context(header, first, cell.row()));
        }
        if (leftLabel != null) {
            return new Named(leftLabel, null);
        }
        String top = cell.row() > 0 ? SpotLabels.clean(cellText(cell.table(), 0, cell.column())) : null;
        return top == null ? null : new Named(top, null);
    }

    /**
     * Whether a table's first row is its header: it has more rows, and every
     * cell of the first row holds words and no place to fill. The first cell
     * may be empty: the corner of a table with its rows named down the side
     * and its columns across the top. A first row with a blank in it is a
     * row of the form like the others ("Name: | ").
     */
    private boolean hasHeaderRow(int table) {
        boolean more = cellParagraphs.containsKey(cellKey(table, 1, 0));
        if (!more) {
            return false;
        }
        boolean words = false;
        for (int column = 0; cellParagraphs.containsKey(cellKey(table, 0, column)); column++) {
            boolean empty = cellText(table, 0, column) == null;
            if (empty && column > 0) {
                return false;
            }
            words = words || !empty;
        }
        if (!words) {
            return false;
        }
        for (Found place : found) {
            FormOutline.Cell cell = paragraphs.get(place.paragraph()).cell();
            if (cell != null && cell.table() == table && cell.row() == 0) {
                return false;
            }
        }
        return true;
    }

    /** How many rows below the first hold a place in this column of the table. */
    private long rowsWithPlaces(int table, int column) {
        return found.stream()
                .map(place -> paragraphs.get(place.paragraph()).cell())
                .filter(cell -> cell != null && cell.table() == table && cell.column() == column && cell.row() > 0)
                .map(FormOutline.Cell::row)
                .distinct()
                .count();
    }

    /**
     * The text printed in a table's cells below its header row: its values,
     * which never name one of its places on their own. A label ending with a
     * colon is not a value, and a table with no header row has none: the
     * words beside a cell there are its label.
     */
    private List<String> valuesOf(int table) {
        return tableValues.computeIfAbsent(table, key -> {
            if (!hasHeaderRow(table)) {
                return List.of();
            }
            Set<String> values = new LinkedHashSet<>();
            for (int row = 1; cellParagraphs.containsKey(cellKey(table, row, 0)); row++) {
                for (int column = 0; cellParagraphs.containsKey(cellKey(table, row, column)); column++) {
                    String text = cellText(table, row, column);
                    if (text != null && !endsWithColon(text) && text.codePointCount(0, text.length()) <= MAX_TABLE_VALUE_LENGTH) {
                        values.add(text);
                    }
                }
            }
            return List.copyOf(values);
        });
    }

    private static boolean endsWithColon(String text) {
        String stripped = text.strip();
        return stripped.endsWith(":") || stripped.endsWith("\uFF1A");
    }

    /**
     * For a place nothing beside it names: the words before an earlier blank
     * on its line ("Name ____ ____" names both "Name"), then the line just
     * above it in its cell or in the body, when that line holds no blank and
     * is short enough to be a label. Null when neither does.
     */
    private String nearestText(Found place, FormOutline.Paragraph paragraph) {
        String text = paragraph.anchorText();
        List<Found> earlier = new ArrayList<>();
        for (Found other : found) {
            if (other != place && other.paragraph() == place.paragraph() && other.end() <= place.start()) {
                earlier.add(other);
            }
        }
        earlier.sort(Comparator.comparingInt(Found::start).reversed());
        for (Found other : earlier) {
            String words = SpotLabels.before(text, previousEnd(other), other.start());
            if (words != null) {
                return words;
            }
        }
        for (int i = place.paragraph() - 1; i >= 0; i--) {
            FormOutline.Paragraph above = paragraphs.get(i);
            boolean sameCell = paragraph.cell() == null
                    ? above.cell() == null && above.region() == paragraph.region()
                    : above.cell() != null && cellKey(above.cell()).equals(cellKey(paragraph.cell()));
            if (!sameCell || holdsAPlace(i)) {
                return null;
            }
            String shown = visibleText(above).strip();
            if (!shown.isEmpty()) {
                return SpotLabels.clean(shown);
            }
        }
        return null;
    }

    private boolean holdsAPlace(int index) {
        return found.stream().anyMatch(place -> place.paragraph() == index);
    }

    /**
     * Whether words keep a place for the office ("For office use only"): in
     * its own line, in its table cell or any row of its table from the first
     * down to its own (a row that heads the office's part of the table), or
     * in the line that heads the run of lines to fill it belongs to. Such a
     * place is only a guess: the naming step may leave it out.
     */
    private boolean forOfficeUse(int index) {
        FormOutline.Paragraph paragraph = paragraphs.get(index);
        if (OFFICE_USE.matcher(visibleText(paragraph)).find()) {
            return true;
        }
        FormOutline.Cell cell = paragraph.cell();
        if (cell != null) {
            for (int other : cellParagraphs.getOrDefault(cellKey(cell), List.of())) {
                if (OFFICE_USE.matcher(visibleText(paragraphs.get(other))).find()) {
                    return true;
                }
            }
            for (int row = 0; row <= cell.row(); row++) {
                for (int column = 0; cellParagraphs.containsKey(cellKey(cell.table(), row, column)); column++) {
                    String text = cellText(cell.table(), row, column);
                    if (text != null && OFFICE_USE.matcher(text).find()) {
                        return true;
                    }
                }
            }
            return false;
        }
        for (int i = index - 1; i >= 0; i--) {
            FormOutline.Paragraph above = paragraphs.get(i);
            if (above.cell() != null || above.region() != paragraph.region()) {
                return false;
            }
            String text = visibleText(above);
            if (text.isBlank()) {
                continue;
            }
            if (OFFICE_USE.matcher(text).find()) {
                return true;
            }
            if (!holdsAPlace(i)) {
                // Words with nothing to fill end the run of lines this place belongs to.
                return false;
            }
        }
        return false;
    }

    private static String placeholderLabel(FormOutline.Control control) {
        if (control.text() == null || GENERIC_PROMPTS.contains(control.text().strip().toLowerCase(Locale.ROOT))) {
            return null;
        }
        return SpotLabels.cleanKeepingInstructions(control.text());
    }

    private int previousEnd(Found place) {
        int end = 0;
        for (Found other : found) {
            if (other != place && other.paragraph() == place.paragraph() && other.end() <= place.start()) {
                end = Math.max(end, other.end());
            }
        }
        return end;
    }

    private int nextStart(Found place) {
        int start = paragraphs.get(place.paragraph()).anchorText().length();
        for (Found other : found) {
            if (other != place && other.paragraph() == place.paragraph() && other.start() >= place.end()) {
                start = Math.min(start, other.start());
            }
        }
        return start;
    }

    /** Whether the nearest line above with any text closes a letter ("Yours sincerely,"). */
    private boolean closesALetterAbove(int index) {
        for (int i = index - 1; i >= 0; i--) {
            FormOutline.Paragraph above = paragraphs.get(i);
            if (above.region() != FormOutline.Region.BODY) {
                return false;
            }
            if (!above.anchorText().isBlank()) {
                return SpotLabels.closesALetter(above.anchorText());
            }
        }
        return false;
    }

    private DocxAnchor anchorFor(Found place, FormOutline.Paragraph paragraph) {
        String text = paragraph.anchorText();
        String hash = DocxAnchor.hashOf(text);
        int start = text.codePointCount(0, place.start());
        int end = text.codePointCount(0, place.end());
        if (place.control() != null) {
            return new DocxAnchor(paragraph.part(), paragraph.nodeId(), AnchorPlacement.EXISTING_CONTROL, start, start, hash,
                    outline.parserVersion(), place.control().nodeId());
        }
        AnchorPlacement placement = place.replace() && start < end ? AnchorPlacement.REPLACE : AnchorPlacement.AT;
        return new DocxAnchor(paragraph.part(), paragraph.nodeId(), placement, start, placement == AnchorPlacement.AT ? start : end,
                hash, outline.parserVersion(), null);
    }

    /** The blank as it can be printed back when the spot is left empty, or null when it cannot (a tab, a line break). */
    private static String blankTextOf(String blank) {
        return FieldIds.isValidBlankText(blank) ? blank : null;
    }

    private static String fieldWords(FormOutline.FormField field) {
        return field.words() == null || field.words().isBlank() ? null : field.words().strip();
    }

    // ---------------------------------------------------------------- the one row that can repeat

    /**
     * The filler repeats only the last row of the body's first table, and
     * only when no row above it holds a place. When the table ends in two or
     * more identical empty rows under a header, the first of them is offered
     * instead, standing for all of them.
     */
    private List<String> offeredRows(Map<String, List<FoundSpots.CollapsedRow>> collapsed) {
        Map<Integer, String> rowNodeIds = new LinkedHashMap<>();
        Map<Integer, List<String>> rowCells = new LinkedHashMap<>();
        int rowCount = 0;
        for (Map.Entry<String, List<Integer>> entry : cellParagraphs.entrySet()) {
            FormOutline.Cell cell = paragraphs.get(entry.getValue().getFirst()).cell();
            if (cell.table() != 1 || paragraphs.get(entry.getValue().getFirst()).part() != DocumentPartKind.MAIN_DOCUMENT) {
                continue;
            }
            rowCount = cell.rowCount();
            rowNodeIds.put(cell.row(), cell.rowNodeId());
            rowCells.computeIfAbsent(cell.row(), row -> new ArrayList<>()).add(entry.getKey());
        }
        if (rowCount < 2) {
            return List.of();
        }
        Set<Integer> rowsWithPlaces = new HashSet<>();
        for (Found place : found) {
            FormOutline.Cell cell = paragraphs.get(place.paragraph()).cell();
            if (cell != null && cell.table() == 1) {
                rowsWithPlaces.add(cell.row());
            }
        }
        int last = rowCount - 1;
        int firstEmpty = last;
        while (firstEmpty - 1 >= 1 && isEmptyRow(rowCells.get(firstEmpty - 1)) && isEmptyRow(rowCells.get(last))
                && sameCellCount(rowCells.get(firstEmpty - 1), rowCells.get(last))) {
            firstEmpty--;
        }
        if (firstEmpty < last && isEmptyRow(rowCells.get(last)) && rowsWithPlaces.contains(firstEmpty)
                && noPlaceAbove(rowsWithPlaces, firstEmpty)) {
            String offered = rowKey(1, firstEmpty);
            List<FoundSpots.CollapsedRow> rows = new ArrayList<>();
            for (int row = firstEmpty + 1; row <= last; row++) {
                rows.add(new FoundSpots.CollapsedRow(rowKey(1, row), rowNodeIds.get(row)));
            }
            collapsed.put(offered, rows);
            return List.of(offered);
        }
        if (rowsWithPlaces.contains(last) && noPlaceAbove(rowsWithPlaces, last)) {
            return List.of(rowKey(1, last));
        }
        return List.of();
    }

    private static boolean noPlaceAbove(Set<Integer> rowsWithPlaces, int row) {
        return rowsWithPlaces.stream().noneMatch(other -> other < row);
    }

    private boolean isEmptyRow(List<String> cells) {
        if (cells == null || cells.isEmpty()) {
            return false;
        }
        for (String key : cells) {
            if (!isEmptyCell(cellParagraphs.get(key))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameCellCount(List<String> a, List<String> b) {
        return a != null && b != null && a.size() == b.size();
    }

    // ---------------------------------------------------------------- the outline the naming step reads

    private List<OutlineLine> outlineLines(Map<Found, String> ids) {
        Map<Integer, List<Found>> byParagraph = new HashMap<>();
        for (Found place : found) {
            byParagraph.computeIfAbsent(place.paragraph(), key -> new ArrayList<>()).add(place);
        }
        List<OutlineLine> lines = new ArrayList<>();
        int headings = 0;
        int plain = 0;
        String currentRow = null;
        StringBuilder row = null;
        for (int i = 0; i < paragraphs.size(); i++) {
            FormOutline.Paragraph paragraph = paragraphs.get(i);
            if (!fillable(paragraph)) {
                continue;
            }
            String line = rendered(paragraph, byParagraph.getOrDefault(i, List.of()), ids);
            if (paragraph.cell() != null) {
                FormOutline.Cell cell = paragraph.cell();
                String key = rowKey(cell.table(), cell.row());
                if (!key.equals(currentRow)) {
                    addRow(lines, currentRow, row);
                    currentRow = key;
                    row = new StringBuilder();
                    row.append(line);
                } else {
                    boolean newCell = previousCellOf(i) != cell.column();
                    row.append(newCell ? " | " : " / ").append(line);
                }
                continue;
            }
            addRow(lines, currentRow, row);
            currentRow = null;
            row = null;
            if (line.isBlank()) {
                continue;
            }
            String key = paragraph.heading() ? "H" + (++headings) : "P" + (++plain);
            lines.add(new OutlineLine(key, line.strip()));
        }
        addRow(lines, currentRow, row);
        return lines;
    }

    private int previousCellOf(int index) {
        return index > 0 && paragraphs.get(index - 1).cell() != null ? paragraphs.get(index - 1).cell().column() : -1;
    }

    private static void addRow(List<OutlineLine> lines, String key, StringBuilder row) {
        if (key != null && row != null && !row.toString().replace("|", "").replace("/", "").isBlank()) {
            lines.add(new OutlineLine(key, row.toString().strip()));
        }
    }

    /**
     * The paragraph's shown text with each place's marker where it sits and
     * hidden text left out. The document's own "[[" and "]]" are pulled
     * apart first, so only a marker put here reads as one.
     */
    private static String rendered(FormOutline.Paragraph paragraph, List<Found> places, Map<Found, String> ids) {
        String text = paragraph.anchorText();
        Mask mask = Mask.of(paragraph);
        List<Found> sorted = new ArrayList<>(places);
        sorted.sort(Comparator.comparingInt(Found::start));
        StringBuilder line = new StringBuilder();
        int position = 0;
        for (Found place : sorted) {
            appendShown(line, text, mask, position, place.start());
            line.append("[[").append(ids.get(place)).append("]]");
            position = Math.max(position, place.end());
        }
        appendShown(line, text, mask, position, text.length());
        return line.toString().replace('\n', ' ');
    }

    private static void appendShown(StringBuilder line, String text, Mask mask, int from, int to) {
        StringBuilder shown = new StringBuilder();
        appendVisible(shown, text, mask, from, to);
        line.append(FillSpotPromptBuilder.bracketsApart(shown.toString()));
    }

    private static void appendVisible(StringBuilder line, String text, Mask mask, int from, int to) {
        for (int i = from; i < to && i < text.length(); i++) {
            if (!mask.hidden(i)) {
                line.append(text.charAt(i));
            }
        }
    }

    private static String visibleText(FormOutline.Paragraph paragraph) {
        StringBuilder line = new StringBuilder();
        appendVisible(line, paragraph.anchorText(), Mask.of(paragraph), 0, paragraph.anchorText().length());
        return line.toString();
    }

    // ---------------------------------------------------------------- counting blanks the filler cannot reach

    private static int countBlanks(FormOutline.Paragraph paragraph) {
        String text = paragraph.anchorText();
        Mask mask = Mask.of(paragraph);
        boolean[] taken = new boolean[text.length()];
        int count = 0;
        List<Pattern> patterns = new ArrayList<>(List.of(DATE_MASK, UNDERSCORES, DOT_LEADER));
        for (Pattern pattern : patterns) {
            for (Matcher matcher = pattern.matcher(text); matcher.find(); ) {
                if (mask.eligible(matcher.start(), matcher.end()) && !anyTaken(taken, matcher.start(), matcher.end())) {
                    mark(taken, matcher.start(), matcher.end());
                    count++;
                }
            }
        }
        for (Pattern bracket : BRACKETS) {
            for (Matcher matcher = bracket.matcher(text); matcher.find(); ) {
                if (isPrompt(matcher.group(1)) && mask.eligible(matcher.start(), matcher.end())
                        && !anyTaken(taken, matcher.start(), matcher.end())) {
                    mark(taken, matcher.start(), matcher.end());
                    count++;
                }
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- small rules

    /** Whether a bracket's content reads as a prompt: some letters, more than one character, and not a reference or a note. */
    static boolean isPrompt(String inner) {
        String trimmed = inner.strip();
        if (trimmed.codePointCount(0, trimmed.length()) < 2 || trimmed.codePoints().noneMatch(Character::isLetter)) {
            return false;
        }
        return !NOT_A_PROMPT.matcher(trimmed).matches();
    }

    private static SpotCandidate.Tier promptTier(String inner) {
        String trimmed = inner.strip();
        boolean upper = trimmed.equals(trimmed.toUpperCase(Locale.ROOT)) && !trimmed.equals(trimmed.toLowerCase(Locale.ROOT));
        boolean title = true;
        for (String word : trimmed.split("\\s+")) {
            if (!word.isEmpty() && Character.isLetter(word.codePointAt(0)) && !Character.isUpperCase(word.codePointAt(0))) {
                title = false;
            }
        }
        boolean promptWord = false;
        for (String word : trimmed.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            promptWord |= PROMPT_WORDS.contains(word);
        }
        return upper || title || promptWord ? SpotCandidate.Tier.HIGH : SpotCandidate.Tier.MEDIUM;
    }

    private static boolean isBlankCharacter(char c) {
        return c == ' ' || c == '\t' || c == '\u00A0' || c == '\u2002' || c == '\u2003' || c == '\u2007' || c == '\u3000';
    }

    private static boolean isBlank(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!isBlankCharacter(text.charAt(i)) && !Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int nextBreak(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) == '\t' || text.charAt(i) == '\n') {
                return i;
            }
        }
        return text.length();
    }

    private static boolean anyTaken(boolean[] taken, int start, int end) {
        for (int i = start; i < end; i++) {
            if (taken[i]) {
                return true;
            }
        }
        return false;
    }

    private static void mark(boolean[] taken, int start, int end) {
        for (int i = start; i < end && i < taken.length; i++) {
            taken[i] = true;
        }
    }

    private static int charIndex(String text, int codePoints) {
        return text.offsetByCodePoints(0, Math.min(codePoints, text.codePointCount(0, text.length())));
    }

    private static String cellKey(FormOutline.Cell cell) {
        return cellKey(cell.table(), cell.row(), cell.column());
    }

    private static String cellKey(int table, int row, int column) {
        return table + "/" + row + "/" + column;
    }

    static String rowKey(int table, int row) {
        return "T" + table + "R" + (row + 1);
    }

    /**
     * Per character of a paragraph's anchor text: whether it may be part of
     * a blank, whether it is underlined, whether it is hidden, and whether a
     * control or an empty field sits just before it.
     */
    private record Mask(boolean[] eligible, boolean[] underlined, boolean[] hidden, boolean[] cutBefore) {

        static Mask of(FormOutline.Paragraph paragraph) {
            String text = paragraph.anchorText();
            boolean[] eligible = new boolean[text.length()];
            boolean[] underlined = new boolean[text.length()];
            boolean[] hidden = new boolean[text.length()];
            for (FormOutline.Atom atom : paragraph.atoms()) {
                if (atom instanceof FormOutline.Run run) {
                    int start = charIndex(text, run.start());
                    int end = charIndex(text, run.end());
                    for (int i = start; i < end; i++) {
                        eligible[i] = !run.hidden() && !run.inLink() && !run.inField();
                        underlined[i] = run.underlined();
                        hidden[i] = run.hidden();
                    }
                }
            }
            return new Mask(eligible, underlined, hidden, new boolean[text.length() + 1]);
        }

        void cutAt(int i) {
            if (i >= 0 && i < cutBefore.length) {
                cutBefore[i] = true;
            }
        }

        boolean cutBefore(int i) {
            return i >= 0 && i < cutBefore.length && cutBefore[i];
        }

        /** Whether something sits strictly inside {@code [start, end)}. */
        boolean cutWithin(int start, int end) {
            for (int i = start + 1; i < end; i++) {
                if (cutBefore(i)) {
                    return true;
                }
            }
            return false;
        }

        boolean eligible(int i) {
            return i >= 0 && i < eligible.length && eligible[i];
        }

        boolean eligible(int start, int end) {
            for (int i = start; i < end; i++) {
                if (!eligible(i)) {
                    return false;
                }
            }
            return true;
        }

        boolean underlined(int i) {
            return underlined[i];
        }

        boolean hidden(int i) {
            return i < hidden.length && hidden[i];
        }
    }
}
