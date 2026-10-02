package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactNotFoundException;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.ContentInspection;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxFeatureFinding;
import io.github.vihuynh72.brownie.core.document.DocxFeatureReport;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.UnsupportedDocxFeature;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The upload step end to end with every port faked: routing, idempotency, retries, and what is stored and answered. */
class FillableFormServiceTest {

    private static final long WORKSPACE = 3;
    private static final long USER = 5;
    private static final String PARSER = "graph-v3";

    private final Artifacts artifacts = new Artifacts();
    private final Extractions extractions = new Extractions(artifacts);
    private final Converter converter = new Converter();
    private final Preparer preparer = new Preparer();
    private final Forms forms = new Forms();
    private final Editor editor = new Editor();
    private final Verifier verifier = new Verifier();
    private final Derivations derivations = new Derivations();
    private final CountingNamer namer = new CountingNamer();
    private final PdfForms pdfForms = new PdfForms();

    private final FillableFormService service = new FillableFormService(artifacts, extractions, new Extractor(), converter, preparer,
            forms, namer, editor, verifier, derivations, pdfForms);

    @Test
    void aWordUploadIsMadeIntoAStoredCopyWithNamedSpotsAndRecorded() {
        long upload = artifacts.add(SupportedMediaType.DOCX, "membership.docx");
        preparer.notices = List.of(new PreparationNotice(PreparationNotice.TRACKED_CHANGES_AND_COMMENTS, 2, null));
        forms.outline = outline(body("p0", "Full name: ______"), body("p1", "Date of birth: __/__/____"));

        FillableForm form = service.prepare(WORKSPACE, USER, upload);

        assertTrue(form.created());
        assertEquals(FillableForm.Kind.DOCX, form.kind());
        assertEquals(upload, form.sourceArtifactId());
        assertEquals("membership.docx", artifacts.names.get(form.templateSourceArtifactId()));
        assertEquals(SupportedMediaType.DOCX, form.sourceFormat());
        assertFalse(form.converted());
        assertEquals(List.of("full.name", "date.of.birth"), form.spots().stream().map(spot -> spot.field().fieldId()).toList());
        assertEquals(NamingSource.RULES, form.spotNaming());
        assertEquals(SpotNaming.DISABLED, form.rulesOnlyReason());
        assertEquals(List.of(
                new PreparationNotice(PreparationNotice.TRACKED_CHANGES_AND_COMMENTS, 2, null),
                new PreparationNotice(PreparationNotice.SPOTS_FOUND, 2, null)), form.notices());
        assertEquals("COMPLETE", form.extraction().status());
        assertEquals(List.of(new FillableForm.KeptFeature("FLOATING_SHAPE", 2)), form.extraction().keptAsIs());
        assertEquals("fillable-form-v1/" + PARSER, derivations.rows.getFirst().recipeVersion());
        assertEquals(PreparationMode.UPLOAD, preparer.mode);
        assertEquals(2, editor.lastEdits.size());
    }

    @Test
    void askingAgainAnswersWithTheSameCopyAndMakesNothingNew() {
        long upload = artifacts.add(SupportedMediaType.DOCX, "form.docx");
        forms.outline = outline(body("p0", "Name: ____"));
        FillableForm first = service.prepare(WORKSPACE, USER, upload);
        int stored = artifacts.stored;

        FillableForm again = service.prepare(WORKSPACE, USER, upload);

        assertFalse(again.created());
        assertEquals(first.templateSourceArtifactId(), again.templateSourceArtifactId());
        assertEquals(first.spots(), again.spots());
        assertEquals(stored, artifacts.stored);
        assertEquals(1, namer.calls);
        assertEquals(Optional.of(again), service.find(WORKSPACE, USER, upload));
        assertEquals(Optional.empty(), service.find(WORKSPACE, USER, 999));
    }

