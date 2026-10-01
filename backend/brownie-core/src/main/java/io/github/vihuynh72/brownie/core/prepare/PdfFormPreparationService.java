package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.NotPdfArtifactException;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfFormReader;
import io.github.vihuynh72.brownie.core.document.PdfFormReading;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfPoint;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.UnusablePdfFormException;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldIds;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.PdfSpotCandidate;
import io.github.vihuynh72.brownie.core.template.PdfSpotCandidateDetector;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import io.github.vihuynh72.brownie.core.template.TableCellLabels;
import io.github.vihuynh72.brownie.core.template.TemplateBindingValidator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Makes an uploaded PDF ready to fill: reads it as a form, keeps that
 * reading, and finds the places to fill in it, named.
 *
 * <p>The reading is kept once per file and reader version, so preparing
 * the same PDF again reads nothing; a file the reader refused is refused
 * again from what was kept, with the same reason ({@link
 * UnusablePdfFormException}). The places come from two sources:
 * <ul>
 * <li>the form's own text fields that a person could type in (not
 * read-only, shown on a page), bound to the field by its full name, as
 * spots that came with the form. Check boxes, choices, signature fields and
 * the rest are left for the person and counted
 * ({@link PreparationNotice#PDF_FIELDS_LEFT});
 * <li>on a PDF with none of those fields and with text on its pages, the
 * blanks {@link PdfSpotCandidateDetector} finds (lines, dots, underscores,
 * space after a label, empty boxes), bound to a box on the page in the
 * style of the words beside it, with the text made smaller to fit when it
 * must, as spots found by Brownie. A signature line is never kept ({@link
 * PreparationNotice#SIGNATURE_LINES_LEFT}).
 * </ul>
 * A form that has fields to type in is taken as it was made: the blanks
 * printed beside its fields are usually the same places again, so no boxes
 * are guessed on it, and the person draws one where the form has no field.
 * A PDF with no text and no fields is a scan: it gets no places and is
 * said to be one ({@link PreparationNotice#SCANNED_PDF}); a person draws
 * boxes on it instead.
 *
 * <p>A place the naming step decides is not a place to fill is left out,
 * as on a Word form, and counted ({@link PreparationNotice#PLACES_LEFT_OUT});
 * it may leave out only the rules' weaker guesses ({@link #sure}), never one
 * of the form's own fields, and the boxes of one grid only all together,
 * except a box kept for the office. A place kept is then checked the way a
 * template's places are ({@link TemplateBindingValidator}): one that check
 * would refuse (off the page the crop box shows, or covering a place kept
 * before it) is left out too and counted ({@link
 * PreparationNotice#SPOTS_SKIPPED}), so that the places offered can always
 * be sent back as they came.
 *
 * <p>The active {@link SpotNamer} names the places from an outline of the
 * pages, one line per line of text with each place marked where it sits.
 * The rules' own names come first from what the form says about a field
 * (its description, then a readable form of its name), then from the words
 * beside the place. Every place starts optional, whatever the form or the
 * naming step suggests; the suggestion is kept as a hint. Field ids are made
 * from the names ({@link FieldIds#fromLabel}), each once.
 */
public class PdfFormPreparationService {

    /** Room for a date written out in full ("September 28, 2026") in a field with a length limit. */
    static final int LONG_DATE_LENGTH = 18;

    private static final int MAX_CONTEXT_CHARACTERS = 200;
    private static final Pattern DATE_WORD = Pattern.compile("(?i)\\b(date|dob)\\b");
    private static final Pattern SIGNATURE_WORD = Pattern.compile("(?i)\\b(signature|sign here|signed|initials)\\b");
    private static final Pattern GENERIC_NAME = Pattern.compile("(?i)(text|field|text field|textfield|fill|fill in|untitled|undefined|blank)( ?\\d+)?");
    private static final Pattern NAME_PREFIX = Pattern.compile("^(txt|fld|tf|tb|text|field|str)(?=[A-Z_\\-\\d])");

    private final ArtifactService artifactService;
    private final PdfFormReader reader;
    private final PdfFormExtractionVersionRepository readings;
    private final SpotNamer spotNamer;

    public PdfFormPreparationService(
            ArtifactService artifactService, PdfFormReader reader, PdfFormExtractionVersionRepository readings, SpotNamer spotNamer) {
        this.artifactService = artifactService;
        this.reader = reader;
        this.readings = readings;
        this.spotNamer = spotNamer;
    }

    /**
     * @throws UnusablePdfFormException when the PDF cannot be filled at all
     * @throws NotPdfArtifactException when the artifact is not a PDF
     */
    public PdfFormPreparation prepare(long workspaceId, long userId, long artifactId) {
        PdfFormExtractionVersion reading = readOnce(workspaceId, userId, artifactId);
        if (reading.status() != ExtractionStatus.COMPLETE) {
            throw new UnusablePdfFormException(reading.unsupportedReason(), reading.unsupportedDetail());
        }
        PdfFormGraph graph = reading.graph();
        Found found = candidates(graph);
        List<PreparationNotice> notices = new ArrayList<>();
        if (found.leftFields() > 0) {
            notices.add(new PreparationNotice(PreparationNotice.PDF_FIELDS_LEFT, found.leftFields(), null));
        }
        if (found.candidates().isEmpty()) {
            boolean scanned = graph.pages().stream().noneMatch(PdfFormGraph.Page::hasText) && graph.acroForm().fields().isEmpty();
            notices.add(new PreparationNotice(scanned ? PreparationNotice.SCANNED_PDF : PreparationNotice.NO_SPOTS_FOUND, 0, null));
            return new PdfFormPreparation(artifactId, reading.id(), List.of(), notices, NamingSource.RULES, SpotNaming.NO_CANDIDATES);
        }

        SpotNaming naming = spotNamer.name(workspaceId, userId, new SpotNamingInput(
                DocumentKind.PDF, outline(graph, found.places()), found.candidates().stream().map(Candidate::naming).toList(), List.of()));
        Map<String, NamedSpot> byId = naming.spots().stream().collect(Collectors.toMap(NamedSpot::id, Function.identity(), (a, b) -> a));

        List<PreparedPdfSpot> spots = new ArrayList<>();
        List<FieldDefinition> checked = new ArrayList<>();
        Set<String> takenIds = new HashSet<>();
        int signatureLines = 0;
        int leftOut = 0;
        int skipped = 0;
        for (Candidate candidate : found.candidates()) {
            NamedSpot named = byId.getOrDefault(candidate.naming().id(), RulesOnlySpotNamer.byRules(candidate.naming()));
            if (!named.keep()) {
                if (candidate.naming().signatureLike()) {
                    signatureLines++;
                } else {
                    leftOut++;
                }
                continue;
            }
            PreparedPdfSpot spot = candidate.prepared(named, takenIds);
            checked.add(spot.definition());
            String fieldId = spot.definition().fieldId();
            if (TemplateBindingValidator.validate(graph, checked).stream().anyMatch(problem -> problem.fieldId().equals(fieldId))) {
                checked.remove(checked.size() - 1);
                skipped++;
                continue;
            }
            spots.add(spot);
        }
        if (!spots.isEmpty()) {
            notices.add(0, new PreparationNotice(PreparationNotice.SPOTS_FOUND, spots.size(), null));
        } else {
            notices.add(new PreparationNotice(PreparationNotice.NO_SPOTS_FOUND, 0, null));
        }
        if (signatureLines > 0) {
            notices.add(new PreparationNotice(PreparationNotice.SIGNATURE_LINES_LEFT, signatureLines, null));
        }
        if (leftOut > 0) {
            notices.add(new PreparationNotice(PreparationNotice.PLACES_LEFT_OUT, leftOut, null));
        }
        if (skipped > 0) {
            notices.add(new PreparationNotice(PreparationNotice.SPOTS_SKIPPED, skipped, null));
        }
        notices.addAll(naming.notices());
        return new PdfFormPreparation(artifactId, reading.id(), spots, notices, naming.source(), naming.rulesOnlyReason());
    }

    /** The kept reading of this file under the current reader, reading and keeping it first when there is none. */
    private PdfFormExtractionVersion readOnce(long workspaceId, long userId, long artifactId) {
        return readings.findByArtifact(workspaceId, userId, artifactId, reader.parserVersion()).orElseGet(() -> {
            byte[] pdf = readPdf(workspaceId, userId, artifactId);
            return switch (reader.read(pdf)) {
                case PdfFormReading.Supported(PdfFormGraph graph) ->
                        readings.saveComplete(workspaceId, userId, artifactId, reader.parserVersion(), graph);
                case PdfFormReading.Unsupported(var reason, String detail) ->
                        readings.saveUnsupported(workspaceId, userId, artifactId, reader.parserVersion(), reason, detail);
            };
        });
    }

    private byte[] readPdf(long workspaceId, long userId, long artifactId) {
        try (ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId)) {
            if (readable.artifact().detectedMediaType() != SupportedMediaType.PDF) {
                throw new NotPdfArtifactException(artifactId, readable.artifact().detectedMediaType());
            }
            return readable.content().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read PDF " + artifactId + " to prepare it as a form.", e);
        }
    }

    // ---- finding the places ----------------------------------------------------------------------------------

    /** What the reading offers to name: the candidates in reading order, where each sits, and the fields left for the person. */
    record Found(List<Candidate> candidates, List<Place> places, int leftFields) {
    }

    /** Where a candidate sits: its page and box, for the outline's markers and for reading order. */
    record Place(String id, int pageNumber, PdfRect box) {
    }

    /**
     * One place offered to the naming step, and how it becomes a field if
     * kept: bound to a form field (with the form's own required flag) or to
     * a box found on the page.
     */
    record Candidate(NamingCandidate naming, Place place, String formFieldName, boolean formRequired, PdfSpotCandidate found) {

        PreparedPdfSpot prepared(NamedSpot named, Set<String> takenIds) {
            String label = FieldIds.normalizeLabel(named.label());
            if (label == null) {
                label = naming.rulesLabel();
            }
            String fieldId = FieldIds.fromLabel(TableCellLabels.idWords(label), takenIds);
            takenIds.add(fieldId);
            FieldBindingTarget binding = formFieldName != null
                    ? new FieldBindingTarget.AcroFormField(formFieldName)
                    : new FieldBindingTarget.PageBox(found.pageNumber(), found.box().x(), found.box().y(), found.box().width(),
                            found.box().height(), found.style(), false, PdfOverflowPolicy.SHRINK_TO_FIT);
            SpotOrigin origin = formFieldName != null ? SpotOrigin.FORM : SpotOrigin.FOUND_BY_BROWNIE;
            FieldDefinition definition = new FieldDefinition(
                    fieldId, named.type(), FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL, binding, label, origin, null, null);
            boolean requiredHint = formFieldName != null ? formRequired : named.requiredHint();
            return new PreparedPdfSpot(definition, named.namedByModel() ? NamingSource.MODEL : NamingSource.RULES, requiredHint);
        }
    }

    /**
     * The form's fillable text fields ({@code f1}, {@code f2}, ...), or,
     * when it has none, the blanks found on its pages ({@code c1}, {@code
     * c2}, ...), in reading order: page, then top to bottom, then left to
     * right.
     */
    static Found candidates(PdfFormGraph graph) {
        List<Candidate> candidates = new ArrayList<>();
        int leftFields = 0;
        int fieldNumber = 0;
        for (PdfFormGraph.Field field : graph.acroForm().fields()) {
            if (!TemplateBindingValidator.fillable(field)) {
                if (field.kind() != PdfFormGraph.FieldKind.BUTTON) {
                    leftFields++;
                }
                continue;
            }
            String id = "f" + (++fieldNumber);
            PdfFormGraph.Widget widget = field.widgets().get(0);
            String nearby = nearbyLabel(graph, widget);
            String label = formFieldLabel(field, nearby, fieldNumber);
            FieldType type = (field.dateFormat() != null || DATE_WORD.matcher(label).find())
                    && (field.maxLen() == null || field.maxLen() >= LONG_DATE_LENGTH) ? FieldType.DATE : FieldType.TEXT;
            boolean signatureLike = SIGNATURE_WORD.matcher(label).find();
            // A field the form made is a place to fill, as a Word form's own controls are: the naming step names it but keeps it.
            NamingCandidate naming = new NamingCandidate(
                    id, "FORM_FIELD", label, type, formFieldContext(field, nearby), signatureLike, null, true, null, List.of());
            candidates.add(new Candidate(naming, new Place(id, widget.pageNumber(), widget.box()), field.fullName(), field.required(), null));
        }
        int blankNumber = 0;
        // A form with fields to type in is filled through them; boxes guessed beside them would mostly be the same places twice.
        if (fieldNumber == 0 && graph.pages().stream().anyMatch(PdfFormGraph.Page::hasText)) {
            for (PdfSpotCandidate found : PdfSpotCandidateDetector.detect(graph)) {
                String label = FieldIds.normalizeLabel(found.labelGuess());
                if (label == null) {
                    label = "Blank " + (++blankNumber);
                }
                FieldType type = DATE_WORD.matcher(label).find() ? FieldType.DATE : FieldType.TEXT;
                // A box kept for the office stays the naming step's to leave out, whatever the rest of its grid is.
                NamingCandidate naming = new NamingCandidate(found.id(), found.kind().name(), label, type, found.contextText(),
                        found.signatureLike(), null, sure(found), found.forOfficeUse() ? null : found.gridKey(), found.tableValues());
                candidates.add(new Candidate(naming, new Place(found.id(), found.pageNumber(), found.box()), null, false, found));
            }
        }
        candidates.sort(Comparator.comparingInt((Candidate candidate) -> candidate.place().pageNumber())
                .thenComparingDouble(candidate -> Math.round(candidate.place().box().y()))
                .thenComparingDouble(candidate -> candidate.place().box().x()));
        return new Found(List.copyOf(candidates), candidates.stream().map(Candidate::place).toList(), leftFields);
    }

    /**
     * Whether the rules are sure a blank found on a page is one to fill in,
     * so that the naming step may not leave it out: a run of underscores or
     * dots, unless it is for a signature or kept for the office. A label with
     * space after it, a lone line and a lone box are guesses; the boxes of a
     * grid are kept or left out together instead ({@link
     * PdfSpotCandidate#gridKey()}).
     */
    static boolean sure(PdfSpotCandidate found) {
        boolean leader = found.kind() == PdfSpotCandidate.Kind.UNDERSCORES || found.kind() == PdfSpotCandidate.Kind.DOT_LEADER;
        return leader && !found.signatureLike() && !found.forOfficeUse();
    }

    /**
     * The name the rules give a form field: its own description, then a
     * readable form of its name unless that is one a form maker left as it
     * came ("Text1"), then the words beside it, then the readable name
     * however plain, and last "Field" and its number.
     */
    static String formFieldLabel(PdfFormGraph.Field field, String nearby, int number) {
        String tooltip = FieldIds.normalizeLabel(field.tooltip());
        if (tooltip != null) {
            return tooltip;
        }
        String readable = FieldIds.normalizeLabel(readableName(field.fullName()));
        if (readable != null && !GENERIC_NAME.matcher(readable).matches()) {
            return readable;
        }
        String beside = FieldIds.normalizeLabel(nearby);
        if (beside != null) {
            return beside;
        }
        return readable != null ? readable : "Field " + number;
    }

    /**
     * A field name as words: the last part of a dotted name, without a
     * {@code [0]} index or a type prefix such as {@code txt}, split where the
     * case changes or at underscores, dashes and digits, and written as a
     * sentence ("txtCompanyName" reads "Company name").
     */
    static String readableName(String fullName) {
        String last = fullName.substring(fullName.lastIndexOf('.') + 1).replaceAll("\\[\\d+]", "");
        last = NAME_PREFIX.matcher(last).replaceFirst("");
        String spaced = last
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([A-Za-z])(\\d)", "$1 $2")
                .replaceAll("(\\d)([A-Za-z])", "$1 $2")
                .replaceAll("[_\\-\\s]+", " ")
                .strip();
        if (spaced.isEmpty()) {
            return "";
        }
        String lower = spaced.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** The words just before a field's first widget on its line, or on the short line above it, colon removed; null when there are none. */
    static String nearbyLabel(PdfFormGraph graph, PdfFormGraph.Widget widget) {
        PdfFormGraph.Page page = graph.pages().stream().filter(candidate -> candidate.pageNumber() == widget.pageNumber()).findFirst().orElse(null);
        if (page == null || !page.hasText()) {
            return null;
        }
        return PdfSpotCandidateDetector.boxSuggestion(graph, widget.pageNumber(), new PdfPoint(widget.box().x() + 1, widget.box().centerY()))
                .labelGuess();
    }

    private static String formFieldContext(PdfFormGraph.Field field, String nearby) {
        StringBuilder context = new StringBuilder();
        if (field.tooltip() != null && !field.tooltip().isBlank()) {
            context.append("description: ").append(field.tooltip().strip()).append("; ");
        }
        context.append("field name: ").append(field.fullName());
        if (nearby != null) {
            context.append("; beside: ").append(nearby);
        }
        String text = context.toString();
        return text.length() > MAX_CONTEXT_CHARACTERS ? text.substring(0, MAX_CONTEXT_CHARACTERS) : text;
    }

    // ---- the outline -----------------------------------------------------------------------------------------

    /**
     * One outline line per line of text ({@code P1L0}, {@code P1L1}, ...),
     * with each place marked as {@code [[id]]} where it sits: after the last
     * word that starts before it, on the line it shares, in place of the
     * underscores or dots it was found on. A place on no line
     * of text (an empty table cell, a field in open space) is a line of its
     * own ({@code P1S} and the place's id), put where it sits top to bottom.
     */
    static List<OutlineLine> outline(PdfFormGraph graph, List<Place> places) {
        List<OutlineLine> outline = new ArrayList<>();
        for (PdfFormGraph.Page page : graph.pages()) {
            List<Place> onPage = places.stream().filter(place -> place.pageNumber() == page.pageNumber()).toList();
            List<Entry> entries = new ArrayList<>();
            Set<String> placed = new HashSet<>();
            for (PdfFormGraph.Line line : page.lines()) {
                List<Place> onLine = onPage.stream().filter(place -> !placed.contains(place.id()) && sharesLine(line.box(), place.box())).toList();
                onLine.forEach(place -> placed.add(place.id()));
                entries.add(new Entry(line.box().y(), new OutlineLine(
                        "P" + page.pageNumber() + "L" + line.index(), marked(line, onLine))));
            }
            for (Place place : onPage) {
                if (!placed.contains(place.id())) {
                    entries.add(new Entry(place.box().y(), new OutlineLine("P" + page.pageNumber() + "S" + place.id(), marker(place.id()))));
                }
            }
            entries.sort(Comparator.comparingDouble(Entry::y));
            entries.forEach(entry -> outline.add(entry.line()));
        }
        return outline;
    }

    private record Entry(double y, OutlineLine line) {
    }

    private static boolean sharesLine(PdfRect line, PdfRect place) {
        double shared = Math.min(line.bottom(), place.bottom()) - Math.max(line.y(), place.y());
        return shared > 0.3 * Math.min(Math.max(line.height(), 1), Math.max(place.height(), 1));
    }

    private static String marked(PdfFormGraph.Line line, List<Place> onLine) {
        List<Place> byX = onLine.stream().sorted(Comparator.comparingDouble(place -> place.box().x())).toList();
        StringBuilder text = new StringBuilder();
        int next = 0;
        for (PdfFormGraph.Word word : line.words()) {
            while (next < byX.size() && byX.get(next).box().x() < word.box().x()) {
                appendWithSpace(text, marker(byX.get(next++).id()));
            }
            // A run of underscores or dots a place was found on is the place itself: its marker stands for it.
            if (byX.stream().noneMatch(place -> place.box().encloses(word.box(), 1))) {
                // The page's own "[[" and "]]" are pulled apart, so only a marker put here reads as one.
                appendWithSpace(text, FillSpotPromptBuilder.bracketsApart(word.text()));
            }
        }
        while (next < byX.size()) {
            appendWithSpace(text, marker(byX.get(next++).id()));
        }
        return text.isEmpty() ? FillSpotPromptBuilder.bracketsApart(line.text()) : text.toString();
    }

    private static void appendWithSpace(StringBuilder text, String part) {
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(part);
    }

    private static String marker(String id) {
        return "[[" + id + "]]";
    }
}
