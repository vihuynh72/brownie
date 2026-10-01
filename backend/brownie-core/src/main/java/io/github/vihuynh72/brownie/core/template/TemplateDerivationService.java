package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStorageException;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfPoint;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.prepare.AnchorPlacement;
import io.github.vihuynh72.brownie.core.prepare.BlankLines;
import io.github.vihuynh72.brownie.core.prepare.DocxAnchor;
import io.github.vihuynh72.brownie.core.prepare.EditedDocx;
import io.github.vihuynh72.brownie.core.prepare.FillSpotEditor;
import io.github.vihuynh72.brownie.core.prepare.FillSpotPlacementException;
import io.github.vihuynh72.brownie.core.prepare.SpotEdit;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import io.github.vihuynh72.brownie.core.text.CodePoints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Works out a new template version from the one a document is on, with its
 * fill spots added, renamed or taken away as a person asked, and proves it
 * the way activation proves a draft ({@link ActivationGate}, then a sample
 * fill and render) before anything is written. This is the slow part (the
 * Word file is edited and read back, and the sample is rendered by the
 * sandboxed LibreOffice), so it runs outside any transaction and writes
 * only what cannot be rolled back anyway: the edited file and its
 * extraction, which belong to the workspace like a failed compile's output
 * would. Writing the version and moving the document is the caller's one
 * short transaction.
 *
 * <p>What each change does to the file depends on who made the spot:
 * <ul>
 * <li>An added spot is inserted where the anchor says ({@link FillSpotEditor}),
 * tagged with a new ID made from its label that no version of the template
 * has used, and its field starts out optional.</li>
 * <li>Taking away a spot Brownie inserted puts the form's own runs back;
 * one Brownie tagged in an existing control loses only the tag; one the
 * form came with keeps the file as it is and only loses its field.</li>
 * <li>Renaming changes the label and nothing else, so the file, its
 * extraction and the base's proven render are all reused.</li>
 * </ul>
 * The base's rules that still apply are carried over with their statuses,
 * minus what named a spot that was taken away. A change inside a paragraph
 * a rule protects, or one a field is bound to by node, is refused: node IDs
 * inside an edited paragraph move, so the protection would silently point
 * somewhere else.
 *
 * <p>A PDF form's spots are its own form fields and boxes Brownie draws on
 * its pages, so a change to one only changes the field list: the new
 * version keeps the base's file and form reading, a box is added, moved or
 * given another text size by changing its binding, and taking a spot away
 * leaves the file as it is. The new bindings are checked against the form
 * reading the way a draft's are ({@link TemplateBindingValidator}), a box
 * that cannot go where it was put is refused with the reason, and the proof
 * is the PDF sample fill, which is quick and needs no renderer. A Word
 * change on a PDF form, or a box on a Word form, is refused in words.
 */
public class TemplateDerivationService {

    /** The most changes one request can make; the page makes one or two at a time. */
    public static final int MAX_CHANGES = 50;
    /** The most fill spots a version can have after a change: far above any real form, low enough to keep every check quick. */
    public static final int MAX_SPOTS = 200;

    /** What a person is told when a place in a Word form's text is asked for on a PDF form. */
    public static final String WORD_CHANGE_ON_PDF =
            "This form is a PDF, so a new fill spot is a box on its page. Draw the box where the text should go.";
    /** What a person is told when a box on a page is asked for on a Word form. */
    public static final String PDF_CHANGE_ON_WORD =
            "This form is a Word file, so its fill spots are places in its text, not boxes on a page. Choose the place in the text.";

    /** The smallest and largest text size, in points, a box's text can start at: the same bounds the template API keeps. */
    public static final double MIN_BOX_TEXT_SIZE = 4;
    public static final double MAX_BOX_TEXT_SIZE = 72;

    private final TemplateLineageRepository lineageRepository;
    private final RuleRepository ruleRepository;
    private final ExtractionVersionRepository extractionVersionRepository;
    private final ArtifactService artifactService;
    private final DocumentExtractionService documentExtractionService;
    private final DocxStructuralExtractor docxExtractor;
    private final FillSpotEditor fillSpotEditor;
    private final TemplateBaselineRenderer templateBaselineRenderer;
    private final TemplateBaselineRenderRepository templateBaselineRenderRepository;
    private final PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository;

    public TemplateDerivationService(
            TemplateLineageRepository lineageRepository,
            RuleRepository ruleRepository,
            ExtractionVersionRepository extractionVersionRepository,
            ArtifactService artifactService,
            DocumentExtractionService documentExtractionService,
            DocxStructuralExtractor docxExtractor,
            FillSpotEditor fillSpotEditor,
            TemplateBaselineRenderer templateBaselineRenderer,
            TemplateBaselineRenderRepository templateBaselineRenderRepository,
            PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository) {
        this.lineageRepository = lineageRepository;
        this.ruleRepository = ruleRepository;
        this.extractionVersionRepository = extractionVersionRepository;
        this.artifactService = artifactService;
        this.documentExtractionService = documentExtractionService;
        this.docxExtractor = docxExtractor;
        this.fillSpotEditor = fillSpotEditor;
        this.templateBaselineRenderer = templateBaselineRenderer;
        this.templateBaselineRenderRepository = templateBaselineRenderRepository;
        this.pdfFormExtractionVersionRepository = pdfFormExtractionVersionRepository;
    }

    /**
     * Refuses, with {@link FillSpotChangeInvalidException}, a change made
     * for the other kind of form: a place in a Word form's text ({@link
     * FillSpotChange.Add}) on a PDF form, whose spots are boxes on its
     * pages, or a box ({@link FillSpotChange.AddBox}, {@link
     * FillSpotChange.MoveBox}, {@link FillSpotChange.RestyleBox}) on a Word
     * form, which has no pages to put one on. Renaming and taking a spot
     * away mean the same on both.
     */
    public static void requireChangesFit(TemplateVersion base, List<FillSpotChange> changes) {
        if (changes == null) {
            return;
        }
        for (FillSpotChange change : changes) {
            boolean forWord = change instanceof FillSpotChange.Add;
            boolean forPdf = change instanceof FillSpotChange.AddBox || change instanceof FillSpotChange.MoveBox
                    || change instanceof FillSpotChange.RestyleBox;
            if (forWord && base.kind() == TemplateKind.PDF) {
                throw new FillSpotChangeInvalidException(WORD_CHANGE_ON_PDF);
            }
            if (forPdf && base.kind() == TemplateKind.DOCX) {
                throw new FillSpotChangeInvalidException(PDF_CHANGE_ON_WORD);
            }
        }
    }