    @Test
    void aRaceLostToAnotherRequestAnswersWithTheOtherCopy() {
        long upload = artifacts.add(SupportedMediaType.DOCX, "form.docx");
        forms.outline = outline(body("p0", "Name: ____"));
        derivations.raceWinnerOutput = 777L;

        FillableForm form = service.prepare(WORKSPACE, USER, upload);

        assertFalse(form.created());
        assertEquals(777L, form.templateSourceArtifactId());
    }

    @Test
    void aFormWithNoPlacesOpensWithNoSpotsAndNothingIsSentToTheNamer() {
        long upload = artifacts.add(SupportedMediaType.DOCX, "letter.docx");
        forms.outline = outline(body("p0", "Dear friend, thank you for coming."));

        FillableForm form = service.prepare(WORKSPACE, USER, upload);

        assertTrue(form.spots().isEmpty());
        assertEquals(0, namer.calls);
        assertEquals(SpotNaming.NO_CANDIDATES, form.rulesOnlyReason());
        assertEquals(List.of(new PreparationNotice(PreparationNotice.NO_SPOTS_FOUND, 0, null)), form.notices());
    }

    @Test
    void aFileThatIsNoWordProcessingDocumentOrPdfIsRefusedWithItsOwnCode() {
        long text = artifacts.add(SupportedMediaType.PLAIN_TEXT, "notes.txt");

        assertEquals(NotAFillableFormException.Code.NOT_A_WORD_PROCESSING_DOCUMENT,
                assertThrows(NotAFillableFormException.class, () -> service.prepare(WORKSPACE, USER, text)).code());
        assertThrows(ArtifactNotFoundException.class, () -> service.prepare(WORKSPACE, USER, 404));
        assertEquals(List.of(), pdfForms.prepared);
    }

    @Test
    void aPdfIsHandedToThePdfStepAsItIsAndNothingOfTheWordStepRuns() {
        long pdf = artifacts.add(SupportedMediaType.PDF, "form.pdf");

        FillableForm form = service.prepare(WORKSPACE, USER, pdf);

        assertEquals(List.of(pdf), pdfForms.prepared);
        assertEquals(FillableForm.Kind.PDF, form.kind());
        assertEquals(0, namer.calls);
        assertEquals(Optional.of(FillableForm.Kind.PDF), service.find(WORKSPACE, USER, pdf).map(FillableForm::kind),
                "a PDF's answer is what the PDF step kept");
        assertTrue(service.find(WORKSPACE, USER, artifacts.add(SupportedMediaType.PDF, "other.pdf")).isEmpty());
    }

    @Test
    void aFileTheConverterCannotReadIsRefusedWithTheReason() {
        long doc = artifacts.add(SupportedMediaType.DOC, "old.doc");
        artifacts.inspection = new ContentInspection(SupportedMediaType.DOC, ConvertibleFormat.WORD_97);

        converter.failure = new DocumentConversionException(DocumentConversionException.Reason.CANNOT_OPEN, "no");
        assertEquals(FillableFormFailedException.Reason.CANNOT_OPEN,
                assertThrows(FillableFormFailedException.class, () -> service.prepare(WORKSPACE, USER, doc)).reason());
        converter.failure = new DocumentConversionException(DocumentConversionException.Reason.TIMED_OUT, "slow");
        assertEquals(FillableFormFailedException.Reason.TIMED_OUT,
                assertThrows(FillableFormFailedException.class, () -> service.prepare(WORKSPACE, USER, doc)).reason());
        converter.failure = new ConversionFormatDisabledException(ConvertibleFormat.WORD_97);
        assertThrows(ConversionFormatDisabledException.class, () -> service.prepare(WORKSPACE, USER, doc));

        converter.failure = null;
        converter.output = "not a word document".getBytes(StandardCharsets.UTF_8);
        assertEquals(FillableFormFailedException.Reason.DAMAGED,
                assertThrows(FillableFormFailedException.class, () -> service.prepare(WORKSPACE, USER, doc)).reason());
        assertEquals(ConvertibleFormat.WORD_97, converter.format);
        assertTrue(derivations.rows.isEmpty());
    }

