package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfFormReason;
import io.github.vihuynh72.brownie.core.prepare.AnchorPlacement;
import io.github.vihuynh72.brownie.core.prepare.DocxAnchor;
import io.github.vihuynh72.brownie.core.prepare.FillSpotPlacementException;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleProposalEvidence;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deriving a version of a PDF form against a hand-built form reading: a
 * page with two labels, a box Brownie found beside the first and one of
 * the form's own text fields, and a second page Brownie cannot write on.
 * What matters here is the new field list and bindings, that the file and
 * reading are the base's, which boxes are refused and why, and that the key
 * finds the same request again.
 */
class PdfTemplateDerivationTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final long SOURCE_ARTIFACT_ID = 20L;
    private static final long FORM_READING_ID = 21L;
    private static final long SCAN_READING_ID = 22L;
    private static final PdfTextStyle BOX_STYLE = new PdfTextStyle(PdfFontFamily.SANS, false, 11);

    private Set<String> idsEverUsed = Set.of();
    private final List<TemplateVersion> rendered = new ArrayList<>();
    private List<String> failedFieldIds = List.of();
    /** Whether the base carries its one rule, about the found box; a base without that box has none. */
    private boolean foundBoxRule = true;
    private final BaselineRenderResult baseBaseline = new BaselineRenderResult(null, 51L, "base fill", List.of());

    @Test
    void aNewBoxGetsAnIdNoVersionUsedTheLookOfTheWordsBesideItAndItsPlaceInPageOrder() {
        idsEverUsed = Set.of("company");
        PdfRect box = new PdfRect(120, 128, 180, 16);

        PreparedDerivation prepared = service().prepare(WORKSPACE_ID, USER_ID, base(), List.of(
                new FillSpotChange.AddBox(1, box, " Company ", FieldType.TEXT, null, false, null, false)));

        assertEquals(List.of("found.name", "company.2", "email"), prepared.fieldDefinitions().stream().map(FieldDefinition::fieldId).toList());
        FieldDefinition added = prepared.fieldDefinitions().get(1);
        assertEquals("Company", added.label());
        assertEquals(SpotOrigin.ADDED_BY_PERSON, added.origin());
        assertNull(added.docxControl());
        assertNull(added.blankText());
        assertEquals(FieldRequiredness.OPTIONAL, added.requiredness());
        // "Company:" is set in 10 pt Helvetica, which fits the box; overflow and wrapping are the defaults.
        assertEquals(new FieldBindingTarget.PageBox(1, 120, 128, 180, 16, new PdfTextStyle(PdfFontFamily.SANS, false, 10), false,
                PdfOverflowPolicy.SHRINK_TO_FIT), added.binding());
        assertEquals(TemplateKind.PDF, prepared.kind());
        assertEquals(SOURCE_ARTIFACT_ID, prepared.sourceArtifactId());
        assertEquals(FORM_READING_ID, prepared.pdfFormExtractionId());
        assertNull(prepared.extractionVersionId());
        assertEquals(1, rendered.size());
        assertEquals(TemplateKind.PDF, rendered.getFirst().kind());
        assertEquals(FORM_READING_ID, rendered.getFirst().pdfFormExtractionId());
        assertEquals(prepared.fieldDefinitions(), rendered.getFirst().fieldDefinitions());
        assertEquals(List.of("company.2"), prepared.changedFieldIds());
        assertEquals(List.of("ADD_BOX"), prepared.changeKinds());
        assertEquals("Added a fill spot: Company.", prepared.editReason());
        assertTrue(prepared.derivationJson().contains(
                "\"kind\":\"ADD_BOX\",\"fieldId\":\"company.2\",\"label\":\"Company\",\"type\":\"TEXT\",\"origin\":\"ADDED_BY_PERSON\","
                        + "\"page\":1,\"box\":{\"x\":120,\"y\":128,\"width\":180,\"height\":16}"), prepared.derivationJson());
        // The rule about the found box comes along; nothing was taken away.
        assertEquals(List.of(1L), prepared.rules().stream().map(RuleRevision::id).toList());
    }

    @Test
    void aBoxTheModelPlacedShowsAsFoundAndAStyleGivenIsKeptAsItIs() {
        PdfTextStyle serif = new PdfTextStyle(PdfFontFamily.SERIF, true, 14);

        PreparedDerivation prepared = service().prepare(WORKSPACE_ID, USER_ID, base(), List.of(
                new FillSpotChange.AddBox(1, new PdfRect(120, 128, 180, 20), "Company", FieldType.TEXT, serif, true,
                        PdfOverflowPolicy.BLOCK, true)));

        FieldDefinition added = prepared.fieldDefinitions().get(1);
        assertEquals(SpotOrigin.FOUND_BY_BROWNIE, added.origin());
        FieldBindingTarget.PageBox binding = (FieldBindingTarget.PageBox) added.binding();
        assertEquals(serif, binding.style());
        assertTrue(binding.multiline());
        assertEquals(PdfOverflowPolicy.BLOCK, binding.overflow());
    }

    @Test
    void aMovedAndRestyledBoxKeepsItsIdNameAndOriginAndChangesOnlyWhatWasAsked() {
        PreparedDerivation prepared = service().prepare(WORKSPACE_ID, USER_ID, base(), List.of(
                new FillSpotChange.MoveBox("found.name", new PdfRect(150, 86, 220, 18)),
                new FillSpotChange.RestyleBox("found.name", 9.0, PdfOverflowPolicy.BLOCK, null)));

        FieldDefinition moved = prepared.fieldDefinitions().getFirst();
        assertEquals("found.name", moved.fieldId());
        assertEquals("Name", moved.label());
        assertEquals(SpotOrigin.FOUND_BY_BROWNIE, moved.origin());
        assertEquals(new FieldBindingTarget.PageBox(1, 150, 86, 220, 18, new PdfTextStyle(PdfFontFamily.SANS, false, 9), false,
                PdfOverflowPolicy.BLOCK), moved.binding());
        assertEquals(base().fieldDefinitions().get(1), prepared.fieldDefinitions().get(1));
        assertEquals(List.of("found.name", "found.name"), prepared.changedFieldIds());
        assertEquals(List.of("MOVE_BOX", "RESTYLE_BOX"), prepared.changeKinds());
        assertEquals("Moved the fill spot Name. Changed how the fill spot Name shows its text.", prepared.editReason());
        assertTrue(prepared.derivationJson().contains("\"from\":{\"x\":140,\"y\":88,\"width\":200,\"height\":16}"), prepared.derivationJson());
        assertTrue(prepared.derivationJson().contains(
                "\"from\":{\"sizePt\":11,\"overflow\":\"SHRINK_TO_FIT\",\"multiline\":false},"
                        + "\"to\":{\"sizePt\":9,\"overflow\":\"BLOCK\",\"multiline\":false}"), prepared.derivationJson());
        assertEquals(1, rendered.size());
    }

    @Test
    void manyBoxesMovedTogetherAreOneVersionAndItsHistorySaysSo() {
        foundBoxRule = false;
        List<FillSpotChange> moves = new ArrayList<>();
        List<FieldDefinition> fields = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fields.add(boxField("box." + i, "Box " + i, new PdfRect(300, 300 + 30 * i, 100, 16)));
            moves.add(new FillSpotChange.MoveBox("box." + i, new PdfRect(320, 300 + 30 * i, 100, 16)));
        }

        PreparedDerivation prepared = service().prepare(WORKSPACE_ID, USER_ID, base(fields, FORM_READING_ID), moves);

        assertEquals(4, prepared.changeKinds().size());
        assertTrue(prepared.fieldDefinitions().stream()
                .allMatch(field -> ((FieldBindingTarget.PageBox) field.binding()).x() == 320));
        assertEquals("Moved 4 fill spots.", prepared.editReason());
        assertEquals(1, rendered.size());
    }

    @Test
    void renamingReusesTheProvenFillAndTakingASpotAwayOnlyDropsItsFieldAndTheRulesAboutIt() {
        PreparedDerivation renamed = service().prepare(WORKSPACE_ID, USER_ID, base(), List.of(
                new FillSpotChange.Rename("email", "Email address")));

        assertEquals("Email address", renamed.fieldDefinitions().get(1).label());
        assertEquals(new FieldBindingTarget.AcroFormField("email"), renamed.fieldDefinitions().get(1).binding());
        assertEquals(baseBaseline, renamed.baseline());
        assertTrue(rendered.isEmpty());
        assertEquals(TemplateKind.PDF, renamed.kind());

        PreparedDerivation removed = service().prepare(WORKSPACE_ID, USER_ID, base(), List.of(new FillSpotChange.Remove("found.name")));

        assertEquals(List.of("email"), removed.fieldDefinitions().stream().map(FieldDefinition::fieldId).toList());
        assertEquals(SOURCE_ARTIFACT_ID, removed.sourceArtifactId());
        assertEquals(FORM_READING_ID, removed.pdfFormExtractionId());
        assertTrue(removed.rules().isEmpty());
        assertTrue(removed.derivationJson().contains("{\"kind\":\"REMOVE\",\"fieldId\":\"found.name\",\"label\":\"Name\",\"docxControl\":null}"),
                removed.derivationJson());
        assertEquals(1, rendered.size());

        assertThrows(FillSpotChangeInvalidException.class, () -> service().prepare(WORKSPACE_ID, USER_ID, base(), List.of(
                new FillSpotChange.Remove("found.name"), new FillSpotChange.Remove("email"))));
    }

    @Test
    void aBoxThatCannotGoWhereItWasPutIsRefusedWithTheReasonBeforeAnythingIsFilled() {
        assertEquals(FillSpotPlacementException.Reason.OFF_PAGE, placementReason(addAt(1, new PdfRect(580, 100, 60, 16))));
        assertEquals(FillSpotPlacementException.Reason.OFF_PAGE, placementReason(addAt(3, new PdfRect(100, 100, 60, 16))));
        assertEquals(FillSpotPlacementException.Reason.TOO_SMALL, placementReason(addAt(1, new PdfRect(300, 300, 5, 5))));
        assertEquals(FillSpotPlacementException.Reason.NOT_FILLABLE, placementReason(addAt(2, new PdfRect(100, 100, 60, 16))));
        // Over the found box, and over the form's own field: the box put there is the one reported.
        FillSpotPlacementException over = assertThrows(FillSpotPlacementException.class, () -> service().prepare(
                WORKSPACE_ID, USER_ID, base(), List.of(addAt(1, new PdfRect(145, 88, 200, 16)))));
        assertEquals(FillSpotPlacementException.Reason.OVERLAPS, over.reason());
        assertEquals("The box for Company would cover another fill spot. Move it, or make it smaller.", over.getMessage());
        assertEquals(FillSpotPlacementException.Reason.OVERLAPS,
                placementReason(new FillSpotChange.MoveBox("found.name", new PdfRect(300, 198, 150, 18))));
        assertTrue(rendered.isEmpty());
    }

    @Test
    void theFormsOwnFieldsStayWhereTheyAreAndChangesForAWordFormAreRefusedInWords() {
        FillSpotChangeInvalidException move = assertThrows(FillSpotChangeInvalidException.class, () -> service().prepare(
                WORKSPACE_ID, USER_ID, base(), List.of(new FillSpotChange.MoveBox("email", new PdfRect(300, 250, 150, 18)))));
        assertEquals("This box belongs to the PDF's own form; Brownie cannot move it.", move.getMessage());
        assertInvalid(new FillSpotChange.RestyleBox("email", 9.0, null, null));

        DocxAnchor anchor = new DocxAnchor(
                DocumentPartKind.MAIN_DOCUMENT, "p0", AnchorPlacement.AT, 0, 0, DocxAnchor.hashOf("Company:"), "graph-test", null);
        FillSpotChangeInvalidException word = assertThrows(FillSpotChangeInvalidException.class, () -> service().prepare(
                WORKSPACE_ID, USER_ID, base(), List.of(new FillSpotChange.Add(anchor, "Company", FieldType.TEXT, false))));
        assertEquals(TemplateDerivationService.WORD_CHANGE_ON_PDF, word.getMessage());
        TemplateVersion wordForm = new TemplateVersion(30L, WORKSPACE_ID, 11L, 1, SOURCE_ARTIFACT_ID, 31L, TemplateVersionStatus.ACTIVATED,
                List.of(), null, null);
        FillSpotChangeInvalidException box = assertThrows(FillSpotChangeInvalidException.class, () -> TemplateDerivationService
                .requireChangesFit(wordForm, List.of(new FillSpotChange.MoveBox("any", new PdfRect(0, 0, 10, 10)))));
        assertEquals(TemplateDerivationService.PDF_CHANGE_ON_WORD, box.getMessage());
        assertTrue(rendered.isEmpty());
    }

    @Test
    void requestsThatCannotBeMadeAsAskedAreRefusedInWords() {
        assertInvalid(new FillSpotChange.RestyleBox("found.name", null, null, null));
        assertInvalid(new FillSpotChange.RestyleBox("found.name", 100.0, null, null));
        assertInvalid(new FillSpotChange.RestyleBox("found.name", Double.NaN, null, null));
        assertInvalid(new FillSpotChange.AddBox(1, new PdfRect(120, 128, 180, 16), "Company", FieldType.TEXT,
                new PdfTextStyle(PdfFontFamily.SANS, false, 2), false, null, false));
        assertInvalid(new FillSpotChange.AddBox(0, new PdfRect(120, 128, 180, 16), "Company", FieldType.TEXT, null, false, null, false));
        assertInvalid(new FillSpotChange.AddBox(1, new PdfRect(120, 128, 180, 16), "\n", FieldType.TEXT, null, false, null, false));
        assertInvalid(new FillSpotChange.AddBox(1, new PdfRect(120, 128, 180, 16), "name", FieldType.TEXT, null, false, null, false));
        assertInvalid(new FillSpotChange.MoveBox("unknown", new PdfRect(0, 0, 10, 10)));
        assertInvalid(new FillSpotChange.MoveBox("found.name", new PdfRect(150, 86, 220, 18)), new FillSpotChange.MoveBox(
                "found.name", new PdfRect(160, 86, 220, 18)));
        assertInvalid(new FillSpotChange.MoveBox("found.name", new PdfRect(150, 86, 220, 18)), new FillSpotChange.Remove("found.name"));
        assertTrue(rendered.isEmpty());
    }

    @Test
    void aBoxOnAScanThatHasNoSpotsYetGetsTheOrdinaryLookMadeToFit() {
        foundBoxRule = false;
        PreparedDerivation prepared = service().prepare(WORKSPACE_ID, USER_ID, base(List.of(), SCAN_READING_ID), List.of(
                new FillSpotChange.AddBox(1, new PdfRect(100, 100, 200, 12), "Reference", FieldType.TEXT, null, false, null, false)));

        FieldBindingTarget.PageBox binding = (FieldBindingTarget.PageBox) prepared.fieldDefinitions().getFirst().binding();
        // Eleven points would not fit a box twelve points tall with room above and below; four fifths of it does.
        assertEquals(new PdfTextStyle(PdfFontFamily.SANS, false, 9.5), binding.style());
        assertEquals(SCAN_READING_ID, prepared.pdfFormExtractionId());
    }

    @Test
    void aProvenFillThatFailsMakesNoVersion() {
        failedFieldIds = List.of("company");

        FillSpotBaselineFailedException failed = assertThrows(FillSpotBaselineFailedException.class, () -> service().prepare(
                WORKSPACE_ID, USER_ID, base(), List.of(addAt(1, new PdfRect(120, 128, 180, 16)))));
        assertEquals(List.of("company"), failed.failedFieldIds());
    }

    @Test
    void theKeyIsTheSameForTheSameBoxesAndDiffersWhenABoxOrItsLookDiffers() {
        TemplateDerivationService service = service();
        PdfRect box = new PdfRect(120, 128, 180, 16);
        String key = service.derivationKey(12L, List.of(new FillSpotChange.AddBox(1, box, "Company", FieldType.TEXT, null, false, null, false)));

        assertEquals(key, service.derivationKey(12L, List.of(new FillSpotChange.AddBox(
                1, new PdfRect(120.0, 128.0, 180.0, 16.0), " Company\t", FieldType.TEXT, null, false, PdfOverflowPolicy.SHRINK_TO_FIT, false))));
        assertTrue(key.matches("[0-9a-f]{64}"));
        assertNotEquals(key, service.derivationKey(12L, List.of(new FillSpotChange.AddBox(
                1, new PdfRect(120.5, 128, 180, 16), "Company", FieldType.TEXT, null, false, null, false))));
        assertNotEquals(key, service.derivationKey(12L, List.of(new FillSpotChange.AddBox(
                1, box, "Company", FieldType.TEXT, BOX_STYLE, false, null, false))));
        assertNotEquals(key, service.derivationKey(12L, List.of(new FillSpotChange.AddBox(
                1, box, "Company", FieldType.TEXT, null, false, PdfOverflowPolicy.BLOCK, false))));
        assertNotEquals(key, service.derivationKey(12L, List.of(new FillSpotChange.AddBox(
                2, box, "Company", FieldType.TEXT, null, false, null, false))));
        String move = service.derivationKey(12L, List.of(new FillSpotChange.MoveBox("found.name", box)));
        assertEquals(move, service.derivationKey(12L, List.of(new FillSpotChange.MoveBox("found.name", new PdfRect(120, 128, 180, 16)))));
        assertNotEquals(move, service.derivationKey(12L, List.of(new FillSpotChange.RestyleBox("found.name", 9.0, null, null))));
        assertFalse(service.derivationKey(12L, List.of(new FillSpotChange.RestyleBox("found.name", 9.0, null, null)))
                .equals(service.derivationKey(12L, List.of(new FillSpotChange.RestyleBox("found.name", 9.0, null, true)))));
    }

    private FillSpotPlacementException.Reason placementReason(FillSpotChange change) {
        return assertThrows(FillSpotPlacementException.class, () -> service().prepare(WORKSPACE_ID, USER_ID, base(), List.of(change)))
                .reason();
    }

    private void assertInvalid(FillSpotChange... changes) {
        assertThrows(FillSpotChangeInvalidException.class, () -> service().prepare(WORKSPACE_ID, USER_ID, base(), List.of(changes)));
    }

    private static FillSpotChange.AddBox addAt(int page, PdfRect box) {
        return new FillSpotChange.AddBox(page, box, "Company", FieldType.TEXT, null, false, null, false);
    }

    /** Version 3 of template 11 (id 12): the box Brownie found beside "Full name:", then the form's own email field. */
    private static TemplateVersion base() {
        return base(List.of(
                boxField("found.name", "Name", new PdfRect(140, 88, 200, 16)),
                new FieldDefinition("email", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                        new FieldBindingTarget.AcroFormField("email"))), FORM_READING_ID);
    }

    private static TemplateVersion base(List<FieldDefinition> fields, long formReadingId) {
        return new TemplateVersion(12L, WORKSPACE_ID, 11L, 3, SOURCE_ARTIFACT_ID, TemplateKind.PDF, null, formReadingId,
                TemplateVersionStatus.ACTIVATED, fields, OffsetDateTime.parse("2026-09-01T00:00:00Z"),
                OffsetDateTime.parse("2026-09-01T00:00:01Z"));
    }

    private static FieldDefinition boxField(String id, String label, PdfRect box) {
        return new FieldDefinition(id, FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.PageBox(1, box.x(), box.y(), box.width(), box.height(), BOX_STYLE, false,
                        PdfOverflowPolicy.SHRINK_TO_FIT),
                label, SpotOrigin.FOUND_BY_BROWNIE, null, null);
    }

    /**
     * A US Letter page with "Full name:" in 12 pt and "Company:" and
     * "Email:" in 10 pt, the email field's box beside its label, and a second
     * page measured in a unit other than points, which boxes are not drawn
     * on. The scan is one page with no words at all.
     */
    private static PdfFormGraph formReading() {
        PdfFormGraph.CropBox letter = new PdfFormGraph.CropBox(0, 0, 612, 792);
        PdfFormGraph.Page first = new PdfFormGraph.Page(1, letter, 0, 1, true, List.of(
                line(0, 72, 100, 12, "Full", "name:"),
                line(1, 72, 140, 10, "Company:"),
                line(2, 72, 210, 10, "Email:")), List.of(), List.of(), List.of());
        PdfFormGraph.Page second = new PdfFormGraph.Page(2, letter, 0, 2, false, List.of(), List.of(), List.of(), List.of());
        PdfFormGraph.Field email = new PdfFormGraph.Field("email", PdfFormGraph.FieldKind.TEXT, false, false, false, false, null, null, null,
                List.of(new PdfFormGraph.Widget(1, new PdfRect(300, 198, 150, 18))));
        return new PdfFormGraph("pdf-form-test", List.of(first, second),
                new PdfFormGraph.AcroForm(true, PdfFormGraph.XfaKind.NONE, false, List.of(email), 0), new PdfFormGraph.Risks(false, false, false));
    }

    private static PdfFormGraph scanReading() {
        PdfFormGraph.Page page = new PdfFormGraph.Page(1, new PdfFormGraph.CropBox(0, 0, 612, 792), 0, 1, false, List.of(), List.of(),
                List.of(), List.of(new PdfFormGraph.Image(new PdfRect(0, 0, 612, 792), List.of("DCTDecode"))));
        return new PdfFormGraph("pdf-form-test", List.of(page), PdfFormGraph.AcroForm.absent(), new PdfFormGraph.Risks(false, false, false));
    }

    /** Words at a baseline in Helvetica, each as wide as half its size per letter, a space of the same between them. */
    private static PdfFormGraph.Line line(int index, double x, double baseline, double size, String... texts) {
        List<PdfFormGraph.Word> words = new ArrayList<>();
        double at = x;
        for (String text : texts) {
            double width = text.length() * size / 2;
            words.add(new PdfFormGraph.Word(text, new PdfRect(at, baseline - 0.7 * size, width, 0.7 * size), "Helvetica", size, 0));
            at += width + size / 2;
        }
        PdfRect box = words.getFirst().box();
        for (PdfFormGraph.Word word : words) {
            box = box.union(word.box());
        }
        return new PdfFormGraph.Line(index, String.join(" ", texts), box, words);
    }

    private TemplateDerivationService service() {
        DocxStructuralExtractor extractor = new DocxStructuralExtractor() {
            @Override
            public String parserVersion() {
                return "graph-test";
            }

            @Override
            public DocxExtractionOutcome extract(InputStream content) {
                throw new UnsupportedOperationException();
            }
        };
        TemplateBaselineRenderRepository baselines = new TemplateBaselineRenderRepository() {
            @Override
            public void recordBaselineRender(long workspaceId, long userId, long templateVersionId, BaselineRenderResult result) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<BaselineRenderResult> findBaselineRender(long workspaceId, long userId, long templateVersionId) {
                return templateVersionId == 12L ? Optional.of(baseBaseline) : Optional.empty();
            }
        };
        TemplateBaselineRenderer renderer = (workspaceId, userId, candidate) -> {
            rendered.add(candidate);
            return new BaselineRenderResult(null, 41L, "test fill", failedFieldIds);
        };
        // Nothing about a PDF form reads or edits a Word file, so those parts are left out.
        return new TemplateDerivationService(
                new IdsEverUsed(), new FoundBoxRules(), null, null, null, extractor, null, renderer, baselines, new FormReadings());
    }

    private final class IdsEverUsed implements TemplateLineageRepository {
        @Override
        public Set<String> findFieldIdsEverUsed(long workspaceId, long userId, long templateId) {
            return idsEverUsed;
        }

        @Override
        public Optional<TemplateVersion> findDerived(long workspaceId, long userId, long templateId, long baseVersionId, String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long lockTemplate(long workspaceId, long userId, long templateId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TemplateVersion insertDerived(long workspaceId, long userId, PreparedDerivation prepared) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void advanceToDerived(long workspaceId, long userId, long templateId, long baseVersionId, long derivedVersionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> findChangedFieldIds(long workspaceId, long userId, long templateVersionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int countLiveDocumentsOn(long workspaceId, long userId, long templateVersionId, long exceptDocumentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean stepCurrentVersion(long workspaceId, long userId, long templateId, long from, long to, long documentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordDocumentVersionChanged(long workspaceId, long userId, long documentId, long from, long to, long revisionId) {
            throw new UnsupportedOperationException();
        }
    }

    /** One accepted rule, about the box Brownie found, when the base has that box. */
    private final class FoundBoxRules implements RuleRepository {

        @Override
        public List<RuleRevision> findByTemplateVersion(long workspaceId, long userId, long templateVersionId) {
            if (!foundBoxRule) {
                return List.of();
            }
            RulePayload payload = new RulePayload.MaxTextLength("found.name", 40);
            return List.of(new RuleRevision(1L, WORKSPACE_ID, 11L, 12L, payload.category(), new RuleScope.SingleField("found.name"), payload,
                    "rules-v1", RuleRevisionStatus.ACCEPTED, null, USER_ID, OffsetDateTime.now()));
        }

        @Override
        public RuleRevision propose(long w, long u, long t, long v, RuleScope s, RulePayload p, String sv, String e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RuleRevision> find(long workspaceId, long userId, long templateId, long ruleId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordProposalEvidence(long workspaceId, long userId, long ruleId, RuleProposalEvidence evidence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RuleProposalEvidence> findProposalEvidence(long workspaceId, long userId, long ruleId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuleRevision decide(long workspaceId, long userId, long templateId, long ruleId, RuleRevisionStatus decision) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FormReadings implements PdfFormExtractionVersionRepository {

        @Override
        public Optional<PdfFormExtractionVersion> findById(long workspaceId, long userId, long id) {
            PdfFormGraph graph = id == FORM_READING_ID ? formReading() : id == SCAN_READING_ID ? scanReading() : null;
            return Optional.ofNullable(graph).map(reading -> new PdfFormExtractionVersion(
                    id, WORKSPACE_ID, SOURCE_ARTIFACT_ID, reading.parserVersion(), ExtractionStatus.COMPLETE, null, null, reading,
                    OffsetDateTime.now()));
        }

        @Override
        public Optional<PdfFormExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Long> findCompleteIdByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PdfFormExtractionVersion saveComplete(long workspaceId, long userId, long artifactId, String parserVersion, PdfFormGraph graph) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PdfFormExtractionVersion saveUnsupported(
                long workspaceId, long userId, long artifactId, String parserVersion, UnsupportedPdfFormReason reason, String detail) {
            throw new UnsupportedOperationException();
        }
    }
}
