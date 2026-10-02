package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxFeatureReport;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.prepare.AnchorPlacement;
import io.github.vihuynh72.brownie.core.prepare.BlankLines;
import io.github.vihuynh72.brownie.core.prepare.DocxAnchor;
import io.github.vihuynh72.brownie.core.prepare.EditedDocx;
import io.github.vihuynh72.brownie.core.prepare.FillSpotEditor;
import io.github.vihuynh72.brownie.core.prepare.FillSpotPlacementException;
import io.github.vihuynh72.brownie.core.prepare.SpotEdit;
import io.github.vihuynh72.brownie.core.rule.DateFormatStyle;
import io.github.vihuynh72.brownie.core.rule.RuleCategory;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleProposalEvidence;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises deriving a version against hand-built graphs, with a fake Word
 * editor whose "file" is the graph the edits describe: what matters here is
 * which edits are asked for, what the new field list and rules are, what is
 * refused, and that the key finds the same request again.
 */
class TemplateDerivationServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 7L;
    private static final String PARSER = "graph-test-v3";
    private static final long BASE_ARTIFACT_ID = 20L;
    private static final long BASE_EXTRACTION_ID = 21L;
    private static final long EDITED_ARTIFACT_ID = 30L;
    private static final long EDITED_EXTRACTION_ID = 31L;
    private static final String COMPANY_LINE = "Company: ________";

    private final Fakes fakes = new Fakes();

    @Test
    void aRenameChangesOnlyTheLabelAndReusesTheFileTheExtractionAndTheProvenRender() {
        PreparedDerivation prepared = fakes.service().prepare(
                WORKSPACE_ID, USER_ID, base(), List.of(new FillSpotChange.Rename("meeting.title", "  Meeting   name ")));

        FieldDefinition renamed = prepared.fieldDefinitions().getFirst();
        assertEquals("meeting.title", renamed.fieldId());
        assertEquals("Meeting name", renamed.label());
        assertNull(renamed.origin());
        assertEquals(BASE_ARTIFACT_ID, prepared.sourceArtifactId());
        assertEquals(BASE_EXTRACTION_ID, prepared.extractionVersionId());
        assertEquals(fakes.baseBaseline, prepared.baseline());
        assertTrue(fakes.editor.edits.isEmpty());
        assertTrue(fakes.renderer.candidates.isEmpty());
        assertEquals(List.of("meeting.title"), prepared.changedFieldIds());
        assertEquals(List.of("RENAME"), prepared.changeKinds());
        assertEquals("Renamed the fill spot Meeting title to Meeting name.", prepared.editReason());
        assertTrue(prepared.derivationJson().contains("\"fromLabel\":\"Meeting title\""), prepared.derivationJson());
        // Every rule still in play comes along with its status; the rejected one does not.
        assertEquals(List.of(1L, 2L, 3L, 4L), prepared.rules().stream().map(RuleRevision::id).toList());
        assertEquals(RuleRevisionStatus.PROPOSED, prepared.rules().get(1).status());
    }

    @Test
    void theKeyIsTheSameForTheSameRequestAndDiffersForAnotherBaseOrChange() {
        TemplateDerivationService service = fakes.service();
        String key = service.derivationKey(12L, List.of(new FillSpotChange.Rename("meeting.title", "Meeting name")));

        assertEquals(key, service.derivationKey(12L, List.of(new FillSpotChange.Rename("meeting.title", " Meeting\tname "))));
        assertTrue(key.matches("[0-9a-f]{64}"));
        assertNotEquals(key, service.derivationKey(13L, List.of(new FillSpotChange.Rename("meeting.title", "Meeting name"))));
        assertNotEquals(key, service.derivationKey(12L, List.of(new FillSpotChange.Rename("meeting.title", "Meeting title"))));
        assertNotEquals(key, service.derivationKey(12L, List.of(new FillSpotChange.Remove("meeting.title"))));
    }

    @Test
    void addingOverABlankMakesANewIdNoVersionUsedAndPutsTheFieldInReadingOrder() {
        fakes.idsEverUsed = Set.of("company");
        DocxAnchor anchor = new DocxAnchor(
                DocumentPartKind.MAIN_DOCUMENT, "p0", AnchorPlacement.REPLACE, 9, 17, DocxAnchor.hashOf(COMPANY_LINE), PARSER, null);

        PreparedDerivation prepared = fakes.service().prepare(
                WORKSPACE_ID, USER_ID, base(), List.of(new FillSpotChange.Add(anchor, "Company", FieldType.TEXT, false)));

        assertEquals(List.of(new SpotEdit.Insert(anchor, "company.2", "Company", "________")), fakes.editor.edits);
        FieldDefinition added = prepared.fieldDefinitions().getFirst();
        assertEquals(new FieldDefinition(
                "company.2", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.ContentControlTag("company.2"), "Company", SpotOrigin.ADDED_BY_PERSON,
                DocxControlOrigin.INSERTED_BY_BROWNIE, "________"), added);
        assertEquals(List.of("company.2", "meeting.title", "found.name", "tagged.date", "action.items"),
                prepared.fieldDefinitions().stream().map(FieldDefinition::fieldId).toList());
        assertEquals(EDITED_ARTIFACT_ID, prepared.sourceArtifactId());
        assertEquals(EDITED_EXTRACTION_ID, prepared.extractionVersionId());
        assertEquals("form.docx", fakes.artifacts.storedFilename);
        assertEquals(1, fakes.renderer.candidates.size());
        assertEquals(12L, fakes.renderer.candidates.getFirst().derivedFromVersionId());
        assertEquals("Added a fill spot: Company.", prepared.editReason());
        assertEquals(List.of("company.2"), prepared.changedFieldIds());
    }

    @Test
    void aSpotTheModelPlacedShowsAsFoundAndOneAtTheEndOfAWordyLineKeepsNoBlank() {
        DocxAnchor anchor = new DocxAnchor(
                DocumentPartKind.MAIN_DOCUMENT, "p0", AnchorPlacement.WHOLE_LINE, 0, 17, DocxAnchor.hashOf(COMPANY_LINE), PARSER, null);

        PreparedDerivation prepared = fakes.service().prepare(
                WORKSPACE_ID, USER_ID, base(), List.of(new FillSpotChange.Add(anchor, "Company", FieldType.TEXT, true)));

        FieldDefinition added = prepared.fieldDefinitions().getFirst();
        assertEquals(SpotOrigin.FOUND_BY_BROWNIE, added.origin());
        assertNull(added.blankText());
        assertTrue(TemplateDerivationService.isOnlyABlankLine("  ________ "));
        assertTrue(TemplateDerivationService.isOnlyABlankLine("[Company name]"));
        assertFalse(TemplateDerivationService.isOnlyABlankLine(COMPANY_LINE));
        assertFalse(TemplateDerivationService.isOnlyABlankLine("   "));
        // The same lines the Word editor replaces: dashes, fullwidth underscores, and every kind of bracketed prompt.
        for (String line : List.of("----------", "\uFF3F\uFF3F\uFF3F\uFF3F", "<<Name>>", "{Company}", "\u00ABName\u00BB")) {
            assertTrue(TemplateDerivationService.isOnlyABlankLine(line), line);
            assertTrue(BlankLines.isOnlyABlank(line), line);
        }
        assertFalse(BlankLines.isOnlyABlank(COMPANY_LINE));
    }

    @Test
    void aSpotOverAWholeLineWithWordsReadsAtTheLineEndWhereTheEditorPutsIt() {
        DocxAnchor anchor = new DocxAnchor(
                DocumentPartKind.MAIN_DOCUMENT, "p2", AnchorPlacement.WHOLE_LINE, 0, 5, DocxAnchor.hashOf("Name "), PARSER, null);

        PreparedDerivation prepared = fakes.service().prepare(
                WORKSPACE_ID, USER_ID, base(), List.of(new FillSpotChange.Add(anchor, "Nickname", FieldType.TEXT, false)));

        // After the line's own spot, which sits at its end, and before the next line's.
        assertEquals(List.of("meeting.title", "found.name", "nickname", "tagged.date", "action.items"),
                prepared.fieldDefinitions().stream().map(FieldDefinition::fieldId).toList());
    }

    @Test
    void removingASpotUndoesWhatBrownieDidToTheFileAndRewritesTheRulesAboutIt() {
        PreparedDerivation prepared = fakes.service().prepare(WORKSPACE_ID, USER_ID, base(), List.of(
                new FillSpotChange.Remove("found.name"),
                new FillSpotChange.Remove("tagged.date"),
                new FillSpotChange.Remove("meeting.title")));

        // The form's own control stays as it was; only its field goes.
        assertEquals(List.of(new SpotEdit.Unwrap("found.name"), new SpotEdit.Untag("tagged.date")), fakes.editor.edits);
        assertEquals(List.of("action.items"), prepared.fieldDefinitions().stream().map(FieldDefinition::fieldId).toList());
        // Required fields lose the removed ones and go when none is left; rules about a removed spot go; the rest stay.
        assertEquals(List.of(4L), prepared.rules().stream().map(RuleRevision::id).toList());
        assertEquals("Removed the fill spot Found name. Removed the fill spot Tagged date. Removed the fill spot Meeting title.",
                prepared.editReason());
        assertEquals(1, fakes.renderer.candidates.size());
    }

    @Test
    void removingOnlyAFormsOwnSpotLeavesTheFileAloneButProvesTheRenderAgain() {
        PreparedDerivation prepared = fakes.service().prepare(
                WORKSPACE_ID, USER_ID, base(), List.of(new FillSpotChange.Remove("meeting.title")));

        assertTrue(fakes.editor.edits.isEmpty());
        assertEquals(BASE_ARTIFACT_ID, prepared.sourceArtifactId());
        assertEquals(1, fakes.renderer.candidates.size());
        RuleRevision required = prepared.rules().getFirst();
        assertEquals(new RulePayload.RequiredFields(List.of("found.name")), required.payload());
        assertEquals(RuleRevisionStatus.ACCEPTED, required.status());
    }

    @Test
    void aPlaceInsideAProtectedTableOrAParagraphAFieldIsBoundToByNodeIsRefused() {
        DocxAnchor inTable = new DocxAnchor(DocumentPartKind.MAIN_DOCUMENT, "tbl4/row0/cell0/p0", AnchorPlacement.AT, 7, 7,
                DocxAnchor.hashOf("Notes: "), PARSER, null);

        FillSpotPlacementException refused = assertThrows(FillSpotPlacementException.class, () -> fakes.service().prepare(
                WORKSPACE_ID, USER_ID, base(), List.of(new FillSpotChange.Add(inTable, "Notes", FieldType.TEXT, false))));

        assertEquals(FillSpotPlacementException.Reason.PROTECTED, refused.reason());
        assertTrue(fakes.editor.edits.isEmpty());
    }

    @Test
    void aPlaceThatChangedOrCannotTakeASpotIsRefusedBeforeTheFileIsTouched() {
        TemplateDerivationService service = fakes.service();
        DocxAnchor staleHash = new DocxAnchor(
                DocumentPartKind.MAIN_DOCUMENT, "p0", AnchorPlacement.AT, 9, 9, DocxAnchor.hashOf("Company:"), PARSER, null);
        DocxAnchor olderReader = new DocxAnchor(
                DocumentPartKind.MAIN_DOCUMENT, "p0", AnchorPlacement.AT, 9, 9, DocxAnchor.hashOf(COMPANY_LINE), "graph-v2", null);
        DocxAnchor pastTheEnd = new DocxAnchor(
                DocumentPartKind.MAIN_DOCUMENT, "p0", AnchorPlacement.AT, 18, 18, DocxAnchor.hashOf(COMPANY_LINE), PARSER, null);
        DocxAnchor header = new DocxAnchor(DocumentPartKind.HEADER, "p0", AnchorPlacement.AT, 0, 0, DocxAnchor.hashOf(""), PARSER, null);
        DocxAnchor repeating = new DocxAnchor(
                DocumentPartKind.MAIN_DOCUMENT, "p5", AnchorPlacement.AT, 0, 0, DocxAnchor.hashOf("Item: "), PARSER, null);

        assertEquals(FillSpotPlacementException.Reason.ANCHOR_STALE, placementReason(service, staleHash));
        assertEquals(FillSpotPlacementException.Reason.ANCHOR_STALE, placementReason(service, olderReader));
        assertEquals(FillSpotPlacementException.Reason.ANCHOR_STALE, placementReason(service, pastTheEnd));
        assertEquals(FillSpotPlacementException.Reason.HEADER_FOOTER, placementReason(service, header));
        assertEquals(FillSpotPlacementException.Reason.REPEATING_REGION, placementReason(service, repeating));
        assertTrue(fakes.editor.edits.isEmpty());
    }

    @Test
    void requestsThatCannotBeMadeAsAskedAreRefusedInWords() {
        TemplateDerivationService service = fakes.service();
        List<FillSpotChange> tooMany = IntStream.range(0, TemplateDerivationService.MAX_CHANGES + 1)
                .<FillSpotChange>mapToObj(i -> new FillSpotChange.Rename("meeting.title", "Name " + i))
                .toList();

        assertInvalid(service, List.of());
        assertInvalid(service, tooMany);
        assertInvalid(service, List.of(new FillSpotChange.Rename("meeting.title", "A"), new FillSpotChange.Remove("meeting.title")));
        assertInvalid(service, List.of(new FillSpotChange.Rename("meeting.title", "\n")));
        assertInvalid(service, List.of(new FillSpotChange.Rename("unknown", "Anything")));
        assertInvalid(service, List.of(new FillSpotChange.Remove("action.items")));
        assertInvalid(service, List.of(new FillSpotChange.Rename("meeting.title", "found NAME")));
    }

    @Test
    void aFormThatAlreadyHasAsManySpotsAsAFormCanHoldTakesNoMoreAndItsFileIsNotTouched() {
        TemplateVersion small = base();
        List<FieldDefinition> fields = new ArrayList<>(small.fieldDefinitions());
        for (int i = 0; fields.size() < TemplateDerivationService.MAX_SPOTS; i++) {
            fields.add(field("extra." + i, FieldType.TEXT, FieldCardinality.SCALAR, null, null));
        }
        TemplateVersion full = new TemplateVersion(small.id(), small.workspaceId(), small.templateId(), small.versionNumber(),
                small.sourceArtifactId(), small.extractionVersionId(), small.status(), fields, small.createdAt(), small.activatedAt());
        DocxAnchor anchor = new DocxAnchor(
                DocumentPartKind.MAIN_DOCUMENT, "p0", AnchorPlacement.AT, 9, 9, DocxAnchor.hashOf(COMPANY_LINE), PARSER, null);

        FillSpotChangeInvalidException refused = assertThrows(FillSpotChangeInvalidException.class, () -> fakes.service().prepare(
                WORKSPACE_ID, USER_ID, full, List.of(new FillSpotChange.Add(anchor, "Company", FieldType.TEXT, false))));
        assertTrue(refused.getMessage().contains(String.valueOf(TemplateDerivationService.MAX_SPOTS)));
        assertTrue(fakes.editor.edits.isEmpty());
    }

    @Test
    void aFormThatWouldNotPrintCorrectlyOrASpotTheFillerCannotReachIsNotMade() {
        fakes.renderer.failedFieldIds = List.of("company");
        DocxAnchor anchor = new DocxAnchor(
                DocumentPartKind.MAIN_DOCUMENT, "p0", AnchorPlacement.AT, 9, 9, DocxAnchor.hashOf(COMPANY_LINE), PARSER, null);
        List<FillSpotChange> add = List.of(new FillSpotChange.Add(anchor, "Company", FieldType.TEXT, false));

        FillSpotBaselineFailedException failed = assertThrows(FillSpotBaselineFailedException.class,
                () -> fakes.service().prepare(WORKSPACE_ID, USER_ID, base(), add));
        assertEquals(List.of("company"), failed.failedFieldIds());

        Fakes lost = new Fakes();
        lost.editor.insertsIntoHeader = true;
        FillSpotNotPlacedException notPlaced = assertThrows(FillSpotNotPlacedException.class,
                () -> lost.service().prepare(WORKSPACE_ID, USER_ID, base(), add));
        assertEquals("company", notPlaced.fieldId());
    }

    private FillSpotPlacementException.Reason placementReason(TemplateDerivationService service, DocxAnchor anchor) {
        return assertThrows(FillSpotPlacementException.class, () -> service.prepare(
                WORKSPACE_ID, USER_ID, base(), List.of(new FillSpotChange.Add(anchor, "Company", FieldType.TEXT, false)))).reason();
    }

    private static void assertInvalid(TemplateDerivationService service, List<FillSpotChange> changes) {
        assertThrows(FillSpotChangeInvalidException.class, () -> service.prepare(WORKSPACE_ID, USER_ID, base(), changes));
    }

    /** Version 3 of template 11 (id 12): a form's own control, one Brownie inserted, one it tagged, and a repeating list. */
    @Test
    void aNewSpotsIdIsNoTagAControlInTheFileAlreadyCarries() {
        // The header's control is no spot (values are written only in the body), but its tag would name the new control too.
        fakes.baseHeader = new StructuralNode[] {control("p0/sdt0", "company", "Acme Ltd")};
        DocxAnchor anchor = new DocxAnchor(
                DocumentPartKind.MAIN_DOCUMENT, "p0", AnchorPlacement.REPLACE, 9, 17, DocxAnchor.hashOf(COMPANY_LINE), PARSER, null);

        PreparedDerivation prepared = fakes.service().prepare(
                WORKSPACE_ID, USER_ID, base(), List.of(new FillSpotChange.Add(anchor, "Company", FieldType.TEXT, false)));

        assertEquals(List.of(new SpotEdit.Insert(anchor, "company.2", "Company", "________")), fakes.editor.edits);
        assertEquals(List.of("company.2"), prepared.changedFieldIds());
    }

    private static TemplateVersion base() {
        return new TemplateVersion(12L, WORKSPACE_ID, 11L, 3, BASE_ARTIFACT_ID, BASE_EXTRACTION_ID, TemplateVersionStatus.ACTIVATED,
                List.of(
                        field("meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, null, null),
                        field("found.name", FieldType.TEXT, FieldCardinality.SCALAR, SpotOrigin.FOUND_BY_BROWNIE,
                                DocxControlOrigin.INSERTED_BY_BROWNIE),
                        field("tagged.date", FieldType.DATE, FieldCardinality.SCALAR, SpotOrigin.FOUND_BY_BROWNIE,
                                DocxControlOrigin.TAGGED_BY_BROWNIE),
                        field("action.items", FieldType.TEXT, FieldCardinality.REPEATED, null, null)),
                OffsetDateTime.parse("2026-09-01T00:00:00Z"), OffsetDateTime.parse("2026-09-01T00:00:01Z"));
    }

    private static FieldDefinition field(String id, FieldType type, FieldCardinality cardinality, SpotOrigin origin, DocxControlOrigin control) {
        return new FieldDefinition(id, type, cardinality, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(id),
                null, origin, control, null);
    }

    private static DocxStructuralGraph baseGraph(StructuralNode... headerContent) {
        return new DocxStructuralGraph(PARSER, List.of(
                new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, node("", StructuralNodeKind.BODY, null, null,
                        node("p0", StructuralNodeKind.PARAGRAPH, null, null, run("p0/r0", "Company: "), run("p0/r1", "________")),
                        node("p1", StructuralNodeKind.PARAGRAPH, null, null, control("p1/sdt0", "meeting.title", "[title]")),
                        node("p2", StructuralNodeKind.PARAGRAPH, null, null, run("p2/r0", "Name "), control("p2/sdt1", "found.name", "____")),
                        node("p3", StructuralNodeKind.PARAGRAPH, null, null, control("p3/sdt0", "tagged.date", "[date]")),
                        node("tbl4", StructuralNodeKind.TABLE, null, null,
                                node("tbl4/row0", StructuralNodeKind.TABLE_ROW, null, null,
                                        node("tbl4/row0/cell0", StructuralNodeKind.TABLE_CELL, null, null,
                                                node("tbl4/row0/cell0/p0", StructuralNodeKind.PARAGRAPH, null, null,
                                                        run("tbl4/row0/cell0/p0/r0", "Notes: "))))),
                        node("p5", StructuralNodeKind.PARAGRAPH, null, null, run("p5/r0", "Item: "), control("p5/sdt1", "action.items", "[item]")))),
                new DocumentPart("word/header1.xml", DocumentPartKind.HEADER, node("", StructuralNodeKind.BODY, null, null,
                        node("p0", StructuralNodeKind.PARAGRAPH, null, null, headerContent)))));
    }

    private static StructuralNode run(String id, String text) {
        return node(id, StructuralNodeKind.RUN, text, null);
    }

    private static StructuralNode control(String id, String tag, String text) {
        return node(id, StructuralNodeKind.CONTENT_CONTROL, null, tag, run(id + "/r0", text));
    }

    private static StructuralNode node(String id, StructuralNodeKind kind, String text, String tag, StructuralNode... children) {
        return new StructuralNode(id, kind, null, text, tag, null, List.of(children));
    }

    /**
     * The graph a Word file would have after the edits: a new control where
     * an Insert says, a removed spot's runs back in its paragraph, a tag
     * taken off. Only as much of an editor as the checks after it need.
     */
    private static StructuralNode edited(StructuralNode node, List<SpotEdit> edits, boolean dropInserted) {
        List<StructuralNode> children = new ArrayList<>();
        for (StructuralNode child : node.children()) {
            if (child.kind() == StructuralNodeKind.CONTENT_CONTROL
                    && edits.stream().anyMatch(edit -> edit instanceof SpotEdit.Unwrap(String tag) && tag.equals(child.contentControlTag()))) {
                children.addAll(child.children());
            } else if (child.kind() == StructuralNodeKind.CONTENT_CONTROL
                    && edits.stream().anyMatch(edit -> edit instanceof SpotEdit.Untag(String tag) && tag.equals(child.contentControlTag()))) {
                children.add(new StructuralNode(child.nodeId(), child.kind(), null, null, null, null, child.children()));
            } else {
                children.add(edited(child, edits, dropInserted));
            }
        }
        for (SpotEdit edit : edits) {
            if (!dropInserted && edit instanceof SpotEdit.Insert insert && node.nodeId().equals(insert.anchor().paragraphNodeId())) {
                children.add(control(node.nodeId() + "/sdt9", insert.tag(), ""));
            }
        }
        return new StructuralNode(node.nodeId(), node.kind(), node.style(), node.text(), node.contentControlTag(), node.imageRelationshipId(),
                children);
    }

    private static final class Fakes {

        Set<String> idsEverUsed = Set.of();
        StructuralNode[] baseHeader = {};
        final RecordingEditor editor = new RecordingEditor();
        final RecordingRenderer renderer = new RecordingRenderer();
        final StoringArtifacts artifacts = new StoringArtifacts();
        final BaselineRenderResult baseBaseline = new BaselineRenderResult(50L, 51L, "base render", List.of());

        TemplateDerivationService service() {
            DocxStructuralExtractor extractor = new DocxStructuralExtractor() {
                @Override
                public String parserVersion() {
                    return PARSER;
                }

                @Override
                public DocxExtractionOutcome extract(InputStream content) {
                    throw new UnsupportedOperationException();
                }
            };
            DocumentExtractionService extraction = new DocumentExtractionService(artifacts, extractor, null, null, null, null, null) {
                @Override
                public ExtractionVersion extractDocx(long workspaceId, long userId, long artifactId) {
                    if (artifactId == BASE_ARTIFACT_ID) {
                        return extractionOf(BASE_EXTRACTION_ID, BASE_ARTIFACT_ID, baseGraph(baseHeader));
                    }
                    DocumentPart main = baseGraph().parts().getFirst();
                    List<StructuralNode> headerControls = new ArrayList<>();
                    if (editor.insertsIntoHeader) {
                        for (SpotEdit edit : editor.edits) {
                            if (edit instanceof SpotEdit.Insert insert) {
                                headerControls.add(control("p0/sdt" + headerControls.size(), insert.tag(), ""));
                            }
                        }
                    }
                    DocxStructuralGraph graph = new DocxStructuralGraph(PARSER, List.of(
                            new DocumentPart(main.partName(), main.kind(), edited(main.root(), editor.edits, editor.insertsIntoHeader)),
                            new DocumentPart("word/header1.xml", DocumentPartKind.HEADER, node("", StructuralNodeKind.BODY, null, null,
                                    node("p0", StructuralNodeKind.PARAGRAPH, null, null, headerControls.toArray(StructuralNode[]::new))))));
                    return extractionOf(EDITED_EXTRACTION_ID, EDITED_ARTIFACT_ID, graph);
                }
            };
            return new TemplateDerivationService(
                    new FakeLineage(), new FakeRules(), new PinnedExtractions(), artifacts, extraction, extractor, editor, renderer,
                    new TemplateBaselineRenderRepository() {
                        @Override
                        public void recordBaselineRender(long workspaceId, long userId, long templateVersionId, BaselineRenderResult result) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public Optional<BaselineRenderResult> findBaselineRender(long workspaceId, long userId, long templateVersionId) {
                            return templateVersionId == 12L ? Optional.of(baseBaseline) : Optional.empty();
                        }
                    },
                    // A Word form is never read as a PDF form.
                    null);
        }

        private static ExtractionVersion extractionOf(long id, long artifactId, DocxStructuralGraph graph) {
            return new ExtractionVersion(id, WORKSPACE_ID, artifactId, PARSER, ExtractionStatus.COMPLETE, DocxFeatureReport.empty(), graph, null,
                    OffsetDateTime.now());
        }

        private final class FakeLineage implements TemplateLineageRepository {
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
    }

    private static final class FakeRules implements RuleRepository {

        @Override
        public List<RuleRevision> findByTemplateVersion(long workspaceId, long userId, long templateVersionId) {
            return List.of(
                    rule(1L, new RuleScope.WholeTemplate(), new RulePayload.RequiredFields(List.of("meeting.title", "found.name")),
                            RuleRevisionStatus.ACCEPTED),
                    rule(2L, new RuleScope.SingleField("found.name"), new RulePayload.MaxTextLength("found.name", 40), RuleRevisionStatus.PROPOSED),
                    rule(3L, new RuleScope.WholeTemplate(), new RulePayload.DateDisplayFormat("tagged.date", DateFormatStyle.LONG),
                            RuleRevisionStatus.ACCEPTED),
                    rule(4L, new RuleScope.WholeTemplate(),
                            new RulePayload.ProtectedRegion(new FieldBindingTarget.StructuralNode(DocumentPartKind.MAIN_DOCUMENT, "tbl4")),
                            RuleRevisionStatus.ACCEPTED),
                    rule(5L, new RuleScope.WholeTemplate(), new RulePayload.RequiredFields(List.of("tagged.date")), RuleRevisionStatus.REJECTED));
        }

        private static RuleRevision rule(long id, RuleScope scope, RulePayload payload, RuleRevisionStatus status) {
            return new RuleRevision(id, WORKSPACE_ID, 11L, 12L, payload.category(), scope, payload, "rules-v1", status, null, USER_ID,
                    OffsetDateTime.now());
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

    private static final class PinnedExtractions implements ExtractionVersionRepository {

        @Override
        public Optional<ExtractionVersion> findById(long workspaceId, long userId, long extractionVersionId) {
            return extractionVersionId == BASE_EXTRACTION_ID
                    ? Optional.of(Fakes.extractionOf(BASE_EXTRACTION_ID, BASE_ARTIFACT_ID, baseGraph()))
                    : Optional.empty();
        }

        @Override
        public Optional<ExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExtractionVersion saveComplete(
                long workspaceId, long userId, long artifactId, String parserVersion, DocxStructuralGraph graph, DocxFeatureReport keptAsIs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExtractionVersion saveUnsupported(long workspaceId, long userId, long artifactId, String parserVersion, DocxFeatureReport report) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExtractionVersion saveFailed(long workspaceId, long userId, long artifactId, String parserVersion, String failureReason) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingEditor implements FillSpotEditor {

        final List<SpotEdit> edits = new ArrayList<>();
        /** Puts every new control in the header instead, where its tag resolves but the filler never writes. */
        boolean insertsIntoHeader;

        @Override
        public EditedDocx apply(byte[] docx, List<SpotEdit> requested) {
            edits.addAll(requested);
            return new EditedDocx("edited".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static final class RecordingRenderer implements TemplateBaselineRenderer {

        final List<TemplateVersion> candidates = new ArrayList<>();
        List<String> failedFieldIds = List.of();

        @Override
        public BaselineRenderResult renderBaseline(long workspaceId, long userId, TemplateVersion draftVersion) {
            candidates.add(draftVersion);
            return new BaselineRenderResult(40L, 41L, "test render", failedFieldIds);
        }
    }

    /** Opens the base's file and stores the edited one, without any storage behind it. */
    private static final class StoringArtifacts extends ArtifactService {

        String storedFilename;

        StoringArtifacts() {
            super(null, null, null, 0, Duration.ZERO);
        }

        @Override
        public ReadableArtifact openContent(long workspaceId, long userId, long artifactId) {
            return new ReadableArtifact(artifact(artifactId), new ByteArrayInputStream(new byte[] {1, 2, 3}));
        }

        @Override
        public Artifact storeGenerated(long workspaceId, long userId, String filename, byte[] bytes) {
            storedFilename = filename;
            return artifact(EDITED_ARTIFACT_ID);
        }

        private static Artifact artifact(long id) {
            return new Artifact(id, WORKSPACE_ID, "key-" + id, ArtifactStatus.READY, 3L, "f".repeat(64), SupportedMediaType.DOCX, "form.docx",
                    null, OffsetDateTime.now(), OffsetDateTime.now());
        }
    }
}