    @Test
    void aFileNoCleanCopyCanBeMadeOfIsDamaged() {
        long upload = artifacts.add(SupportedMediaType.DOCM, "macro.docm");
        preparer.failure = new WorkingCopyPreparationException(WorkingCopyPreparationException.Reason.NOT_CLEAN, "still has a macro");

        assertEquals(FillableFormFailedException.Reason.DAMAGED,
                assertThrows(FillableFormFailedException.class, () -> service.prepare(WORKSPACE, USER, upload)).reason());
    }

    @Test
    void aSpotThatFailsItsCheckIsLeftOutAndTheRestAreMadeAgain() {
        long upload = artifacts.add(SupportedMediaType.DOCX, "form.docx");
        forms.outline = outline(body("p0", "Name: ____"), body("p1", "Town: ____"));
        verifier.answers.add(new FillableCopyCheck(false, Set.of("town"), false, List.of("town: not placed")));

        FillableForm form = service.prepare(WORKSPACE, USER, upload);

        assertEquals(List.of("name"), form.spots().stream().map(spot -> spot.field().fieldId()).toList());
        assertEquals(1, editor.lastEdits.size());
        assertTrue(form.notices().contains(new PreparationNotice(PreparationNotice.SPOTS_SKIPPED, 1, null)));
        assertTrue(form.notices().contains(new PreparationNotice(PreparationNotice.SPOTS_FOUND, 1, null)));
    }

    @Test
    void aPlaceTheEditorRefusesIsLeftOut() {
        long upload = artifacts.add(SupportedMediaType.DOCX, "form.docx");
        forms.outline = outline(body("p0", "Name: ____"), body("p1", "Town: ____"));
        editor.refusedTags.add("name");

        FillableForm form = service.prepare(WORKSPACE, USER, upload);

        assertEquals(List.of("town"), form.spots().stream().map(spot -> spot.field().fieldId()).toList());
    }

    @Test
    void placesTheEditorCannotMakeTogetherFailTheCopySoOnlyTheFormsOwnAreMade() {
        long upload = artifacts.add(SupportedMediaType.DOCX, "form.docx");
        forms.outline = outline(body("p0", "Name: ____"), body("p1", "Town: ____"));
        editor.overlapping = true;

        FillableForm form = service.prepare(WORKSPACE, USER, upload);

        assertTrue(form.spots().isEmpty());
        assertTrue(form.notices().contains(new PreparationNotice(PreparationNotice.SPOTS_SKIPPED, 2, null)));
    }

    @Test
    void aCopyThatStillFailsIsRefusedRatherThanHandedOn() {
        long upload = artifacts.add(SupportedMediaType.DOCX, "form.docx");
        forms.outline = outline(body("p0", "Name: ____"));
        verifier.answers.add(new FillableCopyCheck(true, Set.of(), false, List.of("text changed")));
        verifier.answers.add(new FillableCopyCheck(true, Set.of(), false, List.of("text changed")));

        assertEquals(FillableFormFailedException.Reason.DAMAGED,
                assertThrows(FillableFormFailedException.class, () -> service.prepare(WORKSPACE, USER, upload)).reason());
        assertEquals(0, artifacts.stored);
    }