    /**
     * The key that finds a version already made from {@code baseVersionId}
     * for this same request: the SHA-256 of the base, the changes as asked
     * (labels normalized, so spacing does not make a second version) and
     * the reader that reads the file. It does not depend on the IDs the
     * changes would be given, so a retry finds the version the first
     * attempt made even though the new ID is taken by then. Checks the
     * request's shape as it goes.
     */
    public String derivationKey(long baseVersionId, List<FillSpotChange> changes) {
        requireRequestShape(changes);
        StringBuilder json = new StringBuilder("{\"changes\":[");
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            switch (changes.get(i)) {
                case FillSpotChange.Add add -> json.append("{\"kind\":\"ADD\",\"anchor\":").append(anchorJson(add.anchor()))
                        .append(",\"label\":").append(quote(requireLabel(add.label())))
                        .append(",\"type\":").append(quote(add.type().name()))
                        .append(",\"placedByModel\":").append(add.placedByModel()).append('}');
                case FillSpotChange.Rename rename -> json.append("{\"kind\":\"RENAME\",\"fieldId\":").append(quote(rename.fieldId()))
                        .append(",\"label\":").append(quote(requireLabel(rename.label()))).append('}');
                case FillSpotChange.Remove remove -> json.append("{\"kind\":\"REMOVE\",\"fieldId\":").append(quote(remove.fieldId())).append('}');
                case FillSpotChange.AddBox add -> json.append("{\"kind\":\"ADD_BOX\",\"page\":").append(add.pageNumber())
                        .append(",\"box\":").append(boxJson(add.box()))
                        .append(",\"label\":").append(quote(requireLabel(add.label())))
                        .append(",\"type\":").append(quote(add.type().name()))
                        .append(",\"style\":").append(add.style() == null ? "null" : styleJson(add.style()))
                        .append(",\"multiline\":").append(add.multiline())
                        .append(",\"overflow\":").append(quote(add.overflow().name()))
                        .append(",\"placedByModel\":").append(add.placedByModel()).append('}');
                case FillSpotChange.MoveBox move -> json.append("{\"kind\":\"MOVE_BOX\",\"fieldId\":").append(quote(move.fieldId()))
                        .append(",\"box\":").append(boxJson(move.box())).append('}');
                case FillSpotChange.RestyleBox restyle -> json.append("{\"kind\":\"RESTYLE_BOX\",\"fieldId\":")
                        .append(quote(restyle.fieldId()))
                        .append(",\"sizePt\":").append(restyle.sizePt() == null ? "null" : number(restyle.sizePt()))
                        .append(",\"overflow\":").append(quote(restyle.overflow() == null ? null : restyle.overflow().name()))
                        .append(",\"multiline\":").append(restyle.multiline()).append('}');
            }
        }
        json.append("]}");
        return sha256Hex(baseVersionId + "\n" + json + "\n" + docxExtractor.parserVersion());
    }

    /**
     * What the document's history says about the changes: "Added a fill
     * spot: Company.", "Renamed the fill spot Name to Full name.", "Removed
     * the fill spot Fax.", "Moved the fill spot Date.", up to three of them,
     * or how many there were. Labels are as the person sees them in {@code
     * base}.
     */
    public static String editReason(TemplateVersion base, List<FillSpotChange> changes) {
        if (changes.size() > 3) {
            boolean allMoves = changes.stream().allMatch(change -> change instanceof FillSpotChange.MoveBox);
            return (allMoves ? "Moved " : "Changed ") + changes.size() + " fill spots.";
        }
        List<String> sentences = new ArrayList<>();
        for (FillSpotChange change : changes) {
            sentences.add(switch (change) {
                case FillSpotChange.Add add -> "Added a fill spot: " + labelOrAsGiven(add.label()) + ".";
                case FillSpotChange.Rename rename -> "Renamed the fill spot " + currentLabel(base, rename.fieldId()) + " to "
                        + labelOrAsGiven(rename.label()) + ".";
                case FillSpotChange.Remove remove -> "Removed the fill spot " + currentLabel(base, remove.fieldId()) + ".";
                case FillSpotChange.AddBox add -> "Added a fill spot: " + labelOrAsGiven(add.label()) + ".";
                case FillSpotChange.MoveBox move -> "Moved the fill spot " + currentLabel(base, move.fieldId()) + ".";
                case FillSpotChange.RestyleBox restyle -> "Changed how the fill spot " + currentLabel(base, restyle.fieldId())
                        + " shows its text.";
            });
        }
        return String.join(" ", sentences);
    }

    /**
     * Works out and proves the version {@code changes} make from {@code
     * base}. Throws, having written at most an unused file and extraction:
     * {@link FillSpotChangeInvalidException} for a request that cannot be
     * made as asked, a change for the other kind of form among them; {@link
     * FillSpotPlacementException} for a place that changed since it was
     * chosen ({@code ANCHOR_STALE}) or where a spot cannot go (a box off its
     * page, too small or covering another place among them); {@link
     * FillSpotNotPlacedException} for a spot the filler would never reach;
     * the gate's own exceptions for a version that would not activate;
     * {@link FillSpotBaselineFailedException} when the sample does not print
     * correctly.
     */
    public PreparedDerivation prepare(long workspaceId, long userId, TemplateVersion base, List<FillSpotChange> changes) {
        String derivationKey = derivationKey(base.id(), changes);
        requireChangesFit(base, changes);
        if (base.kind() == TemplateKind.PDF) {
            return preparePdf(workspaceId, userId, base, changes, derivationKey);
        }
        String parserVersion = docxExtractor.parserVersion();
        Map<String, FieldDefinition> baseFields = new LinkedHashMap<>();
        base.fieldDefinitions().forEach(field -> baseFields.putIfAbsent(field.fieldId(), field));
        requireKnownScalarTargets(baseFields, changes);

        boolean editsFile = changes.stream().anyMatch(change -> change instanceof FillSpotChange.Add
                || change instanceof FillSpotChange.Remove remove && editsFileToRemove(baseFields.get(remove.fieldId())));
        DocxStructuralGraph baseGraph = editsFile ? currentGraphOf(workspaceId, userId, base.sourceArtifactId()) : null;
        Places places = baseGraph == null ? null : Places.of(TemplateLayoutProjector.project(base.templateId(), base.id(), baseGraph,
                base.fieldDefinitions()));

        Set<String> taken = new HashSet<>(lineageRepository.findFieldIdsEverUsed(workspaceId, userId, base.templateId()));
        taken.addAll(baseFields.keySet());
        if (baseGraph != null) {
            // A new spot's tag is its id, and the file may already carry that tag on a control that is no spot (a
            // checkbox left as it is, one in a header or footer): the tag would then name two controls.
            taken.addAll(contentControlTags(baseGraph));
        }
        List<FieldDefinition> fields = new ArrayList<>(base.fieldDefinitions());
        List<SpotEdit> edits = new ArrayList<>();
        Set<String> editedParagraphs = new HashSet<>();
        Set<String> removedFieldIds = new HashSet<>();
        List<String> changedFieldIds = new ArrayList<>();
        List<String> changeKinds = new ArrayList<>();
        List<String> addedFieldIds = new ArrayList<>();
        List<String> changeJson = new ArrayList<>();

        for (FillSpotChange change : changes) {
            switch (change) {
                case FillSpotChange.Rename rename -> {
                    FieldDefinition field = baseFields.get(rename.fieldId());
                    String label = requireLabel(rename.label());
                    replace(fields, withLabel(field, label));
                    changeJson.add("{\"kind\":\"RENAME\",\"fieldId\":" + quote(field.fieldId()) + ",\"fromLabel\":"
                            + quote(field.displayLabel()) + ",\"label\":" + quote(label) + "}");
                    changedFieldIds.add(field.fieldId());
                    changeKinds.add("RENAME");
                }
                case FillSpotChange.Remove remove -> {
                    FieldDefinition field = baseFields.get(remove.fieldId());
                    fields.removeIf(candidate -> candidate.fieldId().equals(field.fieldId()));
                    removedFieldIds.add(field.fieldId());
                    removalEdit(field).ifPresent(edit -> {
                        edits.add(edit);
                        editedParagraphs.add(paragraphHoldingTag(baseGraph, tagOf(field)));
                    });
                    changeJson.add("{\"kind\":\"REMOVE\",\"fieldId\":" + quote(field.fieldId()) + ",\"label\":" + quote(field.displayLabel())
                            + ",\"docxControl\":" + quote(field.docxControl() == null ? null : field.docxControl().name()) + "}");
                    changedFieldIds.add(field.fieldId());
                    changeKinds.add("REMOVE");
                }
                case FillSpotChange.Add add -> {
                    PlaceCheck place = checkPlace(add.anchor(), parserVersion, places, baseGraph);
                    String label = requireLabel(add.label());
                    String fieldId = FieldIds.fromLabel(label, taken);
                    taken.add(fieldId);
                    FieldDefinition field = new FieldDefinition(
                            fieldId, add.type(), FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                            new FieldBindingTarget.ContentControlTag(fieldId), label,
                            add.placedByModel() ? SpotOrigin.FOUND_BY_BROWNIE : SpotOrigin.ADDED_BY_PERSON,
                            add.anchor().placement() == AnchorPlacement.EXISTING_CONTROL
                                    ? DocxControlOrigin.TAGGED_BY_BROWNIE
                                    : DocxControlOrigin.INSERTED_BY_BROWNIE,
                            place.blankText());
                    insertInReadingOrder(fields, field, place.position(), places);
                    places.remember(fieldId, place.position());
                    edits.add(new SpotEdit.Insert(add.anchor(), fieldId, label, place.blankText()));
                    editedParagraphs.add(place.paragraphNodeId());
                    changeJson.add("{\"kind\":\"ADD\",\"fieldId\":" + quote(fieldId) + ",\"label\":" + quote(label)
                            + ",\"type\":" + quote(add.type().name()) + ",\"origin\":" + quote(field.origin().name())
                            + ",\"docxControl\":" + quote(field.docxControl().name()) + ",\"blankText\":" + quote(place.blankText())
                            + ",\"anchor\":" + anchorJson(add.anchor()) + "}");
                    changedFieldIds.add(fieldId);
                    changeKinds.add("ADD");
                    addedFieldIds.add(fieldId);
                }
                default -> throw new IllegalStateException("A box is not a change to a Word form: " + change);
            }
        }
        if (fields.isEmpty()) {
            throw new FillSpotChangeInvalidException("A form needs at least one fill spot, so the last one cannot be taken away.");
        }
        if (fields.size() > MAX_SPOTS) {
            throw new FillSpotChangeInvalidException("A form can have at most " + MAX_SPOTS + " fill spots.");
        }
        requireDistinctLabels(fields, changedFieldIds);

        List<RuleRevision> baseRules = ruleRepository.findByTemplateVersion(workspaceId, userId, base.id()).stream()
                .filter(rule -> rule.status() != RuleRevisionStatus.REJECTED)
                .toList();
        requireNothingProtectedIsEdited(editedParagraphs, baseRules, fields);
        List<RuleRevision> rules = new ArrayList<>();
        for (RuleRevision rule : baseRules) {
            rewrite(rule, removedFieldIds).ifPresent(rules::add);
        }

        ExtractionVersion edited = edits.isEmpty() ? null : storeEditedFile(workspaceId, userId, base, edits);
        long sourceArtifactId = edited == null ? base.sourceArtifactId() : edited.artifactId();
        long extractionVersionId = edited == null ? base.extractionVersionId() : edited.id();
        DocxStructuralGraph graph = edited == null ? pinnedGraphOf(workspaceId, userId, base) : edited.graph();

        String subject = "Template " + base.templateId() + " version made from version " + base.versionNumber();
        ActivationGate.check(subject, fields, graph, rules);
        if (!addedFieldIds.isEmpty()) {
            List<String> unplaced = TemplateLayoutProjector.project(base.templateId(), base.id(), graph, fields).unplacedFieldIds();
            for (String fieldId : addedFieldIds) {
                if (unplaced.contains(fieldId)) {
                    throw new FillSpotNotPlacedException(fieldId);
                }
            }
        }

        BaselineRenderResult baseline = baselineFor(workspaceId, userId, base, changes, sourceArtifactId, extractionVersionId, null, fields);

        String derivationJson = "{\"baseVersionId\":" + base.id() + ",\"parserVersion\":" + quote(parserVersion)
                + ",\"changes\":[" + String.join(",", changeJson) + "]}";
        return new PreparedDerivation(
                base.templateId(), base.id(), derivationKey, derivationJson, sourceArtifactId, TemplateKind.DOCX, extractionVersionId, null,
                fields, rules, baseline, changedFieldIds, changeKinds, editReason(base, changes));
    }

    /**
     * {@link #prepare} for a PDF form: only the field list changes. A new
     * box gets an ID no version of the template has used and starts out
     * optional; a moved or restyled box keeps its ID, value and name; a
     * spot taken away only loses its field. Every binding is then checked
     * against the base's form reading, the changed ones last so that a box
     * put over another place is the one reported, and the version is proven
     * by the PDF sample fill (reused as it is when only names changed).
     */
    private PreparedDerivation preparePdf(
            long workspaceId, long userId, TemplateVersion base, List<FillSpotChange> changes, String derivationKey) {
        Map<String, FieldDefinition> baseFields = new LinkedHashMap<>();
        base.fieldDefinitions().forEach(field -> baseFields.putIfAbsent(field.fieldId(), field));
        requireKnownScalarTargets(baseFields, changes);
        PdfFormGraph graph = pdfGraphOf(workspaceId, userId, base);

        Set<String> taken = new HashSet<>(lineageRepository.findFieldIdsEverUsed(workspaceId, userId, base.templateId()));
        taken.addAll(baseFields.keySet());
        List<FieldDefinition> fields = new ArrayList<>(base.fieldDefinitions());
        Set<String> removedFieldIds = new HashSet<>();
        Set<String> placedFieldIds = new HashSet<>();
        List<String> namedFieldIds = new ArrayList<>();
        List<String> changedFieldIds = new ArrayList<>();
        List<String> changeKinds = new ArrayList<>();
        List<String> changeJson = new ArrayList<>();

        for (FillSpotChange change : changes) {
            switch (change) {
                case FillSpotChange.Rename rename -> {
                    FieldDefinition field = fieldIn(fields, rename.fieldId());
                    String label = requireLabel(rename.label());
                    replace(fields, withLabel(field, label));
                    changeJson.add("{\"kind\":\"RENAME\",\"fieldId\":" + quote(field.fieldId()) + ",\"fromLabel\":"
                            + quote(field.displayLabel()) + ",\"label\":" + quote(label) + "}");
                    namedFieldIds.add(field.fieldId());
                    changedFieldIds.add(field.fieldId());
                    changeKinds.add("RENAME");
                }
                case FillSpotChange.Remove remove -> {
                    FieldDefinition field = fieldIn(fields, remove.fieldId());
                    fields.removeIf(candidate -> candidate.fieldId().equals(field.fieldId()));
                    removedFieldIds.add(field.fieldId());
                    changeJson.add("{\"kind\":\"REMOVE\",\"fieldId\":" + quote(field.fieldId()) + ",\"label\":"
                            + quote(field.displayLabel()) + ",\"docxControl\":null}");
                    changedFieldIds.add(field.fieldId());
                    changeKinds.add("REMOVE");
                }
                case FillSpotChange.AddBox add -> {
                    String label = requireLabel(add.label());
                    PdfTextStyle style = add.style() != null
                            ? requireTextSize(add.style())
                            : styleBeside(graph, add.pageNumber(), add.box());
                    FieldBindingTarget.PageBox binding = pageBox(
                            add.pageNumber(), add.box(), style, add.multiline(), add.overflow());
                    String fieldId = FieldIds.fromLabel(label, taken);
                    taken.add(fieldId);
                    FieldDefinition field = new FieldDefinition(
                            fieldId, add.type(), FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL, binding, label,
                            add.placedByModel() ? SpotOrigin.FOUND_BY_BROWNIE : SpotOrigin.ADDED_BY_PERSON, null, null);
                    insertInPageOrder(fields, field, graph);
                    changeJson.add("{\"kind\":\"ADD_BOX\",\"fieldId\":" + quote(fieldId) + ",\"label\":" + quote(label)
                            + ",\"type\":" + quote(add.type().name()) + ",\"origin\":" + quote(field.origin().name())
                            + ",\"page\":" + binding.page() + ",\"box\":" + boxJson(binding.box()) + ",\"style\":" + styleJson(style)
                            + ",\"multiline\":" + binding.multiline() + ",\"overflow\":" + quote(binding.overflow().name()) + "}");
                    placedFieldIds.add(fieldId);
                    namedFieldIds.add(fieldId);
                    changedFieldIds.add(fieldId);
                    changeKinds.add("ADD_BOX");
                }
                case FillSpotChange.MoveBox move -> {
                    FieldDefinition field = fieldIn(fields, move.fieldId());
                    FieldBindingTarget.PageBox from = requireBox(field,
                            "This box belongs to the PDF's own form; Brownie cannot move it.");
                    FieldBindingTarget.PageBox to = pageBox(from.page(), move.box(), from.style(), from.multiline(), from.overflow());
                    replace(fields, withBinding(field, to));
                    changeJson.add("{\"kind\":\"MOVE_BOX\",\"fieldId\":" + quote(field.fieldId()) + ",\"label\":"
                            + quote(field.displayLabel()) + ",\"page\":" + to.page() + ",\"from\":" + boxJson(from.box())
                            + ",\"box\":" + boxJson(to.box()) + "}");
                    placedFieldIds.add(field.fieldId());
                    changedFieldIds.add(field.fieldId());
                    changeKinds.add("MOVE_BOX");
                }
                case FillSpotChange.RestyleBox restyle -> {
                    FieldDefinition field = fieldIn(fields, restyle.fieldId());
                    FieldBindingTarget.PageBox from = requireBox(field,
                            "This box belongs to the PDF's own form, which sets how its text looks; Brownie cannot change it.");
                    if (restyle.sizePt() == null && restyle.overflow() == null && restyle.multiline() == null) {
                        throw new FillSpotChangeInvalidException("Say what to change: the text size, what happens when the text is too long, "
                                + "or whether it can take more than one line.");
                    }
                    PdfTextStyle style = restyle.sizePt() == null
                            ? from.style()
                            : requireTextSize(new PdfTextStyle(from.style().family(), from.style().bold(), finiteSize(restyle.sizePt())));
                    FieldBindingTarget.PageBox to = pageBox(from.page(), from.box(), style,
                            restyle.multiline() == null ? from.multiline() : restyle.multiline(),
                            restyle.overflow() == null ? from.overflow() : restyle.overflow());
                    replace(fields, withBinding(field, to));
                    changeJson.add("{\"kind\":\"RESTYLE_BOX\",\"fieldId\":" + quote(field.fieldId()) + ",\"label\":"
                            + quote(field.displayLabel()) + ",\"from\":" + textJson(from) + ",\"to\":" + textJson(to) + "}");
                    changedFieldIds.add(field.fieldId());
                    changeKinds.add("RESTYLE_BOX");
                }
                default -> throw new IllegalStateException("A place in a Word form's text is not a change to a PDF form: " + change);
            }
        }
        if (fields.isEmpty()) {
            throw new FillSpotChangeInvalidException("A form needs at least one fill spot, so the last one cannot be taken away.");
        }
        if (fields.size() > MAX_SPOTS) {
            throw new FillSpotChangeInvalidException("A form can have at most " + MAX_SPOTS + " fill spots.");
        }
        requireDistinctLabels(fields, namedFieldIds);
        requirePlaceable(graph, fields, placedFieldIds);

        List<RuleRevision> rules = new ArrayList<>();
        for (RuleRevision rule : ruleRepository.findByTemplateVersion(workspaceId, userId, base.id())) {
            if (rule.status() != RuleRevisionStatus.REJECTED) {
                rewrite(rule, removedFieldIds).ifPresent(rules::add);
            }
        }
        // A PDF form has no Word structure, so the gate refuses only a rule that would need one.
        ActivationGate.requireRulesHold(fields, null, rules);

        BaselineRenderResult baseline = baselineFor(
                workspaceId, userId, base, changes, base.sourceArtifactId(), null, base.pdfFormExtractionId(), fields);
        String derivationJson = "{\"baseVersionId\":" + base.id() + ",\"parserVersion\":" + quote(graph.parserVersion())
                + ",\"changes\":[" + String.join(",", changeJson) + "]}";
        return new PreparedDerivation(
                base.templateId(), base.id(), derivationKey, derivationJson, base.sourceArtifactId(), TemplateKind.PDF, null,
                base.pdfFormExtractionId(), fields, rules, baseline, changedFieldIds, changeKinds, editReason(base, changes));
    }

    /**
     * Refuses the new field list when a box added or moved by the request
     * cannot go where it was put, with the reason and plain words. The
     * fields placed by the request are checked after all the others, so a
     * box put over another place is the one reported, not the place it
     * covers. Any other problem is refused the way activation refuses it.
     */
    private static void requirePlaceable(PdfFormGraph graph, List<FieldDefinition> fields, Set<String> placedFieldIds) {
        List<FieldDefinition> placedLast = new ArrayList<>();
        fields.stream().filter(field -> !placedFieldIds.contains(field.fieldId())).forEach(placedLast::add);
        fields.stream().filter(field -> placedFieldIds.contains(field.fieldId())).forEach(placedLast::add);
        List<UnsupportedBinding> problems = TemplateBindingValidator.validate(graph, placedLast);
        for (UnsupportedBinding problem : problems) {
            if (!placedFieldIds.contains(problem.fieldId())) {
                continue;
            }
            String label = placedLast.stream().filter(field -> field.fieldId().equals(problem.fieldId())).findFirst()
                    .map(FieldDefinition::displayLabel).orElse(problem.fieldId());
            switch (problem.reason()) {
                case OFF_PAGE -> throw new FillSpotPlacementException(FillSpotPlacementException.Reason.OFF_PAGE,
                        "The box for " + label + " is not all on the page. Move it so that all of it is on the page.");
                case TOO_SMALL -> throw new FillSpotPlacementException(FillSpotPlacementException.Reason.TOO_SMALL,
                        "The box for " + label + " is too small to write in. Make it bigger.");
                case OVERLAPS -> throw new FillSpotPlacementException(FillSpotPlacementException.Reason.OVERLAPS,
                        "The box for " + label + " would cover another fill spot. Move it, or make it smaller.");
                case NOT_FILLABLE -> throw new FillSpotPlacementException(FillSpotPlacementException.Reason.NOT_FILLABLE,
                        "Brownie cannot write on this page of the PDF. Put the box for " + label + " on another page.");
                default -> {
                    // Not about where the box is: refused below as activation refuses it.
                }
            }
        }
        ActivationGate.requireBindable(problems);
    }

    /** The field's box, or a refusal in {@code refusal}'s words for one of the PDF's own form fields. */
    private static FieldBindingTarget.PageBox requireBox(FieldDefinition field, String refusal) {
        if (field.binding() instanceof FieldBindingTarget.PageBox box) {
            return box;
        }
        throw new FillSpotChangeInvalidException(refusal);
    }

    /** A box binding, or a refusal in words for numbers that are not a place on a page. */
    private static FieldBindingTarget.PageBox pageBox(
            int page, PdfRect box, PdfTextStyle style, boolean multiline, PdfOverflowPolicy overflow) {
        try {
            return new FieldBindingTarget.PageBox(page, box.x(), box.y(), box.width(), box.height(), style, multiline, overflow);
        } catch (IllegalArgumentException e) {
            throw new FillSpotChangeInvalidException("A box needs a page number from 1, and a position and size in points.");
        }
    }

    private static double finiteSize(double sizePt) {
        if (!Double.isFinite(sizePt) || sizePt <= 0) {
            throw new FillSpotChangeInvalidException(textSizeRefusal());
        }
        return sizePt;
    }

    private static PdfTextStyle requireTextSize(PdfTextStyle style) {
        if (style.sizePt() < MIN_BOX_TEXT_SIZE || style.sizePt() > MAX_BOX_TEXT_SIZE) {
            throw new FillSpotChangeInvalidException(textSizeRefusal());
        }
        return style;
    }

    private static String textSizeRefusal() {
        return "A box's text size must be from " + number(MIN_BOX_TEXT_SIZE) + " to " + number(MAX_BOX_TEXT_SIZE) + " points.";
    }

    /**
     * The look of the words beside a new box, as a box suggested at its
     * middle would have it ({@link PdfSpotCandidateDetector#boxSuggestion}),
     * made no taller than the box holds and kept to the sizes a box may
     * have; the ordinary look on a page with no
     * words, such as a scan, or for a box not on a page at all (which the
     * check then refuses).
     */
    static PdfTextStyle styleBeside(PdfFormGraph graph, int pageNumber, PdfRect box) {
        Optional<PdfFormGraph.Page> page = graph.pages().stream().filter(candidate -> candidate.pageNumber() == pageNumber).findFirst();
        if (page.isEmpty() || !(box.width() > 0) || !(box.height() > 0) || !Double.isFinite(box.x()) || !Double.isFinite(box.y())
                || !Double.isFinite(box.width()) || !Double.isFinite(box.height())) {
            return PdfTextStyle.DEFAULT;
        }
        PdfTextStyle beside = PdfSpotCandidateDetector.boxSuggestion(graph, pageNumber, new PdfPoint(box.centerX(), box.centerY())).style();
        // Text reads upright on the page as it is shown, so a page turned a quarter shows the box's width as its height.
        double shownHeight = Math.floorMod(page.get().rotation(), 180) == 90 ? box.width() : box.height();
        double largest = Math.floor(0.8 * shownHeight * 2) / 2;
        return new PdfTextStyle(beside.family(), beside.bold(), PdfSpotCandidateDetector.boxTextSize(Math.min(beside.sizePt(), largest)));
    }

    /**
     * Puts a new box's field before the first one that comes after it on the
     * pages (by page, then top to bottom, then left to right), so the field
     * list keeps the order a person reads the form in. A form field counts
     * where it is first shown.
     */
    private static void insertInPageOrder(List<FieldDefinition> fields, FieldDefinition field, PdfFormGraph graph) {
        double[] position = pagePosition(field, graph);
        for (int i = 0; i < fields.size(); i++) {
            double[] other = pagePosition(fields.get(i), graph);
            if (position != null && other != null && java.util.Arrays.compare(other, position) > 0) {
                fields.add(i, field);
                return;
            }
        }
        fields.add(field);
    }

    private static double[] pagePosition(FieldDefinition field, PdfFormGraph graph) {
        return switch (field.binding()) {
            case FieldBindingTarget.PageBox box -> new double[] {box.page(), box.y(), box.x()};
            case FieldBindingTarget.AcroFormField(String name) -> TemplateBindingValidator.formField(graph, name)
                    .filter(formField -> !formField.widgets().isEmpty())
                    .map(formField -> formField.widgets().getFirst())
                    .map(widget -> new double[] {widget.pageNumber(), widget.box().y(), widget.box().x()})
                    .orElse(null);
            default -> null;
        };
    }

    private PdfFormGraph pdfGraphOf(long workspaceId, long userId, TemplateVersion base) {
        return pdfFormExtractionVersionRepository.findById(workspaceId, userId, base.pdfFormExtractionId())
                .map(PdfFormExtractionVersion::graph)
                .orElseThrow(() -> new IllegalStateException(
                        "PDF form reading " + base.pdfFormExtractionId() + " of template version " + base.id() + " no longer exists."));
    }

    /** A request that only renames reuses the render the base was proven with; anything else is proven again. */
    private BaselineRenderResult baselineFor(
            long workspaceId, long userId, TemplateVersion base, List<FillSpotChange> changes, long sourceArtifactId,
            Long extractionVersionId, Long pdfFormExtractionId, List<FieldDefinition> fields) {
        boolean onlyRenames = changes.stream().allMatch(change -> change instanceof FillSpotChange.Rename);
        return onlyRenames
                ? templateBaselineRenderRepository.findBaselineRender(workspaceId, userId, base.id())
                        .orElseGet(() -> renderBaseline(workspaceId, userId, base, sourceArtifactId, extractionVersionId, pdfFormExtractionId, fields))
                : renderBaseline(workspaceId, userId, base, sourceArtifactId, extractionVersionId, pdfFormExtractionId, fields);
    }

    /**
     * A base rule as it applies to the new version: dropped when it is about
     * a spot that was taken away, with that spot left out of a list of
     * required ones (dropped when none is left), otherwise unchanged. The
     * copy keeps the base rule's status, so an accepted rule stays accepted.
     */
    static Optional<RuleRevision> rewrite(RuleRevision rule, Set<String> removedFieldIds) {
        if (rule.scope() instanceof RuleScope.SingleField(String fieldId) && removedFieldIds.contains(fieldId)) {
            return Optional.empty();
        }
        RulePayload payload = switch (rule.payload()) {
            case RulePayload.RequiredFields required -> {
                List<String> kept = required.fieldIds().stream().filter(fieldId -> !removedFieldIds.contains(fieldId)).toList();
                yield kept.isEmpty() ? null : kept.size() == required.fieldIds().size() ? required : new RulePayload.RequiredFields(kept);
            }
            case RulePayload.MaxTextLength bound -> removedFieldIds.contains(bound.fieldId()) ? null : bound;
            case RulePayload.MaxItemCount bound -> removedFieldIds.contains(bound.fieldId()) ? null : bound;
            case RulePayload.DateDisplayFormat bound -> removedFieldIds.contains(bound.fieldId()) ? null : bound;
            case RulePayload.AllowedSourceKinds bound -> removedFieldIds.contains(bound.fieldId()) ? null : bound;
            case RulePayload.MissingValueBehavior bound -> removedFieldIds.contains(bound.fieldId()) ? null : bound;
            case RulePayload.AllowedOverflowBehavior bound -> removedFieldIds.contains(bound.fieldId()) ? null : bound;
            case RulePayload.RepeatableRegionEmptyBehavior bound -> removedFieldIds.contains(bound.fieldId()) ? null : bound;
            case RulePayload.AllowedSectionOrder order -> order;
            case RulePayload.ProtectedRegion region -> region;
        };
        if (payload == null) {
            return Optional.empty();
        }
        return Optional.of(new RuleRevision(
                rule.id(), rule.workspaceId(), rule.templateId(), rule.templateVersionId(), rule.category(), rule.scope(), payload,
                rule.schemaVersion(), rule.status(), rule.humanExplanation(), rule.authorUserId(), rule.createdAt()));
    }

    private static void requireRequestShape(List<FillSpotChange> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new FillSpotChangeInvalidException("Say which fill spot to add, rename or take away.");
        }
        if (changes.size() > MAX_CHANGES) {
            throw new FillSpotChangeInvalidException("Change at most " + MAX_CHANGES + " fill spots at a time.");
        }
        // A spot can be renamed, moved and restyled in one request (a person moves a box and changes its text size in one go),
        // but each only once, and a spot taken away is not changed as well.
        Set<String> named = new HashSet<>();
        Set<String> changed = new HashSet<>();
        Set<String> removed = new HashSet<>();
        for (FillSpotChange change : changes) {
            String fieldId = switch (change) {
                case FillSpotChange.Rename rename -> rename.fieldId();
                case FillSpotChange.Remove remove -> remove.fieldId();
                case FillSpotChange.MoveBox move -> move.fieldId();
                case FillSpotChange.RestyleBox restyle -> restyle.fieldId();
                case FillSpotChange.Add ignored -> null;
                case FillSpotChange.AddBox ignored -> null;
            };
            if (fieldId == null) {
                continue;
            }
            if (!named.add(change.getClass().getSimpleName() + ":" + fieldId)) {
                throw new FillSpotChangeInvalidException("Each fill spot can be changed only once in one request.");
            }
            (change instanceof FillSpotChange.Remove ? removed : changed).add(fieldId);
            if (removed.contains(fieldId) && changed.contains(fieldId)) {
                throw new FillSpotChangeInvalidException("A fill spot that is taken away cannot be changed in the same request.");
            }
        }
    }

    private static String requireLabel(String label) {
        String normalized = FieldIds.normalizeLabel(label);
        if (normalized == null) {
            throw new FillSpotChangeInvalidException(
                    "A fill spot's name is 1 to " + FieldIds.MAX_LABEL_LENGTH + " characters on one line, with at least one letter or digit.");
        }
        return normalized;
    }

    private static String labelOrAsGiven(String label) {
        String normalized = FieldIds.normalizeLabel(label);
        return normalized != null ? normalized : label.strip();
    }

    private static String currentLabel(TemplateVersion base, String fieldId) {
        return base.fieldDefinitions().stream()
                .filter(field -> field.fieldId().equals(fieldId))
                .findFirst()
                .map(FieldDefinition::displayLabel)
                .orElseGet(() -> FieldIds.labelFor(fieldId));
    }

    private static void requireKnownScalarTargets(Map<String, FieldDefinition> baseFields, List<FillSpotChange> changes) {
        for (FillSpotChange change : changes) {
            switch (change) {
                case FillSpotChange.Rename rename -> requireField(baseFields, rename.fieldId());
                case FillSpotChange.Remove remove -> {
                    FieldDefinition field = requireField(baseFields, remove.fieldId());
                    if (field.cardinality() != FieldCardinality.SCALAR) {
                        throw new FillSpotChangeInvalidException(
                                "\"" + field.displayLabel() + "\" holds a list, and a fill spot that holds a list cannot be taken away here.");
                    }
                }
                case FillSpotChange.MoveBox move -> requireField(baseFields, move.fieldId());
                case FillSpotChange.RestyleBox restyle -> requireField(baseFields, restyle.fieldId());
                case FillSpotChange.Add ignored -> {
                    // A new spot names no existing field.
                }
                case FillSpotChange.AddBox ignored -> {
                    // Nor does a new box.
                }
            }
        }
    }

    private static FieldDefinition requireField(Map<String, FieldDefinition> baseFields, String fieldId) {
        FieldDefinition field = baseFields.get(fieldId);
        if (field == null) {
            throw new FillSpotChangeInvalidException("This form has no fill spot \"" + fieldId + "\".");
        }
        return field;
    }

    /** Only a spot Brownie made in the file changes the file when it is taken away; one that came with the form keeps it as it is. */
    private static boolean editsFileToRemove(FieldDefinition field) {
        return removalEdit(field).isPresent();
    }

    private static Optional<SpotEdit> removalEdit(FieldDefinition field) {
        String tag = tagOf(field);
        if (tag == null || field.docxControl() == null) {
            return Optional.empty();
        }
        return switch (field.docxControl()) {
            case INSERTED_BY_BROWNIE -> Optional.of(new SpotEdit.Unwrap(tag));
            case TAGGED_BY_BROWNIE -> Optional.of(new SpotEdit.Untag(tag));
            case ORIGINAL -> Optional.empty();
        };
    }

    private static String tagOf(FieldDefinition field) {
        return field.binding() instanceof FieldBindingTarget.ContentControlTag(String tag) ? tag : null;
    }

    /** The field as the changes so far left it, so that one renamed and then moved keeps both. */
    private static FieldDefinition fieldIn(List<FieldDefinition> fields, String fieldId) {
        return fields.stream().filter(field -> field.fieldId().equals(fieldId)).findFirst()
                .orElseThrow(() -> new FillSpotChangeInvalidException("This form has no fill spot \"" + fieldId + "\"."));
    }

    private static FieldDefinition withBinding(FieldDefinition field, FieldBindingTarget binding) {
        return new FieldDefinition(
                field.fieldId(), field.type(), field.cardinality(), field.requiredness(), binding, field.label(), field.origin(),
                field.docxControl(), field.blankText());
    }

    private static FieldDefinition withLabel(FieldDefinition field, String label) {
        return new FieldDefinition(
                field.fieldId(), field.type(), field.cardinality(), field.requiredness(), field.binding(), label, field.origin(),
                field.docxControl(), field.blankText());
    }

    private static void replace(List<FieldDefinition> fields, FieldDefinition field) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).fieldId().equals(field.fieldId())) {
                fields.set(i, field);
                return;
            }
        }
    }

    /**
     * Two spots with one name could not be told apart in the chat or on the
     * page, so a name given in this request must differ from every other
     * spot's (ignoring case). Names the form already had are left alone.
     */
    private static void requireDistinctLabels(List<FieldDefinition> fields, List<String> changedFieldIds) {
        Map<String, List<String>> idsByName = new HashMap<>();
        for (FieldDefinition field : fields) {
            String name = Normalizer.normalize(field.displayLabel(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            idsByName.computeIfAbsent(name, key -> new ArrayList<>()).add(field.fieldId());
        }
        for (FieldDefinition field : fields) {
            if (!changedFieldIds.contains(field.fieldId())) {
                continue;
            }
            String name = Normalizer.normalize(field.displayLabel(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            if (idsByName.get(name).size() > 1) {
                throw new FillSpotChangeInvalidException(
                        "Another fill spot is already called \"" + field.displayLabel() + "\". Choose a different name.");
            }
        }
    }

    /**
     * Node IDs inside an edited paragraph move, so a rule protecting it (or
     * anything in it, or a table around it) and a field bound to a node in
     * it would silently point somewhere else afterwards.
     */
    private static void requireNothingProtectedIsEdited(Set<String> editedParagraphs, List<RuleRevision> rules, List<FieldDefinition> fields) {
        if (editedParagraphs.isEmpty()) {
            return;
        }
        List<String> pinnedNodes = new ArrayList<>();
        for (RuleRevision rule : rules) {
            if (rule.payload() instanceof RulePayload.ProtectedRegion(FieldBindingTarget.StructuralNode(DocumentPartKind part, String nodeId))
                    && part == DocumentPartKind.MAIN_DOCUMENT) {
                pinnedNodes.add(nodeId);
            }
        }
        for (FieldDefinition field : fields) {
            if (field.binding() instanceof FieldBindingTarget.StructuralNode(DocumentPartKind part, String nodeId)
                    && part == DocumentPartKind.MAIN_DOCUMENT) {
                pinnedNodes.add(nodeId);
            }
        }
        for (String paragraph : editedParagraphs) {
            for (String node : pinnedNodes) {
                if (node.equals(paragraph) || node.startsWith(paragraph + "/") || paragraph.startsWith(node + "/")) {
                    throw new FillSpotPlacementException(
                            FillSpotPlacementException.Reason.PROTECTED, "The line is part of the form that is protected from changes.");
                }
            }
        }
    }

    private PlaceCheck checkPlace(DocxAnchor anchor, String parserVersion, Places places, DocxStructuralGraph graph) {
        if (anchor.part() != DocumentPartKind.MAIN_DOCUMENT) {
            throw new FillSpotPlacementException(
                    FillSpotPlacementException.Reason.HEADER_FOOTER, "A fill spot cannot go in a header or footer.");
        }
        if (!parserVersion.equals(anchor.parserVersion())) {
            throw stale();
        }
        boolean existingControl = anchor.placement() == AnchorPlacement.EXISTING_CONTROL;
        String paragraphNodeId = anchor.paragraphNodeId() != null
                ? anchor.paragraphNodeId()
                : existingControl ? parentOf(anchor.controlNodeId()) : null;
        Places.ParagraphPlace paragraph = paragraphNodeId == null ? null : places.paragraph(paragraphNodeId);
        if (paragraph == null) {
            throw stale();
        }
        if (!paragraph.anchorable()) {
            throw paragraph.repeating()
                    ? new FillSpotPlacementException(
                            FillSpotPlacementException.Reason.REPEATING_REGION, "A fill spot cannot go in the part that repeats for each item.")
                    : new FillSpotPlacementException(FillSpotPlacementException.Reason.NOT_FOUND, "A fill spot cannot go there.");
        }
        if (anchor.anchorTextHash() != null && !anchor.anchorTextHash().equals(paragraph.anchorTextHash())) {
            throw stale();
        }
        if (existingControl) {
            if (paragraph.spotControls().contains(anchor.controlNodeId())) {
                throw new FillSpotChangeInvalidException("That place is a fill spot already.");
            }
            Integer offset = paragraph.controlOffsets().get(anchor.controlNodeId());
            if (offset == null) {
                throw stale();
            }
            return new PlaceCheck(paragraphNodeId, new Position(paragraph.ordinal(), offset), null);
        }
        if (anchor.anchorTextHash() == null) {
            throw stale();
        }
        String text = ParagraphAnchorText.of(findParagraph(graph, paragraphNodeId));
        int length = CodePoints.length(text);
        if (anchor.end() > length) {
            throw stale();
        }
        String blankText = switch (anchor.placement()) {
            case AT -> {
                if (anchor.start() != anchor.end()) {
                    throw new FillSpotChangeInvalidException("A spot added at a point starts and ends at the same place.");
                }
                yield null;
            }
            case REPLACE -> {
                if (anchor.start() == anchor.end()) {
                    throw new FillSpotChangeInvalidException("Select the words the fill spot should take the place of.");
                }
                yield usableBlank(CodePoints.substring(text, anchor.start(), anchor.end()));
            }
            case WHOLE_LINE -> isOnlyABlankLine(text) ? usableBlank(text) : null;
            case EXISTING_CONTROL -> null;
        };
        // Over a whole line that is more than a blank, the editor puts the spot at the line's end, so it reads there.
        int at = anchor.placement() == AnchorPlacement.WHOLE_LINE && !BlankLines.isOnlyABlank(text) ? length : anchor.start();
        return new PlaceCheck(paragraphNodeId, new Position(paragraph.ordinal(), at), blankText);
    }

    private static FillSpotPlacementException stale() {
        return new FillSpotPlacementException(
                FillSpotPlacementException.Reason.ANCHOR_STALE, "The form changed since the place was chosen. Choose it again.");
    }

    private static String parentOf(String controlNodeId) {
        int slash = controlNodeId.lastIndexOf('/');
        return slash <= 0 ? null : controlNodeId.substring(0, slash);
    }

    private static String usableBlank(String text) {
        return FieldIds.isValidBlankText(text) ? text : null;
    }

    /**
     * A line the editor replaces with a spot over the whole of it, and that
     * has something to print while the spot is empty (see {@link
     * BlankLines}): underscores, dots, dashes, or one bracketed prompt such
     * as "[Company]". On any other line the spot goes at the end and keeps
     * no blank of its own.
     */
    static boolean isOnlyABlankLine(String text) {
        return !text.strip().isEmpty() && BlankLines.isOnlyABlank(text);
    }

    private static StructuralNode findParagraph(DocxStructuralGraph graph, String nodeId) {
        return mainPart(graph).flatMap(part -> find(part.root(), node -> node.kind() == StructuralNodeKind.PARAGRAPH && node.nodeId().equals(nodeId)))
                .orElseThrow(TemplateDerivationService::stale);
    }

    private static String paragraphHoldingTag(DocxStructuralGraph graph, String tag) {
        return mainPart(graph)
                .flatMap(part -> find(part.root(), node -> node.kind() == StructuralNodeKind.PARAGRAPH
                        && node.children().stream().anyMatch(child ->
                                child.kind() == StructuralNodeKind.CONTENT_CONTROL && tag.equals(child.contentControlTag()))))
                .map(StructuralNode::nodeId)
                .orElseThrow(() -> new FillSpotPlacementException(
                        FillSpotPlacementException.Reason.NOT_FOUND, "The fill spot is not in the form's file any more."));
    }

    private static Optional<DocumentPart> mainPart(DocxStructuralGraph graph) {
        return graph.parts().stream().filter(part -> part.kind() == DocumentPartKind.MAIN_DOCUMENT).findFirst();
    }

    private static Optional<StructuralNode> find(StructuralNode node, java.util.function.Predicate<StructuralNode> wanted) {
        if (wanted.test(node)) {
            return Optional.of(node);
        }
        for (StructuralNode child : node.children()) {
            Optional<StructuralNode> found = find(child, wanted);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Every tag a content control carries anywhere in the file: the body, headers, footers and the rest. */
    private static Set<String> contentControlTags(DocxStructuralGraph graph) {
        Set<String> tags = new HashSet<>();
        for (DocumentPart part : graph.parts()) {
            collectTags(part.root(), tags);
        }
        return tags;
    }

    private static void collectTags(StructuralNode node, Set<String> tags) {
        if (node.kind() == StructuralNodeKind.CONTENT_CONTROL && node.contentControlTag() != null) {
            tags.add(node.contentControlTag());
        }
        for (StructuralNode child : node.children()) {
            collectTags(child, tags);
        }
    }

    /** Puts a new field before the first one whose spot comes after it on the page, so the field list keeps reading order. */
    private static void insertInReadingOrder(List<FieldDefinition> fields, FieldDefinition field, Position position, Places places) {
        for (int i = 0; i < fields.size(); i++) {
            Position other = places.positionOf(fields.get(i).fieldId());
            if (other != null && other.compareTo(position) > 0) {
                fields.add(i, field);
                return;
            }
        }
        fields.add(field);
    }

    private DocxStructuralGraph currentGraphOf(long workspaceId, long userId, long artifactId) {
        ExtractionVersion extraction = documentExtractionService.extractDocx(workspaceId, userId, artifactId);
        if (extraction.status() != ExtractionStatus.COMPLETE) {
            throw new FillSpotChangeInvalidException("This form's file can no longer be read, so its fill spots cannot be changed.");
        }
        return extraction.graph();
    }

    private DocxStructuralGraph pinnedGraphOf(long workspaceId, long userId, TemplateVersion base) {
        return extractionVersionRepository.findById(workspaceId, userId, base.extractionVersionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Extraction version " + base.extractionVersionId() + " of template version " + base.id() + " no longer exists."))
                .graph();
    }

    /**
     * Edits a copy of the base's file and stores it the way any file Brownie
     * makes is stored (inspected and scanned), then reads it back: the new
     * version is pinned to that reading, which must be complete.
     */
    private ExtractionVersion storeEditedFile(long workspaceId, long userId, TemplateVersion base, List<SpotEdit> edits) {
        String filename;
        byte[] bytes;
        try (ReadableArtifact readable = artifactService.openContent(workspaceId, userId, base.sourceArtifactId())) {
            filename = readable.artifact().displayFilename();
            bytes = readable.content().readAllBytes();
        } catch (IOException e) {
            throw new ArtifactStorageException("Failed to read the file of template version " + base.id() + ".", e);
        }
        EditedDocx edited = fillSpotEditor.apply(bytes, edits);
        Artifact stored = artifactService.storeGenerated(
                workspaceId, userId, filename == null || filename.isBlank() ? "form.docx" : filename, edited.docxBytes());
        ExtractionVersion extraction = documentExtractionService.extractDocx(workspaceId, userId, stored.id());
        if (extraction.status() != ExtractionStatus.COMPLETE) {
            throw new FillSpotChangeInvalidException("The form could not be read back after the change, so the change was not made.");
        }
        return extraction;
    }

    private BaselineRenderResult renderBaseline(
            long workspaceId, long userId, TemplateVersion base, long sourceArtifactId, Long extractionVersionId, Long pdfFormExtractionId,
            List<FieldDefinition> fields) {
        TemplateVersion candidate = new TemplateVersion(
                0L, workspaceId, base.templateId(), base.versionNumber() + 1, sourceArtifactId, base.kind(), extractionVersionId,
                pdfFormExtractionId, TemplateVersionStatus.ACTIVATED, fields, null, null, base.id());
        BaselineRenderResult baseline = templateBaselineRenderer.renderBaseline(workspaceId, userId, candidate);
        if (!baseline.passed()) {
            throw new FillSpotBaselineFailedException(baseline.failedFieldIds());
        }
        return baseline;
    }

    private static String anchorJson(DocxAnchor anchor) {
        return "{\"part\":" + quote(anchor.part().name())
                + ",\"paragraphNodeId\":" + quote(anchor.paragraphNodeId())
                + ",\"placement\":" + quote(anchor.placement().name())
                + ",\"start\":" + anchor.start()
                + ",\"end\":" + anchor.end()
                + ",\"anchorTextHash\":" + quote(anchor.anchorTextHash())
                + ",\"parserVersion\":" + quote(anchor.parserVersion())
                + ",\"controlNodeId\":" + quote(anchor.controlNodeId()) + "}";
    }

    private static String boxJson(PdfRect box) {
        return "{\"x\":" + number(box.x()) + ",\"y\":" + number(box.y()) + ",\"width\":" + number(box.width())
                + ",\"height\":" + number(box.height()) + "}";
    }

    private static String styleJson(PdfTextStyle style) {
        return "{\"font\":" + quote(style.family().name()) + ",\"bold\":" + style.bold() + ",\"sizePt\":" + number(style.sizePt()) + "}";
    }

    /** How a box shows its text, as stored with a restyle: its size, what happens when it is too long, and whether it wraps. */
    private static String textJson(FieldBindingTarget.PageBox box) {
        return "{\"sizePt\":" + number(box.style().sizePt()) + ",\"overflow\":" + quote(box.overflow().name())
                + ",\"multiline\":" + box.multiline() + "}";
    }

    /**
     * A number as JSON writes it, the same text for the same value every
     * time: a whole number without a fraction, anything else as Java prints
     * a double. A value that is not a number is written as a string, so the
     * key still tells requests apart and the check refuses the value later.
     */
    static String number(double value) {
        if (!Double.isFinite(value)) {
            return quote(Double.toString(value));
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** A JSON string literal, or {@code null}; every character outside printable ASCII is escaped, so the text is the same bytes everywhere. */
    static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                quoted.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7e) {
                quoted.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available.", e);
        }
    }

    /** Where a spot is in reading order: which paragraph of the body, and how far into its anchor text. */
    private record Position(int paragraph, int offset) implements Comparable<Position> {

        @Override
        public int compareTo(Position other) {
            int byParagraph = Integer.compare(paragraph, other.paragraph);
            return byParagraph != 0 ? byParagraph : Integer.compare(offset, other.offset);
        }
    }

    private record PlaceCheck(String paragraphNodeId, Position position, String blankText) {
    }

    /**
     * The base's body as the page shows it: which paragraphs can take a spot,
     * the hash each one's place is checked against, and where every existing
     * spot sits in reading order.
     */
    private static final class Places {

        record ParagraphPlace(
                int ordinal, boolean anchorable, boolean repeating, String anchorTextHash, Map<String, Integer> controlOffsets,
                Set<String> spotControls) {
        }

        private final Map<String, ParagraphPlace> paragraphs = new HashMap<>();
        private final Map<String, Position> spots = new HashMap<>();
        private int ordinal;

        static Places of(TemplateLayout layout) {
            Places places = new Places();
            layout.parts().stream()
                    .filter(part -> part.kind() == DocumentPartKind.MAIN_DOCUMENT)
                    .findFirst()
                    .ifPresent(main -> places.walk(main.blocks(), false));
            return places;
        }

        private void walk(List<TemplateLayout.Block> blocks, boolean inRepeatingRow) {
            for (TemplateLayout.Block block : blocks) {
                switch (block) {
                    case TemplateLayout.Paragraph paragraph -> visit(paragraph, inRepeatingRow);
                    case TemplateLayout.Table table -> table.rows().forEach(row ->
                            row.cells().forEach(cell -> walk(cell.blocks(), row.repeating())));
                }
            }
        }

        private void visit(TemplateLayout.Paragraph paragraph, boolean inRepeatingRow) {
            int number = ordinal++;
            int offset = 0;
            Map<String, Integer> controlOffsets = new HashMap<>();
            Set<String> spotControls = new HashSet<>();
            for (TemplateLayout.Inline inline : paragraph.inlines()) {
                switch (inline) {
                    case TemplateLayout.Text text -> {
                        if (text.anchorStart() != null) {
                            offset = text.anchorStart() + CodePoints.length(text.text());
                        } else if (text.controlNodeId() != null) {
                            controlOffsets.putIfAbsent(text.controlNodeId(), offset);
                        }
                    }
                    case TemplateLayout.FillSpot spot -> {
                        spots.putIfAbsent(spot.fieldId(), new Position(number, offset));
                        spotControls.add(spot.nodeId());
                    }
                    case TemplateLayout.Image ignored -> {
                        // A picture takes no room in the anchor text.
                    }
                }
            }
            paragraphs.put(paragraph.nodeId(), new ParagraphPlace(
                    number, paragraph.anchorable(), paragraph.repeating() || inRepeatingRow, paragraph.anchorTextHash(),
                    controlOffsets, spotControls));
        }

        ParagraphPlace paragraph(String nodeId) {
            return paragraphs.get(nodeId);
        }

        Position positionOf(String fieldId) {
            return spots.get(fieldId);
        }

        void remember(String fieldId, Position position) {
            spots.put(fieldId, position);
        }
    }
}
