package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;

import java.util.List;
import java.util.Optional;

/**
 * The upload step's answer for a PDF, made by {@link
 * PdfFormPreparationService}. A PDF is filled as it is, so there is no
 * working copy: the template is built on the upload itself, pinned to the
 * form reading kept for it, and every place found is a spot bound to one of
 * the form's own fields or to a box on a page, ready to send back as a
 * template field. Nothing is converted and nothing is kept as it is, since
 * the file is never changed.
 *
 * <p>The answer is kept, as the Word path keeps its copy's: a {@link
 * ArtifactDerivation#PDF_FORM} record of the PDF to itself, one per file
 * and recipe version (this recipe and the reader's version). Asking again
 * answers with the same spots and notices, so naming the places, which may
 * be a paid call to the model, happens once per file, and a client that
 * retries gets the field ids it may already have built on. Two first
 * requests racing each name the places; the one recorded first stands, and
 * both answer with it. {@code created} is true when no answer was kept
 * when this request began. A PDF the reader refused is refused again every
 * time, with the same reason ({@link
 * io.github.vihuynh72.brownie.core.document.UnusablePdfFormException}), and
 * no answer is kept for it.
 */
public class PdfFillableForms implements PdfFormPreparer {

    /** The recipe's own name; its version is this and the reader's version. */
    public static final String RECIPE = "fillable-pdf-v1";

    private final PdfFormPreparationService preparation;
    private final PdfFormExtractionVersionRepository readings;
    private final ArtifactDerivationRepository derivations;
    private final String readerVersion;

    /** {@code readerVersion} is the PDF form reader's own, the version its readings are kept under. */
    public PdfFillableForms(
            PdfFormPreparationService preparation,
            PdfFormExtractionVersionRepository readings,
            ArtifactDerivationRepository derivations,
            String readerVersion) {
        this.preparation = preparation;
        this.readings = readings;
        this.derivations = derivations;
        this.readerVersion = readerVersion;
    }

    /** The recipe version every answer this code keeps is stored under. */
    public String recipeVersion() {
        return RECIPE + "/" + readerVersion;
    }

    @Override
    public FillableForm prepare(long workspaceId, long userId, long artifactId) {
        Optional<FillableForm> kept = find(workspaceId, userId, artifactId);
        if (kept.isPresent()) {
            return kept.get();
        }
        PdfFormPreparation prepared = preparation.prepare(workspaceId, userId, artifactId);
        List<FillableForm.Spot> spots = prepared.spots().stream()
                .map(spot -> new FillableForm.Spot(spot.definition(), spot.namedBy(), spot.requiredHint(), null, null))
                .toList();
        ArtifactDerivation derivation = derivations.insertOrGet(workspaceId, userId, artifactId, artifactId, ArtifactDerivation.PDF_FORM,
                recipeVersion(), SupportedMediaType.PDF.name(), null, prepared.spotNaming(), prepared.rulesOnlyReason(), spots,
                prepared.notices());
        return formOf(derivation, prepared.formExtractionId(), true);
    }

    @Override
    public Optional<FillableForm> find(long workspaceId, long userId, long artifactId) {
        Optional<ArtifactDerivation> derivation =
                derivations.find(workspaceId, userId, artifactId, ArtifactDerivation.PDF_FORM, recipeVersion());
        if (derivation.isEmpty()) {
            return Optional.empty();
        }
        // An answer is kept only after a complete reading, and the two go together when the file does. Only the
        // reading's id is needed, not the reading, which can run to tens of megabytes.
        return readings.findCompleteIdByArtifact(workspaceId, userId, artifactId, readerVersion)
                .map(readingId -> formOf(derivation.get(), readingId, false));
    }

    private FillableForm formOf(ArtifactDerivation derivation, long readingId, boolean created) {
        return new FillableForm(
                FillableForm.Kind.PDF,
                derivation.sourceArtifactId(),
                derivation.sourceArtifactId(),
                SupportedMediaType.PDF,
                false,
                new FillableForm.Extraction(readingId, ExtractionStatus.COMPLETE.name(), readerVersion, List.of()),
                derivation.spots(),
                derivation.notices(),
                derivation.spotNaming(),
                derivation.rulesOnlyReason(),
                created);
    }
}