    @Test
    void aRepeatingRowThatWillNotFillIsFilledOnceAndTheRowsTakenOutForItComeBack() {
        long upload = artifacts.add(SupportedMediaType.DOCX, "form.docx");
        forms.outline = outline(
                cell(0, 0, 3, "Item"), cell(1, 0, 3, ""), cell(2, 0, 3, ""));
        verifier.answers.add(new FillableCopyCheck(false, Set.of(), true, List.of("repeat failed")));

        FillableForm form = service.prepare(WORKSPACE, USER, upload);

        assertEquals(List.of(List.of("tbl0/row2")), forms.removedRows);
        assertEquals(List.of(FieldCardinality.SCALAR), form.spots().stream().map(spot -> spot.field().cardinality()).toList());
        assertFalse(form.notices().stream().anyMatch(notice -> notice.code().equals(PreparationNotice.TABLE_ROWS_GROW)));
        assertEquals("prepared", new String(editor.lastBase, StandardCharsets.UTF_8), "the copy is made from the file with its rows");
    }

    @Test
    void aCopyThatFailsAsAWholeKeepsTheRowsTakenOutForARowThatNoLongerRepeats() {
        long upload = artifacts.add(SupportedMediaType.DOCX, "form.docx");
        forms.outline = outline(
                cell(0, 0, 3, "Item"), cell(1, 0, 3, ""), cell(2, 0, 3, ""));
        verifier.answers.add(new FillableCopyCheck(true, Set.of(), false, List.of("sample fill failed")));

        FillableForm form = service.prepare(WORKSPACE, USER, upload);

        assertTrue(form.spots().isEmpty());
        assertFalse(form.notices().stream().anyMatch(notice -> notice.code().equals(PreparationNotice.TABLE_ROWS_GROW)));
        assertEquals("prepared", new String(editor.lastBase, StandardCharsets.UTF_8), "the copy is made from the file with its rows");
    }

    @Test
    void aNewSpotsIdIsNoTagAControlOutsideTheBodyAlreadyCarries() {
        long upload = artifacts.add(SupportedMediaType.DOCX, "membership.docx");
        FormOutline.Paragraph header = new FormOutline.Paragraph("header1#p0", DocumentPartKind.HEADER, FormOutline.Region.HEADER_FOOTER,
                "p0", "Member ", null, false, null, List.of(
                        new FormOutline.Run(0, 7, false, false, false, false),
                        new FormOutline.Control(7, "p0/sdt0", "full.name", null, "Jordan Lee", FormOutline.ControlKind.TEXT)));
        forms.outline = outline(header, body("p0", "Full name: ______"));

        FillableForm form = service.prepare(WORKSPACE, USER, upload);

        assertEquals(List.of("full.name.2"), form.spots().stream().map(spot -> spot.field().fieldId()).toList());
    }

    @Test
    void theWorkingCopyIsNamedAfterTheUpload() {
        assertEquals("membership.docx", FillableFormService.workingCopyName(artifact(1, SupportedMediaType.ODT, "membership.odt")));
        assertEquals("form.docx", FillableFormService.workingCopyName(artifact(1, SupportedMediaType.DOC, null)));
        assertEquals("notes.docx", FillableFormService.workingCopyName(artifact(1, SupportedMediaType.RTF, "notes")));
    }

    // ---------------------------------------------------------------- outlines

    private static FormOutline outline(FormOutline.Paragraph... paragraphs) {
        return new FormOutline(PARSER, List.of(paragraphs));
    }

    private static FormOutline.Paragraph body(String nodeId, String text) {
        return new FormOutline.Paragraph("main#" + nodeId, DocumentPartKind.MAIN_DOCUMENT, FormOutline.Region.BODY, nodeId, text, null,
                false, null, List.of(new FormOutline.Run(0, text.length(), false, false, false, false)));
    }

    private static FormOutline.Paragraph cell(int row, int column, int rows, String text) {
        String rowNodeId = "tbl0/row" + row;
        String nodeId = rowNodeId + "/cell" + column + "/p0";
        List<FormOutline.Atom> atoms = text.isEmpty() ? List.of() : List.of(new FormOutline.Run(0, text.length(), false, false, false, false));
        return new FormOutline.Paragraph("main#" + nodeId, DocumentPartKind.MAIN_DOCUMENT, FormOutline.Region.TOP_TABLE_CELL, nodeId, text,
                null, false, new FormOutline.Cell(1, "tbl0", rowNodeId, row, column, rows), atoms);
    }

