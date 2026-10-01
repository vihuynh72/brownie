package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactContentInspector;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStorageException;
import io.github.vihuynh72.brownie.core.artifact.ContentInspection;
import io.github.vihuynh72.brownie.core.artifact.PackagePolicy;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.artifact.UnsupportedArtifactTypeException;
import io.github.vihuynh72.brownie.core.artifact.WordRoute;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.DocxFeatureFinding;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Opens a person's upload of any word-processing type as a fillable
 * document: one request that ends with a clean Word working copy, stored
 * and read, whose places to fill are named content controls a template can
 * bind as they are. The original upload is never changed.
 *
 * <p>In order, and with no transaction held open across any of it (each
 * step that stores something is its own short transaction): an earlier
 * answer for the same upload and recipe is looked up first and returned as
 * it is; otherwise the upload is read, converted to Word in the sandbox
 * when it is another format (and the converter's output inspected like an
 * upload), made into a clean working copy, read for its outline, searched
 * for places ({@link FillSpotCandidateFinder}), named ({@link
 * RulesSpotNamer}, then the active {@link SpotNamer}), planned, edited
 * ({@link FillSpotEditor}) and checked ({@link FillableCopyVerifier}),
 * stored as a new artifact, extracted (so a template draft finds a
 * complete extraction) and recorded as a derivation of the upload.
 *
 * <p>A spot that fails the check is left out and the edit made once more
 * without it, with a {@link PreparationNotice#SPOTS_SKIPPED} line; a
 * repeating row that will not fill is filled once instead. A copy that
 * still fails is refused rather than handed on.
 *
 * <p>The recipe version names the reader version, so a newer reader makes
 * a new copy rather than answering with one read another way. A PDF goes
 * to {@link PdfFormPreparer}.
 */
public class FillableFormService {

    /** The recipe's own name; its version is this and the reader's version. */
    public static final String RECIPE = "fillable-form-v1";

    private static final Logger log = LoggerFactory.getLogger(FillableFormService.class);
    private static final String DEFAULT_NAME = "form";

    private final ArtifactService artifactService;
    private final DocumentExtractionService documentExtractionService;
    private final DocxStructuralExtractor docxExtractor;
    private final DocumentConverter documentConverter;
    private final WorkingCopyPreparer workingCopyPreparer;
    private final WordForms wordForms;
    private final SpotNamer spotNamer;
    private final FillSpotEditor fillSpotEditor;
    private final FillableCopyVerifier verifier;
    private final ArtifactDerivationRepository derivations;
    private final PdfFormPreparer pdfFormPreparer;

    public FillableFormService(
            ArtifactService artifactService,
            DocumentExtractionService documentExtractionService,
            DocxStructuralExtractor docxExtractor,
            DocumentConverter documentConverter,
            WorkingCopyPreparer workingCopyPreparer,
            WordForms wordForms,
            SpotNamer spotNamer,
            FillSpotEditor fillSpotEditor,
            FillableCopyVerifier verifier,
            ArtifactDerivationRepository derivations,
            PdfFormPreparer pdfFormPreparer) {
        this.artifactService = artifactService;
        this.documentExtractionService = documentExtractionService;
        this.docxExtractor = docxExtractor;
        this.documentConverter = documentConverter;
        this.workingCopyPreparer = workingCopyPreparer;
        this.wordForms = wordForms;
        this.spotNamer = spotNamer;
        this.fillSpotEditor = fillSpotEditor;
        this.verifier = verifier;
        this.derivations = derivations;
        this.pdfFormPreparer = pdfFormPreparer;
    }

    /** The recipe version stored with every copy this code makes. */
    public String recipeVersion() {
        return RECIPE + "/" + docxExtractor.parserVersion();
    }

    /**
     * The upload made ready to fill, made now or found from before. Refuses
     * an artifact that is missing ({@code ArtifactNotFoundException}), not
     * READY ({@code ArtifactStateConflictException}), neither a
     * word-processing document nor a PDF ({@link NotAFillableFormException}),
     * of a format this server does not convert
     * ({@link ConversionFormatDisabledException}), or one no copy could be
     * made of ({@link FillableFormFailedException}); and passes on a busy or
     * missing converter.
     */
    public FillableForm prepare(long workspaceId, long userId, long artifactId) {
        Artifact source = sourceOf(workspaceId, userId, artifactId);
        SupportedMediaType mediaType = source.detectedMediaType();
        if (mediaType == SupportedMediaType.PDF) {
            return pdfFormPreparer.prepare(workspaceId, userId, artifactId);
        }
        if (mediaType == null || mediaType.wordRoute() == WordRoute.NONE) {
            throw new NotAFillableFormException(NotAFillableFormException.Code.NOT_A_WORD_PROCESSING_DOCUMENT, artifactId);
        }
        Optional<ArtifactDerivation> existing =
                derivations.find(workspaceId, userId, artifactId, ArtifactDerivation.PREPARED, recipeVersion());
        if (existing.isPresent()) {
            return formOf(workspaceId, userId, existing.get(), false);
        }
        return make(workspaceId, userId, source);
    }

    /**
     * The answer an earlier {@link #prepare} stored for this upload, if there
     * is one: a Word upload's copy, or what {@link PdfFormPreparer} kept for
     * a PDF.
     */
    public Optional<FillableForm> find(long workspaceId, long userId, long artifactId) {
        Optional<FillableForm> word = derivations.find(workspaceId, userId, artifactId, ArtifactDerivation.PREPARED, recipeVersion())
                .map(derivation -> formOf(workspaceId, userId, derivation, false));
        return word.isPresent() ? word : pdfFormPreparer.find(workspaceId, userId, artifactId);
    }

    private FillableForm make(long workspaceId, long userId, Artifact source) {
        long artifactId = source.id();
        byte[] original = readAll(workspaceId, userId, artifactId);
        List<PreparationNotice> notices = new ArrayList<>();

        byte[] docx = original;
        PreparationMode mode = PreparationMode.UPLOAD;
        String converter = null;
        if (source.detectedMediaType().wordRoute() == WordRoute.CONVERT) {
            ContentInspection inspection = artifactService.inspectContent(workspaceId, userId, artifactId);
            ConvertedDocument converted = convert(original, inspection.convertibleFormat());
            docx = converted.docxBytes();
            requireWordDocument(docx);
            mode = PreparationMode.CONVERTER_OUTPUT;
            converter = converted.converterVersion();
            notices.add(new PreparationNotice(PreparationNotice.CONVERTED, 1, inspection.convertibleFormat().name()));
        }

        PreparedCopy prepared = workingCopy(docx, mode);
        notices.addAll(prepared.notices());
        WordForms.ReadForm form = read(prepared.docxBytes());
        FoundSpots found = FillSpotCandidateFinder.find(form.outline());
        notices.addAll(found.notices());

        SpotNamingInput input = RulesSpotNamer.namingInput(found);
        SpotNaming naming = input.candidates().isEmpty()
                ? new SpotNaming(List.of(), null, NamingSource.RULES, SpotNaming.NO_CANDIDATES, List.of())
                : spotNamer.name(workspaceId, userId, input);
        SpotPlan plan = RulesSpotNamer.plan(found, naming, controlTags(form.outline()));
        Made made = carryOut(form.docxBytes(), plan);
        notices.addAll(made.plan().notices());
        int spotCount = made.plan().spots().size();
        notices.add(spotCount == 0
                ? new PreparationNotice(PreparationNotice.NO_SPOTS_FOUND, 0, null)
                : new PreparationNotice(PreparationNotice.SPOTS_FOUND, spotCount, null));

        Artifact output = artifactService.storeGenerated(workspaceId, userId, workingCopyName(source), made.docxBytes());
        ExtractionVersion extraction = documentExtractionService.extractDocx(workspaceId, userId, output.id());
        if (extraction.status() != ExtractionStatus.COMPLETE) {
            throw new FillableFormFailedException(FillableFormFailedException.Reason.DAMAGED,
                    "The working copy of artifact " + artifactId + " did not read as a complete document ("
                            + extraction.status() + ").", null);
        }
        List<FillableForm.Spot> spots = made.plan().spots().stream()
                .map(spot -> new FillableForm.Spot(spot.field(), spot.namedBy(), spot.requiredHint(), spot.suggestedType(),
                        spot.kind().name()))
                .toList();
        NamingSource spotNaming = spots.stream().anyMatch(spot -> spot.namedBy() == NamingSource.MODEL)
                ? NamingSource.MODEL
                : NamingSource.RULES;
        String rulesOnlyReason = spotNaming == NamingSource.MODEL ? null
                : naming.rulesOnlyReason() != null ? naming.rulesOnlyReason() : SpotNaming.NO_CANDIDATES;
        ArtifactDerivation derivation = derivations.insertOrGet(workspaceId, userId, artifactId, output.id(),
                ArtifactDerivation.PREPARED, recipeVersion(), source.detectedMediaType().name(), converter, spotNaming,
                rulesOnlyReason, spots, notices);
        return formOf(workspaceId, userId, derivation, derivation.outputArtifactId() == output.id());
    }

    private record Made(byte[] docxBytes, SpotPlan plan) {
    }

    /**
     * Makes the plan's spots and checks the result. A failing spot is left
     * out and the whole edit made once more from the same file; a copy
     * that fails as a whole is made again with only the form's own
     * controls; a repeating row that will not fill is filled once instead.
     * Each attempt starts from the file as read, so rows taken out for a
     * repeating row are back whenever the plan it ends with repeats none.
     */
    private Made carryOut(byte[] read, SpotPlan plan) {
        Attempt first = attempt(read, plan);
        if (first.check().ok()) {
            return new Made(first.docxBytes(), first.plan());
        }
        log.info("Some found spots failed their check and are left out: {}", first.check().problems());
        SpotPlan retry = first.check().copyFailed()
                ? first.plan().without(brownieMade(first.plan()))
                : first.plan().without(first.check().failedFieldIds());
        Attempt second = attempt(read, retry);
        if (second.check().ok()) {
            return new Made(second.docxBytes(), second.plan());
        }
        throw new FillableFormFailedException(FillableFormFailedException.Reason.DAMAGED,
                "The working copy could not be given checked spots: " + second.check().problems(), null);
    }

    private record Attempt(byte[] docxBytes, SpotPlan plan, FillableCopyCheck check) {
    }

    private Attempt attempt(byte[] read, SpotPlan plan) {
        byte[] base = plan.removedRowNodeIds().isEmpty() ? read : wordForms.withoutRows(read, plan.removedRowNodeIds());
        byte[] edited;
        try {
            edited = fillSpotEditor.apply(base, plan.edits()).docxBytes();
        } catch (FillSpotPlacementException e) {
            Set<String> refused = refusedAlone(base, plan);
            return new Attempt(base, plan, new FillableCopyCheck(
                    refused.isEmpty(), refused, false, List.of("placements refused: " + e.reason() + " " + refused)));
        } catch (IllegalArgumentException e) {
            // Places that together cannot be made (two that overlap) fail the copy as a whole, so only the form's own are made.
            return new Attempt(base, plan, new FillableCopyCheck(true, Set.of(), false, List.of("edits refused: " + e.getMessage())));
        }
        FillableCopyCheck check = verifier.check(base, edited, plan.fields());
        if (!check.copyFailed() && check.failedFieldIds().isEmpty() && check.repeatedGroupFailed() && plan.hasRepeatingRow()) {
            return attempt(read, plan.withoutRepeatingRow());
        }
        return new Attempt(edited, plan, check);
    }

    /** When the edits together are refused, the fields whose edit the editor refuses on its own. */
    private Set<String> refusedAlone(byte[] base, SpotPlan plan) {
        Set<String> refused = new HashSet<>();
        for (SpotPlan.PlannedSpot spot : plan.spots()) {
            if (spot.edit() == null) {
                continue;
            }
            try {
                fillSpotEditor.apply(base, List.of(spot.edit()));
            } catch (FillSpotPlacementException | IllegalArgumentException alone) {
                refused.add(spot.field().fieldId());
            }
        }
        return refused;
    }

    private static Set<String> brownieMade(SpotPlan plan) {
        Set<String> made = new HashSet<>();
        for (SpotPlan.PlannedSpot spot : plan.spots()) {
            if (spot.field().effectiveOrigin() == SpotOrigin.FOUND_BY_BROWNIE) {
                made.add(spot.field().fieldId());
            }
        }
        return made;
    }

    private FillableForm formOf(long workspaceId, long userId, ArtifactDerivation derivation, boolean created) {
        ExtractionVersion extraction = documentExtractionService.extractDocx(workspaceId, userId, derivation.outputArtifactId());
        Map<String, Integer> kept = new LinkedHashMap<>();
        for (DocxFeatureFinding finding : extraction.featureReport().keptAsIs()) {
            kept.merge(finding.feature().name(), 1, Integer::sum);
        }
        List<FillableForm.KeptFeature> keptAsIs = kept.entrySet().stream()
                .map(entry -> new FillableForm.KeptFeature(entry.getKey(), entry.getValue()))
                .toList();
        return new FillableForm(
                FillableForm.Kind.DOCX,
                derivation.sourceArtifactId(),
                derivation.outputArtifactId(),
                SupportedMediaType.valueOf(derivation.sourceFormat()),
                derivation.converter() != null,
                new FillableForm.Extraction(extraction.id(), extraction.status().name(), extraction.parserVersion(), keptAsIs),
                derivation.spots(),
                derivation.notices(),
                derivation.spotNaming(),
                derivation.rulesOnlyReason(),
                created);
    }

    private Artifact sourceOf(long workspaceId, long userId, long artifactId) {
        try (ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId)) {
            return readable.artifact();
        } catch (IOException e) {
            throw new ArtifactStorageException("Failed to close stored content for artifact " + artifactId + ".", e);
        }
    }

    private byte[] readAll(long workspaceId, long userId, long artifactId) {
        try (ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId)) {
            return readable.content().readAllBytes();
        } catch (IOException e) {
            throw new ArtifactStorageException("Failed to read stored content for artifact " + artifactId + ".", e);
        }
    }

    private ConvertedDocument convert(byte[] original, ConvertibleFormat format) {
        try {
            return documentConverter.convertToDocx(original, format);
        } catch (DocumentConversionException e) {
            FillableFormFailedException.Reason reason = switch (e.reason()) {
                case CANNOT_OPEN -> FillableFormFailedException.Reason.CANNOT_OPEN;
                case DAMAGED -> FillableFormFailedException.Reason.DAMAGED;
                case TIMED_OUT -> FillableFormFailedException.Reason.TIMED_OUT;
            };
            throw new FillableFormFailedException(reason, "The file could not be converted: " + e.getMessage(), e);
        }
    }

    /** The converter's output is inspected like an upload before anything else reads it. */
    private static void requireWordDocument(byte[] docx) {
        try {
            SupportedMediaType type = ArtifactContentInspector.inspect(docx, PackagePolicy.CONVERTER_OUTPUT).mediaType();
            if (type != SupportedMediaType.DOCX) {
                throw new FillableFormFailedException(FillableFormFailedException.Reason.DAMAGED,
                        "The converter wrote " + type + ", not a Word document.", null);
            }
        } catch (IOException | UnsupportedArtifactTypeException e) {
            throw new FillableFormFailedException(FillableFormFailedException.Reason.DAMAGED,
                    "The converter's output is not a Word document this server accepts.", e);
        }
    }

    private PreparedCopy workingCopy(byte[] docx, PreparationMode mode) {
        try {
            return workingCopyPreparer.prepare(docx, mode);
        } catch (WorkingCopyPreparationException e) {
            throw new FillableFormFailedException(FillableFormFailedException.Reason.DAMAGED,
                    "No clean working copy could be made: " + e.getMessage(), e);
        }
    }

    private WordForms.ReadForm read(byte[] docx) {
        try {
            return wordForms.read(docx);
        } catch (RuntimeException e) {
            throw new FillableFormFailedException(FillableFormFailedException.Reason.DAMAGED,
                    "The working copy could not be read for its places to fill.", e);
        }
    }

    /**
     * Every tag the form's own controls carry, spots or not (a checkbox, one
     * in a header or a text box). A new spot's tag is its id, so no id is
     * made that a control already uses: the tag would name two controls.
     */
    private static Set<String> controlTags(FormOutline outline) {
        Set<String> tags = new HashSet<>();
        for (FormOutline.Paragraph paragraph : outline.paragraphs()) {
            for (FormOutline.Atom atom : paragraph.atoms()) {
                if (atom instanceof FormOutline.Control control && control.tag() != null && !control.tag().isBlank()) {
                    tags.add(control.tag());
                }
            }
        }
        return tags;
    }

    /** The source's name with a Word extension: "membership.odt" gives "membership.docx". */
    static String workingCopyName(Artifact source) {
        String name = source.displayFilename();
        if (name == null || name.isBlank()) {
            return DEFAULT_NAME + ".docx";
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return (base.isBlank() ? DEFAULT_NAME : base) + ".docx";
    }
}
