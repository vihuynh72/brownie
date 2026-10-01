package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactRepository;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.artifact.UploadResult;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.NotPdfArtifactException;
import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfFormReader;
import io.github.vihuynh72.brownie.core.document.PdfFormReading;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfFormReason;
import io.github.vihuynh72.brownie.core.document.UnusablePdfFormException;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Preparing a PDF as a form, against a form reading written out by hand and
 * fakes for storage and naming: which places become fields and how they are
 * bound and named, what the person is told, that a refused file stays
 * refused, that a file is read once, and the upload step's answer made from
 * it ({@link PdfFillableForms}).
 */
class PdfFormPreparationServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long ARTIFACT_ID = 42L;

    @Test
    void theFormsOwnTextFieldsBecomeOptionalSpotsBoundByNameAndTheRestAreCounted() {
        Harness harness = new Harness(new PdfFormReading.Supported(fillableForm()));

        PdfFormPreparation prepared = harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        List<FieldDefinition> fields = prepared.spots().stream().map(PreparedPdfSpot::definition).toList();
        assertEquals(List.of("Company name", "Email address", "Phone", "Date of birth"),
                fields.stream().map(FieldDefinition::label).toList());
        assertEquals(List.of("company.name", "email.address", "phone", "date.of.birth"),
                fields.stream().map(FieldDefinition::fieldId).toList());
        assertEquals(new FieldBindingTarget.AcroFormField("applicant.txtCompanyName"), fields.get(0).binding());
        assertTrue(fields.stream().allMatch(field -> field.requiredness() == FieldRequiredness.OPTIONAL));
        assertTrue(fields.stream().allMatch(field -> field.effectiveOrigin() == SpotOrigin.FORM && field.docxControl() == null));
        assertEquals(List.of(false, true, false, false), prepared.spots().stream().map(PreparedPdfSpot::requiredHint).toList());
        assertEquals(FieldType.DATE, fields.get(3).type());
        assertEquals(FieldType.TEXT, fields.get(1).type());
        assertTrue(prepared.notices().contains(new PreparationNotice(PreparationNotice.SPOTS_FOUND, 4, null)));
        assertTrue(prepared.notices().contains(new PreparationNotice(PreparationNotice.PDF_FIELDS_LEFT, 2, null)));
        assertEquals(NamingSource.RULES, prepared.spotNaming());
        assertEquals(SpotNaming.DISABLED, prepared.rulesOnlyReason());
        assertEquals(harness.readings.lastSavedId, prepared.formExtractionId());
    }

    @Test
    void blanksOnAFlatPageBecomeBoxesInTheStyleBesideThemAndSignatureLinesAreLeft() {
        Harness harness = new Harness(new PdfFormReading.Supported(flatForm()));

        PdfFormPreparation prepared = harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        assertEquals(1, prepared.spots().size());
        FieldDefinition field = prepared.spots().get(0).definition();
        assertEquals("Full name", field.label());
        assertEquals("full.name", field.fieldId());
        assertEquals(SpotOrigin.FOUND_BY_BROWNIE, field.origin());
        FieldBindingTarget.PageBox box = (FieldBindingTarget.PageBox) field.binding();
        assertEquals(1, box.page());
        assertEquals(PdfOverflowPolicy.SHRINK_TO_FIT, box.overflow());
        assertEquals(PdfFontFamily.SERIF, box.style().family());
        assertTrue(box.x() >= 125 && box.width() > 100, "the box covers the blank: " + box);
        assertTrue(prepared.notices().contains(new PreparationNotice(PreparationNotice.SIGNATURE_LINES_LEFT, 1, null)));
    }

    @Test
    void theNamingStepIsToldARunOfUnderscoresIsSureButASignatureLineIsNot() {
        Harness harness = new Harness(new PdfFormReading.Supported(flatForm()));

        harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        List<NamingCandidate> offered = harness.namer.inputs.getFirst().candidates();
        assertEquals(List.of(true, false), offered.stream().map(NamingCandidate::sure).toList());
    }

    @Test
    void theNamingStepIsToldTheFormsOwnFieldsAreSureSoItCannotLeaveOneOut() {
        Harness harness = new Harness(new PdfFormReading.Supported(fillableForm()));

        harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        List<NamingCandidate> offered = harness.namer.inputs.getFirst().candidates();
        assertEquals(4, offered.size());
        assertTrue(offered.stream().allMatch(NamingCandidate::sure), "a person cannot draw a box over a field the form made");
    }

    @Test
    void aBoxKeptForTheOfficeIsNotKeptWithTheRestOfItsGrid() {
        PdfFormPreparationService.Found found = PdfFormPreparationService.candidates(labelledBoxes("Name:", "Phone:", "Office use:"));

        List<NamingCandidate> offered = found.candidates().stream().map(PdfFormPreparationService.Candidate::naming).toList();
        assertEquals(List.of("Name", "Phone", "Office use"), offered.stream().map(NamingCandidate::rulesLabel).toList());
        assertEquals(java.util.Arrays.asList("P1G1", "P1G1", null), offered.stream().map(NamingCandidate::groupKey).toList());
        assertTrue(offered.stream().noneMatch(NamingCandidate::sure));
    }

    @Test
    void aPlaceTheNamingStepSaysIsNoBlankIsLeftOutAndCounted() {
        Harness harness = new Harness(new PdfFormReading.Supported(flatForm()));
        harness.namer.rename = candidate -> candidate.signatureLike()
                ? null
                : new NamedSpot(candidate.id(), false, candidate.rulesLabel(), FieldType.TEXT, "TEXT", false, true);

        PdfFormPreparation prepared = harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        assertEquals(List.of(), prepared.spots());
        assertEquals(List.of(PreparationNotice.NO_SPOTS_FOUND, PreparationNotice.SIGNATURE_LINES_LEFT, PreparationNotice.PLACES_LEFT_OUT),
                prepared.notices().stream().map(PreparationNotice::code).toList());
        assertTrue(prepared.notices().contains(new PreparationNotice(PreparationNotice.PLACES_LEFT_OUT, 1, null)),
                "the name line, never quietly");
    }

    @Test
    void aPlaceATemplateCouldNotTakeIsLeftOutAndCounted() {
        // Underscores printed against the very top of the page: a box standing on them would reach above it.
        PdfFormGraph.Line top = line(0, 0,
                word("Name:", 72, 0, 30, "Times-Roman", 12),
                word("____________________________", 130, 0, 180, "Times-Roman", 12));
        PdfFormGraph flat = flatForm();
        List<PdfFormGraph.Line> lines = new ArrayList<>(flat.pages().get(0).lines().stream()
                .map(line -> new PdfFormGraph.Line(line.index() + 1, line.text(), line.box(), line.words())).toList());
        lines.add(0, top);
        PdfFormGraph.Page page = new PdfFormGraph.Page(1, crop(), 0, 1, true, lines, List.of(), List.of(), List.of());
        Harness harness = new Harness(new PdfFormReading.Supported(graph(List.of(page), List.of())));

        PdfFormPreparation prepared = harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        assertEquals(List.of("Full name"), prepared.spots().stream().map(spot -> spot.definition().label()).toList());
        assertTrue(prepared.notices().contains(new PreparationNotice(PreparationNotice.SPOTS_SKIPPED, 1, null)), prepared.notices().toString());
    }

    @Test
    void aFormWithFieldsToTypeInGetsNoBoxesGuessedOnItsPrintedBlanks() {
        Harness harness = new Harness(new PdfFormReading.Supported(fieldsAndPrintedBlanks()));

        PdfFormPreparation prepared = harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        List<FieldDefinition> fields = prepared.spots().stream().map(PreparedPdfSpot::definition).toList();
        assertEquals(List.of("Company name"), fields.stream().map(FieldDefinition::label).toList());
        assertTrue(fields.stream().allMatch(field -> field.binding() instanceof FieldBindingTarget.AcroFormField));
        assertEquals(List.of("f1"), harness.namer.inputs.get(0).candidates().stream().map(NamingCandidate::id).toList(),
                "only the form's own field is offered for naming");
        assertFalse(prepared.notices().stream().anyMatch(notice -> notice.code().equals(PreparationNotice.SIGNATURE_LINES_LEFT)),
                "the printed signature line was never looked at");
    }

    @Test
    void aFormWhoseOnlyFieldsCannotBeTypedInStillGetsItsBlanksFound() {
        PdfFormGraph flat = flatForm();
        PdfFormGraph.Field checkBox = new PdfFormGraph.Field("news", PdfFormGraph.FieldKind.CHECKBOX, false, false, false, false, null,
                null, null, List.of(new PdfFormGraph.Widget(1, new PdfRect(150, 650, 14, 14))));
        Harness harness = new Harness(new PdfFormReading.Supported(graph(flat.pages(), List.of(checkBox))));

        PdfFormPreparation prepared = harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        assertEquals(1, prepared.spots().size());
        assertTrue(prepared.spots().get(0).definition().binding() instanceof FieldBindingTarget.PageBox);
        assertTrue(prepared.notices().contains(new PreparationNotice(PreparationNotice.PDF_FIELDS_LEFT, 1, null)));
    }

    @Test
    void theNamerIsGivenAPdfOutlineWithEachPlaceMarkedWhereItSits() {
        Harness harness = new Harness(new PdfFormReading.Supported(flatForm()));

        harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        SpotNamingInput input = harness.namer.inputs.get(0);
        assertEquals(DocumentKind.PDF, input.kind());
        assertEquals(List.of("P1L0", "P1L1"), input.outline().stream().map(OutlineLine::key).toList());
        String firstId = input.candidates().get(0).id();
        assertEquals("Full name: [[" + firstId + "]]", input.outline().get(0).text());
        assertTrue(input.candidates().get(1).signatureLike());
    }

    @Test
    void markersTypedOnThePageAreNotReadAsPlaces() {
        PdfFormGraph flat = flatForm();
        PdfFormGraph.Line office = line(2, 60, word("Office:", 72, 60, 30, "Times-Roman", 12),
                word("[[" + "b1]]", 110, 60, 30, "Times-Roman", 12));
        PdfFormGraph.Page page = flat.pages().get(0);
        List<PdfFormGraph.Line> lines = new java.util.ArrayList<>(page.lines());
        lines.add(office);
        PdfFormGraph withMarkers = graph(List.of(new PdfFormGraph.Page(1, crop(), 0, 1, true, lines, List.of(), List.of(), List.of())),
                List.of());
        Harness harness = new Harness(new PdfFormReading.Supported(withMarkers));

        harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        List<String> texts = harness.namer.inputs.get(0).outline().stream().map(OutlineLine::text).toList();
        assertTrue(texts.contains("Office: [ [b1] ]"), texts.toString());
    }

    @Test
    void namesTheModelGaveAreUsedAndSaySo() {
        Harness harness = new Harness(new PdfFormReading.Supported(fillableForm()));
        harness.namer.rename = candidate -> candidate.id().equals("f1")
                ? new NamedSpot("f1", true, "Business name", FieldType.TEXT, "TEXT", true, true)
                : null;

        PdfFormPreparation prepared = harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        PreparedPdfSpot first = prepared.spots().get(0);
        assertEquals("Business name", first.definition().label());
        assertEquals("business.name", first.definition().fieldId());
        assertEquals(NamingSource.MODEL, first.namedBy());
        assertFalse(first.requiredHint(), "a form field's hint is the form's own required flag");
        assertEquals(NamingSource.RULES, prepared.spots().get(1).namedBy());
        assertEquals(NamingSource.MODEL, prepared.spotNaming());
    }

    @Test
    void aScanWithNoTextAndNoFieldsHasNoSpotsAndIsSaidToBeOne() {
        PdfFormGraph.Page page = new PdfFormGraph.Page(1, crop(), 0, 1, false, List.of(), List.of(), List.of(),
                List.of(new PdfFormGraph.Image(new PdfRect(0, 0, 612, 792), List.of("CCITTFaxDecode"))));
        Harness harness = new Harness(new PdfFormReading.Supported(graph(List.of(page), List.of())));

        PdfFormPreparation prepared = harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        assertTrue(prepared.spots().isEmpty());
        assertEquals(List.of(new PreparationNotice(PreparationNotice.SCANNED_PDF, 0, null)), prepared.notices());
        assertTrue(harness.namer.inputs.isEmpty(), "nothing to name, so the namer is not asked");
        assertEquals(SpotNaming.NO_CANDIDATES, prepared.rulesOnlyReason());
    }

    @Test
    void aRefusedPdfIsRefusedWithItsReasonAndStaysRefusedWithoutBeingReadAgain() {
        Harness harness = new Harness(new PdfFormReading.Unsupported(UnsupportedPdfFormReason.ENCRYPTED, "owner password"));

        UnusablePdfFormException first = assertThrows(UnusablePdfFormException.class,
                () -> harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID));
        UnusablePdfFormException again = assertThrows(UnusablePdfFormException.class,
                () -> harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID));

        assertEquals(UnsupportedPdfFormReason.ENCRYPTED, first.reason());
        assertEquals(UnsupportedPdfFormReason.ENCRYPTED, again.reason());
        assertTrue(first.getMessage().startsWith("This PDF is locked"));
        assertEquals(1, harness.reader.reads);
        assertEquals(ExtractionStatus.UNSUPPORTED,
                harness.readings.findByArtifact(WORKSPACE_ID, USER_ID, ARTIFACT_ID, Harness.READER_VERSION).orElseThrow().status());
    }

    @Test
    void preparingTheSamePdfAgainReadsNothing() {
        Harness harness = new Harness(new PdfFormReading.Supported(fillableForm()));

        PdfFormPreparation first = harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);
        PdfFormPreparation again = harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        assertEquals(1, harness.reader.reads);
        assertEquals(first.formExtractionId(), again.formExtractionId());
        assertEquals(first.spots(), again.spots());
    }

    @Test
    void anArtifactThatIsNotAPdfIsRefusedBeforeReading() {
        Harness harness = new Harness(new PdfFormReading.Supported(fillableForm()));
        harness.mediaType = SupportedMediaType.DOCX;

        assertThrows(NotPdfArtifactException.class, () -> harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID));
        assertEquals(0, harness.reader.reads);
    }

    // ---- the upload step's answer ----

    @Test
    void theUploadStepAnswersWithThePdfItselfPinnedToItsReadingAndEachPlaceAsATemplateField() {
        Harness harness = new Harness(new PdfFormReading.Supported(fillableForm()));
        PdfFillableForms forms = harness.forms();

        FillableForm form = forms.prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);
        PdfFormPreparation prepared = harness.service().prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        assertEquals(FillableForm.Kind.PDF, form.kind());
        assertEquals(ARTIFACT_ID, form.sourceArtifactId());
        assertEquals(ARTIFACT_ID, form.templateSourceArtifactId(), "a PDF is filled as it is, so no copy is made");
        assertEquals(SupportedMediaType.PDF, form.sourceFormat());
        assertFalse(form.converted());
        assertEquals(new FillableForm.Extraction(prepared.formExtractionId(), ExtractionStatus.COMPLETE.name(), Harness.READER_VERSION,
                List.of()), form.extraction());
        assertEquals(prepared.spots().stream().map(PreparedPdfSpot::definition).toList(),
                form.spots().stream().map(FillableForm.Spot::field).toList());
        assertEquals(prepared.spots().stream().map(PreparedPdfSpot::requiredHint).toList(),
                form.spots().stream().map(FillableForm.Spot::requiredHint).toList());
        assertTrue(form.spots().stream().allMatch(spot -> spot.namedBy() == NamingSource.RULES
                && spot.suggestedType() == null && spot.kind() == null && spot.field().blankText() == null));
        assertEquals(prepared.notices(), form.notices());
        assertEquals(NamingSource.RULES, form.spotNaming());
        assertEquals(SpotNaming.DISABLED, form.rulesOnlyReason());
        assertEquals(1, harness.reader.reads);
    }

    @Test
    void theUploadStepSaysTheFirstRequestReadThePdfAndLaterOnesDidNot() {
        Harness harness = new Harness(new PdfFormReading.Supported(fillableForm()));
        PdfFillableForms forms = harness.forms();

        FillableForm first = forms.prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);
        FillableForm again = forms.prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);

        assertTrue(first.created());
        assertFalse(again.created());
        assertEquals(first.extraction(), again.extraction());
        assertEquals(first.spots(), again.spots());
    }

    /**
     * Naming may be a paid call whose answer differs from one call to the
     * next, so the first answer is kept: asking again, or reading it back,
     * answers with the same spots and names nothing.
     */
    @Test
    void theUploadStepKeepsItsFirstAnswerAndNamesThePlacesOnlyOnce() {
        Harness harness = new Harness(new PdfFormReading.Supported(flatForm()));
        PdfFillableForms forms = harness.forms();
        harness.namer.rename = candidate -> new NamedSpot(candidate.id(), !candidate.signatureLike(), "Applicant name", FieldType.TEXT,
                "TEXT", true, true);

        assertTrue(forms.find(WORKSPACE_ID, USER_ID, ARTIFACT_ID).isEmpty(), "nothing is kept before the first request");
        FillableForm first = forms.prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);
        harness.namer.rename = candidate -> new NamedSpot(candidate.id(), !candidate.signatureLike(), "Your full name", FieldType.TEXT,
                "TEXT", false, true);
        int wholeReadingsBefore = harness.readings.wholeReadings;
        FillableForm again = forms.prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID);
        FillableForm readBack = forms.find(WORKSPACE_ID, USER_ID, ARTIFACT_ID).orElseThrow();

        assertEquals(wholeReadingsBefore, harness.readings.wholeReadings, "what was kept is answered without loading the reading");
        assertEquals(1, harness.namer.inputs.size(), "the places are named once");
        assertEquals(List.of("applicant.name"), first.spots().stream().map(spot -> spot.field().fieldId()).toList());
        assertTrue(first.spots().getFirst().field().binding() instanceof FieldBindingTarget.PageBox);
        assertEquals(NamingSource.MODEL, first.spotNaming());
        for (FillableForm answer : List.of(again, readBack)) {
            assertFalse(answer.created());
            assertEquals(first.spots(), answer.spots());
            assertEquals(first.notices(), answer.notices());
            assertEquals(first.extraction(), answer.extraction());
            assertEquals(first.spotNaming(), answer.spotNaming());
            assertEquals(first.rulesOnlyReason(), answer.rulesOnlyReason());
        }
        assertEquals(List.of(ArtifactDerivation.PDF_FORM), harness.derivations.rows.stream().map(ArtifactDerivation::kind).toList());
        assertEquals(ARTIFACT_ID, harness.derivations.rows.getFirst().outputArtifactId(), "a PDF's answer is kept against the PDF itself");
        assertEquals(PdfFillableForms.RECIPE + "/" + Harness.READER_VERSION, harness.derivations.rows.getFirst().recipeVersion());
    }

    @Test
    void nothingIsKeptForAPdfThatCannotBeFilled() {
        Harness harness = new Harness(new PdfFormReading.Unsupported(UnsupportedPdfFormReason.XFA, "dynamic"));
        PdfFillableForms forms = harness.forms();

        assertThrows(UnusablePdfFormException.class, () -> forms.prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID));

        assertTrue(harness.derivations.rows.isEmpty());
        assertTrue(forms.find(WORKSPACE_ID, USER_ID, ARTIFACT_ID).isEmpty());
    }

    @Test
    void theUploadStepRefusesAPdfThatCannotBeFilledWithItsReason() {
        Harness harness = new Harness(new PdfFormReading.Unsupported(UnsupportedPdfFormReason.XFA, "dynamic"));
        PdfFillableForms forms = harness.forms();

        UnusablePdfFormException refused = assertThrows(UnusablePdfFormException.class, () -> forms.prepare(WORKSPACE_ID, USER_ID, ARTIFACT_ID));

        assertEquals(UnsupportedPdfFormReason.XFA, refused.reason());
    }

    @Test
    void aFieldNameReadsAsWords() {
        assertEquals("Company name", PdfFormPreparationService.readableName("txtCompanyName"));
        assertEquals("Phone", PdfFormPreparationService.readableName("applicant.phone"));
        assertEquals("Date of birth", PdfFormPreparationService.readableName("form1[0].date_of_birth[0]"));
        assertEquals("Zip code 2", PdfFormPreparationService.readableName("ZIPCode2"));
        assertEquals("Text 1", PdfFormPreparationService.readableName("Text1"));
    }

    @Test
    void aPlainFieldNameGivesWayToTheWordsBesideIt() {
        PdfFormGraph.Field plain = field("Text1", null, false, new PdfRect(150, 398, 150, 16));
        PdfFormGraph.Field named = field("txtCity", null, false, new PdfRect(150, 398, 150, 16));

        assertEquals("Phone", PdfFormPreparationService.formFieldLabel(plain, "Phone", 1));
        assertEquals("City", PdfFormPreparationService.formFieldLabel(named, "Phone", 1));
        assertEquals("Text 1", PdfFormPreparationService.formFieldLabel(plain, null, 1));
    }

    // ---- the forms ----

    /**
     * A page with four fillable text fields (a described one, one named
     * {@code txtCompanyName}, a required email, a plain {@code Text1} beside
     * the label "Phone:", and one whose script formats a date), plus a
     * read-only field and a check box, which are left.
     */
    private static PdfFormGraph fillableForm() {
        PdfFormGraph.Line phoneLabel = line(0, 400, word("Phone:", 72, 400, 30, "Helvetica", 11));
        PdfFormGraph.Page page = new PdfFormGraph.Page(1, crop(), 0, 1, true, List.of(phoneLabel), List.of(), List.of(), List.of());
        List<PdfFormGraph.Field> fields = List.of(
                field("applicant.txtCompanyName", null, false, new PdfRect(150, 100, 300, 20)),
                new PdfFormGraph.Field("email", PdfFormGraph.FieldKind.TEXT, false, true, false, false, null, "Email address", null,
                        List.of(new PdfFormGraph.Widget(1, new PdfRect(150, 200, 300, 20)))),
                field("Text1", null, false, new PdfRect(150, 398, 150, 16)),
                new PdfFormGraph.Field("dob", PdfFormGraph.FieldKind.TEXT, false, false, false, false, null, "Date of birth",
                        "mm/dd/yyyy", List.of(new PdfFormGraph.Widget(1, new PdfRect(150, 500, 150, 20)))),
                field("reference", null, true, new PdfRect(150, 600, 150, 20)),
                new PdfFormGraph.Field("news", PdfFormGraph.FieldKind.CHECKBOX, false, false, false, false, null, null, null,
                        List.of(new PdfFormGraph.Widget(1, new PdfRect(150, 650, 14, 14)))));
        return graph(List.of(page), fields);
    }

    /** A page with "Full name:" in Times over a run of underscores, and a signature line the same way. */
    private static PdfFormGraph flatForm() {
        PdfFormGraph.Line name = line(0, 90,
                word("Full", 72, 90, 20, "Times-Roman", 12),
                word("name:", 95, 90, 30, "Times-Roman", 12),
                word("____________________________", 130, 90, 180, "Times-Roman", 12));
        PdfFormGraph.Line signature = line(1, 300,
                word("Signature:", 72, 300, 50, "Times-Roman", 12),
                word("____________________________", 130, 300, 180, "Times-Roman", 12));
        PdfFormGraph.Page page = new PdfFormGraph.Page(1, crop(), 0, 1, true, List.of(name, signature), List.of(), List.of(), List.of());
        return graph(List.of(page), List.of());
    }

    /** The flat form's page, with its "Full name" blank and signature line printed, and one fillable text field of its own. */
    private static PdfFormGraph fieldsAndPrintedBlanks() {
        return graph(flatForm().pages(), List.of(field("applicant.txtCompanyName", null, false, new PdfRect(150, 500, 300, 20))));
    }

    /** A form drawn as a stack of boxes edge to edge: each label in a box on the left, an empty box to write in on its right. */
    private static PdfFormGraph labelledBoxes(String... labels) {
        List<PdfFormGraph.Line> lines = new ArrayList<>();
        List<PdfRect> boxes = new ArrayList<>();
        lines.add(line(0, 60, word("Please", 72, 60, 40, "Helvetica", 10), word("fill", 115, 60, 20, "Helvetica", 10),
                word("in", 138, 60, 12, "Helvetica", 10), word("this", 153, 60, 22, "Helvetica", 10), word("form.", 178, 60, 30, "Helvetica", 10)));
        for (int row = 0; row < labels.length; row++) {
            double top = 100 + 25 * row;
            boxes.add(new PdfRect(72, top, 100, 25));
            boxes.add(new PdfRect(172, top, 250, 25));
            lines.add(line(row + 1, top + 7, word(labels[row], 76, top + 7, labels[row].length() * 5.5, "Helvetica", 11)));
        }
        PdfFormGraph.Page page = new PdfFormGraph.Page(1, crop(), 0, 1, true, lines, List.of(), boxes, List.of());
        return graph(List.of(page), List.of());
    }

    private static PdfFormGraph graph(List<PdfFormGraph.Page> pages, List<PdfFormGraph.Field> fields) {
        PdfFormGraph.AcroForm form = fields.isEmpty()
                ? PdfFormGraph.AcroForm.absent()
                : new PdfFormGraph.AcroForm(true, PdfFormGraph.XfaKind.NONE, false, fields, 0);
        return new PdfFormGraph("test-form-v1", pages, form, new PdfFormGraph.Risks(false, false, false));
    }

    private static PdfFormGraph.CropBox crop() {
        return new PdfFormGraph.CropBox(0, 0, 612, 792);
    }

    private static PdfFormGraph.Field field(String name, String tooltip, boolean readOnly, PdfRect widget) {
        return new PdfFormGraph.Field(name, PdfFormGraph.FieldKind.TEXT, readOnly, false, false, false, null, tooltip, null,
                List.of(new PdfFormGraph.Widget(1, widget)));
    }

    private static PdfFormGraph.Line line(int index, double y, PdfFormGraph.Word... words) {
        PdfRect box = words[0].box();
        for (PdfFormGraph.Word word : words) {
            box = box.union(word.box());
        }
        StringBuilder text = new StringBuilder();
        for (PdfFormGraph.Word word : words) {
            text.append(text.isEmpty() ? "" : " ").append(word.text());
        }
        return new PdfFormGraph.Line(index, text.toString(), box, List.of(words));
    }

    private static PdfFormGraph.Word word(String text, double x, double y, double width, String font, double size) {
        return new PdfFormGraph.Word(text, new PdfRect(x, y, width, size), font, size, 0);
    }

    // ---- fakes ----

    private static final class Harness {
        static final String READER_VERSION = "test-form-v1";

        final CountingReader reader;
        final FakeReadings readings = new FakeReadings();
        final RecordingNamer namer = new RecordingNamer();
        final FakeDerivations derivations = new FakeDerivations();
        SupportedMediaType mediaType = SupportedMediaType.PDF;

        Harness(PdfFormReading reading) {
            this.reader = new CountingReader(reading);
        }

        PdfFormPreparationService service() {
            ArtifactService artifacts = new ArtifactService(new OneArtifact(this), new OneBlob(), null, 1_000_000, Duration.ofHours(1));
            return new PdfFormPreparationService(artifacts, reader, readings, namer);
        }

        PdfFillableForms forms() {
            return new PdfFillableForms(service(), readings, derivations, READER_VERSION);
        }
    }

    /** Keeps derivations in memory, one per source, kind and recipe version: the first one written stands. */
    private static final class FakeDerivations implements ArtifactDerivationRepository {
        final List<ArtifactDerivation> rows = new ArrayList<>();

        @Override
        public Optional<ArtifactDerivation> find(long workspaceId, long userId, long sourceArtifactId, String kind, String recipeVersion) {
            return rows.stream()
                    .filter(row -> row.sourceArtifactId() == sourceArtifactId && row.kind().equals(kind) && row.recipeVersion().equals(recipeVersion))
                    .findFirst();
        }

        @Override
        public ArtifactDerivation insertOrGet(long workspaceId, long userId, long sourceArtifactId, long outputArtifactId, String kind,
                                              String recipeVersion, String sourceFormat, String converter, NamingSource spotNaming,
                                              String rulesOnlyReason, List<FillableForm.Spot> spots, List<PreparationNotice> notices) {
            return find(workspaceId, userId, sourceArtifactId, kind, recipeVersion).orElseGet(() -> {
                ArtifactDerivation row = new ArtifactDerivation(rows.size() + 1, workspaceId, sourceArtifactId, outputArtifactId, kind,
                        recipeVersion, sourceFormat, converter, spotNaming, rulesOnlyReason, spots, notices, userId, OffsetDateTime.now());
                rows.add(row);
                return row;
            });
        }
    }

    private static final class CountingReader implements PdfFormReader {
        private final PdfFormReading reading;
        int reads;

        CountingReader(PdfFormReading reading) {
            this.reading = reading;
        }

        @Override
        public String parserVersion() {
            return Harness.READER_VERSION;
        }

        @Override
        public PdfFormReading read(byte[] pdf) {
            reads++;
            return reading;
        }
    }

    /** The rules' own answer, except where a test says what the model would call a place. */
    private static final class RecordingNamer implements SpotNamer {
        final List<SpotNamingInput> inputs = new ArrayList<>();
        java.util.function.Function<NamingCandidate, NamedSpot> rename = candidate -> null;

        @Override
        public SpotNaming name(long workspaceId, long userId, SpotNamingInput input) {
            inputs.add(input);
            List<NamedSpot> spots = new ArrayList<>();
            boolean byModel = false;
            for (NamingCandidate candidate : input.candidates()) {
                NamedSpot renamed = rename.apply(candidate);
                byModel |= renamed != null;
                spots.add(renamed != null ? renamed : RulesOnlySpotNamer.byRules(candidate));
            }
            return byModel
                    ? new SpotNaming(spots, null, NamingSource.MODEL, null, List.of())
                    : new SpotNaming(spots, null, NamingSource.RULES, SpotNaming.DISABLED, List.of());
        }
    }

    private static final class FakeReadings implements PdfFormExtractionVersionRepository {
        private final Map<Long, PdfFormExtractionVersion> byId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(300);
        long lastSavedId;
        /** How many times a whole reading, graph and all, was handed out. */
        int wholeReadings;

        @Override
        public Optional<PdfFormExtractionVersion> findById(long workspaceId, long userId, long id) {
            wholeReadings++;
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<PdfFormExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
            wholeReadings++;
            return byId.values().stream()
                    .filter(reading -> reading.artifactId() == artifactId && reading.parserVersion().equals(parserVersion))
                    .findFirst();
        }

        @Override
        public Optional<Long> findCompleteIdByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
            return byId.values().stream()
                    .filter(reading -> reading.artifactId() == artifactId && reading.parserVersion().equals(parserVersion)
                            && reading.status() == ExtractionStatus.COMPLETE)
                    .map(PdfFormExtractionVersion::id)
                    .findFirst();
        }

        @Override
        public PdfFormExtractionVersion saveComplete(long workspaceId, long userId, long artifactId, String parserVersion, PdfFormGraph graph) {
            return save(new PdfFormExtractionVersion(ids.getAndIncrement(), workspaceId, artifactId, parserVersion,
                    ExtractionStatus.COMPLETE, null, null, graph, OffsetDateTime.now()));
        }

        @Override
        public PdfFormExtractionVersion saveUnsupported(
                long workspaceId, long userId, long artifactId, String parserVersion, UnsupportedPdfFormReason reason, String detail) {
            return save(new PdfFormExtractionVersion(ids.getAndIncrement(), workspaceId, artifactId, parserVersion,
                    ExtractionStatus.UNSUPPORTED, reason, detail, null, OffsetDateTime.now()));
        }

        private PdfFormExtractionVersion save(PdfFormExtractionVersion reading) {
            byId.put(reading.id(), reading);
            lastSavedId = reading.id();
            return reading;
        }
    }

    /** Holds the one uploaded file, of whatever type the harness says. */
    private static final class OneArtifact implements ArtifactRepository {
        private final Harness harness;

        OneArtifact(Harness harness) {
            this.harness = harness;
        }

        @Override
        public Optional<Artifact> find(long workspaceId, long userId, long artifactId) {
            return artifactId != ARTIFACT_ID ? Optional.empty() : Optional.of(new Artifact(ARTIFACT_ID, WORKSPACE_ID, "blob",
                    ArtifactStatus.READY, 5L, "a".repeat(64), harness.mediaType, "form.pdf", null, OffsetDateTime.now(), OffsetDateTime.now()));
        }

        @Override
        public Artifact initiateUpload(long workspaceId, long userId, String displayFilename) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact recordUploadedContent(
                long workspaceId, long userId, long artifactId, long byteCount, String sha256, SupportedMediaType detectedMediaType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact finalizeUpload(long workspaceId, long userId, long artifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact reject(long workspaceId, long userId, long artifactId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact beginScanning(long workspaceId, long userId, long artifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact markReady(long workspaceId, long userId, long artifactId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Artifact revertToQuarantined(long workspaceId, long userId, long artifactId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class OneBlob implements BlobStore {
        @Override
        public InputStream openStream(String objectKey) {
            return new ByteArrayInputStream("%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }

        @Override
        public UploadResult writeAndDigest(String objectKey, InputStream content, long maxBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UploadResult writeNewAndDigest(String objectKey, InputStream content, long maxBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Long> sizeOf(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            throw new UnsupportedOperationException();
        }
    }
}