    private static Artifact artifact(long id, SupportedMediaType type, String name) {
        return new Artifact(id, WORKSPACE, "blob-" + id, ArtifactStatus.READY, 10L, "sha", type, name, null, OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    // ---------------------------------------------------------------- fakes

    private static final class Artifacts extends ArtifactService {
        final Map<Long, Artifact> byId = new HashMap<>();
        final Map<Long, byte[]> bytes = new HashMap<>();
        final Map<Long, String> names = new HashMap<>();
        ContentInspection inspection;
        long nextId = 1;
        int stored;

        Artifacts() {
            super(null, null, null, 0, Duration.ZERO);
        }

        long add(SupportedMediaType type, String name) {
            long id = nextId++;
            byId.put(id, artifact(id, type, name));
            bytes.put(id, ("original " + id).getBytes(StandardCharsets.UTF_8));
            return id;
        }

        @Override
        public ReadableArtifact openContent(long workspaceId, long userId, long artifactId) {
            Artifact artifact = byId.get(artifactId);
            if (artifact == null) {
                throw new ArtifactNotFoundException(artifactId);
            }
            return new ReadableArtifact(artifact, new ByteArrayInputStream(bytes.get(artifactId)));
        }

        @Override
        public ContentInspection inspectContent(long workspaceId, long userId, long artifactId) {
            return inspection;
        }

        @Override
        public Artifact storeGenerated(long workspaceId, long userId, String filename, byte[] content) {
            long id = add(SupportedMediaType.DOCX, filename);
            bytes.put(id, content);
            names.put(id, filename);
            stored++;
            return byId.get(id);
        }
    }

    private static final class Extractions extends DocumentExtractionService {
        final Map<Long, ExtractionVersion> byArtifact = new HashMap<>();

        Extractions(Artifacts artifacts) {
            super(artifacts, null, null, null, null, null, null);
        }

        @Override
        public ExtractionVersion extractDocx(long workspaceId, long userId, long artifactId) {
            return byArtifact.computeIfAbsent(artifactId, id -> new ExtractionVersion(100 + id, workspaceId, id, PARSER,
                    ExtractionStatus.COMPLETE, new DocxFeatureReport(List.of(
                            new DocxFeatureFinding(UnsupportedDocxFeature.FLOATING_SHAPE, "p1", "shape"),
                            new DocxFeatureFinding(UnsupportedDocxFeature.FLOATING_SHAPE, "p2", "shape"))),
                    null, null, OffsetDateTime.now()));
        }
    }

    private static final class Extractor implements DocxStructuralExtractor {
        @Override
        public String parserVersion() {
            return PARSER;
        }

        @Override
        public DocxExtractionOutcome extract(InputStream content) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class Converter implements DocumentConverter {
        RuntimeException failure;
        byte[] output = new byte[0];
        ConvertibleFormat format;

        @Override
        public ConvertedDocument convertToDocx(byte[] source, ConvertibleFormat format) {
            this.format = format;
            if (failure != null) {
                throw failure;
            }
            return new ConvertedDocument(output, "converter-under-test");
        }
    }

    private static final class Preparer implements WorkingCopyPreparer {
        List<PreparationNotice> notices = List.of();
        WorkingCopyPreparationException failure;
        PreparationMode mode;

        @Override
        public PreparedCopy prepare(byte[] docx, PreparationMode mode) {
            this.mode = mode;
            if (failure != null) {
                throw failure;
            }
            return new PreparedCopy("prepared".getBytes(StandardCharsets.UTF_8), notices);
        }
    }

    private static final class Forms implements WordForms {
        FormOutline outline = outline();
        final List<List<String>> removedRows = new ArrayList<>();

        @Override
        public ReadForm read(byte[] docx) {
            return new ReadForm(docx, outline);
        }

        @Override
        public byte[] withoutRows(byte[] docx, List<String> rowNodeIds) {
            removedRows.add(rowNodeIds);
            return "without rows".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class Editor implements FillSpotEditor {
        final Set<String> refusedTags = new HashSet<>();
        List<SpotEdit> lastEdits = List.of();
        byte[] lastBase;
        boolean overlapping;

        @Override
        public EditedDocx apply(byte[] docx, List<SpotEdit> edits) {
            lastBase = docx;
            if (overlapping && edits.size() > 1) {
                throw new IllegalArgumentException("Two places overlap in one line; each place must be separate.");
            }
            for (SpotEdit edit : edits) {
                if (edit instanceof SpotEdit.Insert insert && refusedTags.contains(insert.tag())) {
                    throw new FillSpotPlacementException(FillSpotPlacementException.Reason.INSIDE_LINK, "in a link");
                }
            }
            lastEdits = edits;
            return new EditedDocx(docx);
        }
    }

    private static final class Verifier implements FillableCopyVerifier {
        final List<FillableCopyCheck> answers = new ArrayList<>();

        @Override
        public FillableCopyCheck check(byte[] before, byte[] after, List<FieldDefinition> fields) {
            return answers.isEmpty() ? FillableCopyCheck.passed() : answers.removeFirst();
        }
    }

    private static final class CountingNamer implements SpotNamer {
        int calls;

        @Override
        public SpotNaming name(long workspaceId, long userId, SpotNamingInput input) {
            calls++;
            return new RulesOnlySpotNamer(SpotNaming.DISABLED).name(workspaceId, userId, input);
        }
    }

    /** The PDF step, which records what it was handed, answers with an empty PDF form, and finds what it was handed. */
    private static final class PdfForms implements PdfFormPreparer {
        final List<Long> prepared = new ArrayList<>();

        @Override
        public FillableForm prepare(long workspaceId, long userId, long artifactId) {
            prepared.add(artifactId);
            return form(artifactId);
        }

        @Override
        public Optional<FillableForm> find(long workspaceId, long userId, long artifactId) {
            return prepared.contains(artifactId) ? Optional.of(form(artifactId)) : Optional.empty();
        }

        private static FillableForm form(long artifactId) {
            return new FillableForm(FillableForm.Kind.PDF, artifactId, artifactId, SupportedMediaType.PDF, false,
                    new FillableForm.Extraction(9, ExtractionStatus.COMPLETE.name(), "form-reader", List.of()), List.of(), List.of(),
                    NamingSource.RULES, SpotNaming.NO_CANDIDATES, true);
        }
    }

    private static final class Derivations implements ArtifactDerivationRepository {
        final List<ArtifactDerivation> rows = new ArrayList<>();
        Long raceWinnerOutput;

        @Override
        public Optional<ArtifactDerivation> find(long workspaceId, long userId, long sourceArtifactId, String kind, String recipeVersion) {
            return rows.stream()
                    .filter(row -> row.sourceArtifactId() == sourceArtifactId && row.kind().equals(kind)
                            && row.recipeVersion().equals(recipeVersion))
                    .findFirst();
        }

        @Override
        public ArtifactDerivation insertOrGet(long workspaceId, long userId, long sourceArtifactId, long outputArtifactId, String kind,
                                              String recipeVersion, String sourceFormat, String converter, NamingSource spotNaming,
                                              String rulesOnlyReason, List<FillableForm.Spot> spots, List<PreparationNotice> notices) {
            long output = raceWinnerOutput != null ? raceWinnerOutput : outputArtifactId;
            ArtifactDerivation row = new ArtifactDerivation(rows.size() + 1, workspaceId, sourceArtifactId, output, kind, recipeVersion,
                    sourceFormat, converter, spotNaming, rulesOnlyReason, spots, notices, userId, OffsetDateTime.now());
            rows.add(row);
            return row;
        }
    }
}
