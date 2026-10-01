package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.template.DocxControlOrigin;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldIds;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plan the rules make from the naming step's decisions: ids, origins, edits, the repeating row, and the words of each label. */
class RulesSpotNamerTest {

    private static final DocxAnchor REPLACE = new DocxAnchor(DocumentPartKind.MAIN_DOCUMENT, "p0", AnchorPlacement.REPLACE, 6, 10,
            DocxAnchor.hashOf("Name: ____"), "v3", null);
    private static final DocxAnchor CONTROL = new DocxAnchor(DocumentPartKind.MAIN_DOCUMENT, "p1", AnchorPlacement.EXISTING_CONTROL, 0, 0,
            DocxAnchor.hashOf(""), "v3", "p1/sdt0");

    @Test
    void everyPlaceTheRulesKeepBecomesAnOptionalFoundFieldWithAnIdMadeFromItsLabel() {
        FoundSpots found = found(
                candidate("c1", SpotCandidate.Kind.UNDERSCORES, "H\u1ECD v\u00E0 t\u00EAn", FieldType.TEXT, "____", false, null),
                candidate("c2", SpotCandidate.Kind.UNDERSCORES, "Signature", FieldType.TEXT, "____", true, null),
                candidate("c3", SpotCandidate.Kind.BRACKET, "Date", FieldType.DATE, "[Date]", false, null));

        SpotPlan plan = RulesSpotNamer.plan(found, rulesOnly(found), Set.of());

        assertEquals(2, plan.spots().size());
        FieldDefinition name = plan.spots().getFirst().field();
        assertEquals("ho.va.ten", name.fieldId());
        assertEquals("H\u1ECD v\u00E0 t\u00EAn", name.label());
        assertEquals(FieldRequiredness.OPTIONAL, name.requiredness());
        assertEquals(SpotOrigin.FOUND_BY_BROWNIE, name.origin());
        assertEquals(DocxControlOrigin.INSERTED_BY_BROWNIE, name.docxControl());
        assertEquals("____", name.blankText());
        assertEquals(new FieldBindingTarget.ContentControlTag("ho.va.ten"), name.binding());
        assertEquals(new SpotEdit.Insert(REPLACE, "ho.va.ten", "H\u1ECD v\u00E0 t\u00EAn", "____"), plan.spots().getFirst().edit());
        assertEquals(NamingSource.RULES, plan.spots().getFirst().namedBy());
        assertEquals(FieldType.DATE, plan.spots().get(1).field().type());
        assertTrue(FieldIds.isSafeId(plan.spots().get(1).field().fieldId()));
    }

    @Test
    void anIdAnyVersionOfTheTemplateUsedIsNeverUsedAgainAndARepeatedLabelIsNumbered() {
        FoundSpots found = found(
                candidate("c1", SpotCandidate.Kind.BRACKET, "Member name", FieldType.TEXT, "[Member name]", false, null),
                candidate("c2", SpotCandidate.Kind.BRACKET, "Member name", FieldType.TEXT, "[Member name]", false, null));

        SpotPlan plan = RulesSpotNamer.plan(found, rulesOnly(found), Set.of("member.name"));

        assertEquals(List.of("member.name.2", "member.name.2.2"), plan.fields().stream().map(FieldDefinition::fieldId).toList());
        assertEquals(List.of("Member name", "Member name 2"), plan.fields().stream().map(FieldDefinition::label).toList());
    }

    @Test
    void theRowThatTellsTableCellsApartIsInTheirLabelsButNotTheirIds() {
        FoundSpots found = found(
                candidate("c1", SpotCandidate.Kind.EMPTY_CELL, "Quantity (row 1)", FieldType.TEXT, null, false, "T2R2"),
                candidate("c2", SpotCandidate.Kind.EMPTY_CELL, "Quantity (row 2)", FieldType.TEXT, null, false, "T2R3"),
                candidate("c3", SpotCandidate.Kind.EMPTY_CELL, "Year (Painting)", FieldType.TEXT, null, false, "T3R2"));

        SpotPlan plan = RulesSpotNamer.plan(found, rulesOnly(found), Set.of());

        assertEquals(List.of("quantity", "quantity.2", "year"), plan.fields().stream().map(FieldDefinition::fieldId).toList());
        assertEquals(List.of("Quantity (row 1)", "Quantity (row 2)", "Year (Painting)"),
                plan.fields().stream().map(FieldDefinition::label).toList());
    }

    @Test
    void noNewIdIsATagAControlOfTheFormKeeps() {
        FoundSpots found = new FoundSpots(List.of(
                candidate("c1", SpotCandidate.Kind.UNDERSCORES, "Company", FieldType.TEXT, "____", false, null)),
                List.of(), List.of(), Map.of(), List.of(), Set.of("company"));

        assertEquals(List.of("company.2"), RulesSpotNamer.plan(found, rulesOnly(found), Set.of()).fields().stream()
                .map(FieldDefinition::fieldId).toList());
    }

    @Test
    void theModelsDecisionsStandWhereItMadeThem() {
        FoundSpots found = found(
                candidate("c1", SpotCandidate.Kind.UNDERSCORES, "Blank 1", FieldType.TEXT, "____", false, null),
                candidate("c2", SpotCandidate.Kind.DOT_LEADER, "Total", FieldType.TEXT, "....", false, null),
                candidate("c3", SpotCandidate.Kind.BRACKET, "When", FieldType.TEXT, "[When]", false, null));
        SpotNaming naming = new SpotNaming(List.of(
                new NamedSpot("c1", true, "Applicant's name", FieldType.TEXT, "TEXT", true, true),
                new NamedSpot("c2", false, "Total", FieldType.TEXT, "NUMBER", false, true),
                new NamedSpot("c3", true, "\n", FieldType.DATE, "DATE", false, true)), null, NamingSource.MODEL, null, List.of());

        SpotPlan plan = RulesSpotNamer.plan(found, naming, Set.of());

        assertEquals(List.of("applicant.s.name", "when"), plan.fields().stream().map(FieldDefinition::fieldId).toList());
        assertEquals(NamingSource.MODEL, plan.spots().getFirst().namedBy());
        assertTrue(plan.spots().getFirst().requiredHint());
        assertEquals(FieldRequiredness.OPTIONAL, plan.spots().getFirst().field().requiredness());
        assertEquals("When", plan.spots().get(1).field().label(), "a label that is no label falls back to the rules'");
        assertEquals(FieldType.DATE, plan.spots().get(1).field().type());
    }

    @Test
    void aPlaceTheNamingStepLeftOutIsCountedButAPlaceToSignIsNot() {
        FoundSpots found = found(
                candidate("c1", SpotCandidate.Kind.UNDERSCORES, "Name", FieldType.TEXT, "____", false, null),
                candidate("c2", SpotCandidate.Kind.DOT_LEADER, "Example", FieldType.TEXT, "....", false, null),
                candidate("c3", SpotCandidate.Kind.LABEL_AT_END, "Office", FieldType.TEXT, null, false, null),
                candidate("c4", SpotCandidate.Kind.UNDERSCORES, "Signature", FieldType.TEXT, "____", true, null));
        SpotNaming naming = new SpotNaming(List.of(
                new NamedSpot("c1", true, "Name", FieldType.TEXT, "TEXT", false, true),
                new NamedSpot("c2", false, "Example", FieldType.TEXT, "TEXT", false, true),
                new NamedSpot("c3", false, "Office", FieldType.TEXT, "TEXT", false, true),
                new NamedSpot("c4", false, "Signature", FieldType.TEXT, "TEXT", false, true)), null, NamingSource.MODEL, null, List.of());

        SpotPlan plan = RulesSpotNamer.plan(found, naming, Set.of());

        assertEquals(List.of("Name"), plan.fields().stream().map(FieldDefinition::label).toList());
        assertEquals(List.of(new PreparationNotice(PreparationNotice.PLACES_LEFT_OUT, 2, null)), plan.notices());
        assertEquals(List.of(), RulesSpotNamer.plan(found, rulesOnly(found), Set.of()).notices(), "the rules leave out only the signature");
    }

    @Test
    void theNamingStepIsToldWhichPlacesTheRulesAreSureOfAndWhichRowEachIsKeptWith() {
        SpotCandidate cell = new SpotCandidate("c1", SpotCandidate.Kind.EMPTY_CELL, REPLACE, null, null, "Year (Painting)", FieldType.TEXT,
                SpotCandidate.Tier.HIGH, "T1R2", false, "column: Year; row: Painting", "k", List.of("Painting"));
        SpotCandidate weak = new SpotCandidate("c2", SpotCandidate.Kind.LABEL_AT_END, REPLACE, null, null, "Notes", FieldType.TEXT,
                SpotCandidate.Tier.MEDIUM, null, false, null, "k");
        SpotCandidate signature = candidate("c3", SpotCandidate.Kind.UNDERSCORES, "Signature", FieldType.TEXT, "____", true, "T1R3");

        List<NamingCandidate> offered = RulesSpotNamer.namingInput(found(cell, weak, signature)).candidates();

        assertEquals(new NamingCandidate("c1", "EMPTY_CELL", "Year (Painting)", FieldType.TEXT, "column: Year; row: Painting", false,
                "T1R2", true, "T1R2", List.of("Painting")), offered.get(0));
        assertFalse(offered.get(1).sure());
        assertNull(offered.get(1).groupKey());
        assertFalse(offered.get(2).sure(), "a place to sign is the rules' to leave out");
    }

    @Test
    void aControlWithAUsableTagIsKeptAsItIsAndOthersAreRetagged() {
        SpotCandidate kept = new SpotCandidate("c1", SpotCandidate.Kind.EXISTING_TAGGED_CONTROL, CONTROL, "meeting.title", null,
                "Meeting title", FieldType.TEXT, SpotCandidate.Tier.HIGH, null, false, null, "k");
        SpotCandidate unsafe = new SpotCandidate("c2", SpotCandidate.Kind.EXISTING_TAGGED_CONTROL, CONTROL, null, null,
                "Customer Name", FieldType.TEXT, SpotCandidate.Tier.HIGH, null, false, "Customer Name", "k");
        SpotCandidate untagged = new SpotCandidate("c3", SpotCandidate.Kind.EXISTING_UNTAGGED_CONTROL, CONTROL, null, null,
                "Notes", FieldType.TEXT, SpotCandidate.Tier.HIGH, null, false, null, "k");
        FoundSpots found = found(kept, unsafe, untagged);

        assertEquals(List.of("c3"), RulesSpotNamer.namingInput(found).candidates().stream().map(NamingCandidate::id).toList(),
                "the form's own controls are not second-guessed");
        SpotPlan plan = RulesSpotNamer.plan(found, rulesOnly(found), Set.of());

        SpotPlan.PlannedSpot first = plan.spots().getFirst();
        assertEquals("meeting.title", first.field().fieldId());
        assertNull(first.field().label());
        assertEquals(SpotOrigin.FORM, first.field().origin());
        assertEquals(DocxControlOrigin.ORIGINAL, first.field().docxControl());
        assertNull(first.edit());

        SpotPlan.PlannedSpot second = plan.spots().get(1);
        assertEquals("customer.name", second.field().fieldId());
        assertEquals(SpotOrigin.FORM, second.field().origin());
        assertEquals(DocxControlOrigin.TAGGED_BY_BROWNIE, second.field().docxControl());
        assertEquals(new SpotEdit.Retag(DocumentPartKind.MAIN_DOCUMENT, "p1/sdt0", "customer.name", "Customer Name"), second.edit());

        SpotPlan.PlannedSpot third = plan.spots().get(2);
        assertEquals(SpotOrigin.FOUND_BY_BROWNIE, third.field().origin());
        assertEquals(DocxControlOrigin.TAGGED_BY_BROWNIE, third.field().docxControl());
        assertInstanceOf(SpotEdit.Retag.class, third.edit());
        assertEquals(List.of(), plan.removedRowNodeIds());
    }

    @Test
    void theOfferedRowRepeatsAndTheIdenticalRowsUnderItGo() {
        FoundSpots found = new FoundSpots(List.of(
                candidate("c1", SpotCandidate.Kind.EMPTY_CELL, "Item", FieldType.TEXT, null, false, "T1R2"),
                candidate("c2", SpotCandidate.Kind.EMPTY_CELL, "Cost", FieldType.TEXT, null, false, "T1R2"),
                candidate("c3", SpotCandidate.Kind.EMPTY_CELL, "Item", FieldType.TEXT, null, false, "T1R3"),
                candidate("c4", SpotCandidate.Kind.EMPTY_CELL, "Cost", FieldType.TEXT, null, false, "T1R3"),
                candidate("c5", SpotCandidate.Kind.UNDERSCORES, "Approved by", FieldType.TEXT, "____", false, null)),
                List.of(), List.of("T1R2"), Map.of("T1R2", List.of(new FoundSpots.CollapsedRow("T1R3", "tbl0/row2"))), List.of());

        SpotPlan plan = RulesSpotNamer.plan(found, rulesOnly(found), Set.of());

        assertEquals(List.of("item", "cost", "approved.by"), plan.fields().stream().map(FieldDefinition::fieldId).toList());
        assertEquals(List.of(FieldCardinality.REPEATED, FieldCardinality.REPEATED, FieldCardinality.SCALAR),
                plan.fields().stream().map(FieldDefinition::cardinality).toList());
        assertEquals(List.of("tbl0/row2"), plan.removedRowNodeIds());
        assertTrue(plan.notices().contains(new PreparationNotice(PreparationNotice.TABLE_ROWS_GROW, 1, null)));

        SpotPlan single = plan.withoutRepeatingRow();
        assertFalse(single.hasRepeatingRow());
        assertFalse(single.notices().stream().anyMatch(notice -> notice.code().equals(PreparationNotice.TABLE_ROWS_GROW)));
        assertEquals(List.of(), single.removedRowNodeIds(), "rows go only for a row that repeats");

        SpotPlan withoutTheRow = plan.without(Set.of("item", "cost"));
        assertEquals(List.of(), withoutTheRow.removedRowNodeIds());
        assertFalse(withoutTheRow.notices().stream().anyMatch(notice -> notice.code().equals(PreparationNotice.TABLE_ROWS_GROW)));
        assertEquals(List.of("tbl0/row2"), plan.without(Set.of("approved.by")).removedRowNodeIds());
    }

    @Test
    void aRowThatWasNotOfferedOrHoldsNothingKeptDoesNotRepeat() {
        FoundSpots found = new FoundSpots(List.of(
                candidate("c1", SpotCandidate.Kind.EMPTY_CELL, "Signature", FieldType.TEXT, null, true, "T1R2")),
                List.of(), List.of("T1R2"), Map.of(), List.of());
        SpotNaming chooseNotOffered = new SpotNaming(List.of(), "T9R9", NamingSource.MODEL, null, List.of());

        assertFalse(RulesSpotNamer.plan(found, chooseNotOffered, Set.of()).hasRepeatingRow());
        SpotNaming chooseEmpty = new SpotNaming(List.of(), "T1R2", NamingSource.RULES, SpotNaming.DISABLED, List.of());
        SpotPlan plan = RulesSpotNamer.plan(found, chooseEmpty, Set.of());
        assertTrue(plan.spots().isEmpty());
        assertTrue(plan.notices().isEmpty());
    }

    @Test
    void aLastRowOfTheFormsOwnControlsRepeatsThoughTheNamingStepIsNeverShownIt() {
        SpotCandidate title = tagged("c1", "meeting.title", null);
        SpotCandidate task = tagged("c2", "action.item.task", "T1R2");
        SpotCandidate owner = tagged("c3", "action.item.owner", "T1R2");
        FoundSpots onlyControls = new FoundSpots(List.of(title, task, owner), List.of(), List.of("T1R2"), Map.of(), List.of());
        SpotNaming nothingToName = new SpotNaming(List.of(), null, NamingSource.RULES, SpotNaming.NO_CANDIDATES, List.of());

        SpotPlan plan = RulesSpotNamer.plan(onlyControls, nothingToName, Set.of());

        assertEquals(List.of(FieldCardinality.SCALAR, FieldCardinality.REPEATED, FieldCardinality.REPEATED),
                plan.fields().stream().map(FieldDefinition::cardinality).toList());
        assertTrue(plan.notices().contains(new PreparationNotice(PreparationNotice.TABLE_ROWS_GROW, 1, null)));

        FoundSpots withABlank = new FoundSpots(List.of(title, task, owner,
                candidate("c4", SpotCandidate.Kind.UNDERSCORES, "Notes", FieldType.TEXT, "____", false, null)),
                List.of(), List.of("T1R2"), Map.of(), List.of());
        SpotPlan named = RulesSpotNamer.plan(withABlank, rulesOnly(withABlank), Set.of());
        assertEquals(List.of(FieldCardinality.SCALAR, FieldCardinality.REPEATED, FieldCardinality.REPEATED, FieldCardinality.SCALAR),
                named.fields().stream().map(FieldDefinition::cardinality).toList());

        FoundSpots declined = new FoundSpots(List.of(title,
                candidate("c2", SpotCandidate.Kind.EMPTY_CELL, "Task", FieldType.TEXT, null, false, "T1R2")),
                List.of(), List.of("T1R2"), Map.of(), List.of());
        SpotNaming noRow = new SpotNaming(List.of(), null, NamingSource.MODEL, null, List.of());
        assertFalse(RulesSpotNamer.plan(declined, noRow, Set.of()).hasRepeatingRow(),
                "a row the naming step was shown and did not choose does not repeat");
    }

    @Test
    void aPlanWithoutFailedFieldsSaysHowManyWereLeftOut() {
        FoundSpots found = found(
                candidate("c1", SpotCandidate.Kind.UNDERSCORES, "Name", FieldType.TEXT, "____", false, null),
                candidate("c2", SpotCandidate.Kind.UNDERSCORES, "Town", FieldType.TEXT, "____", false, null));
        SpotPlan plan = RulesSpotNamer.plan(found, rulesOnly(found), Set.of());

        SpotPlan fewer = plan.without(Set.of("town"));

        assertEquals(List.of("name"), fewer.fields().stream().map(FieldDefinition::fieldId).toList());
        assertEquals(List.of(new PreparationNotice(PreparationNotice.SPOTS_SKIPPED, 1, null)), fewer.notices());
        assertEquals(1, fewer.edits().size());
    }

    @Test
    void labelWordsFollowTheRules() {
        assertEquals("Name", SpotLabels.clean("please write your name here:"));
        assertEquals("Full name", SpotLabels.clean("Full name (required)*:"));
        assertEquals("Name", SpotLabels.clean("Print name"));
        assertEquals("Type of membership", SpotLabels.clean("Type of membership:"));
        assertNull(SpotLabels.clean("one two three four five six seven eight nine"));
        assertEquals("First name", SpotLabels.fromName("FirstName"));
        assertEquals("Client email", SpotLabels.fromName("client_email"));
        assertTrue(SpotLabels.namesADate("Date of birth"));
        assertTrue(SpotLabels.namesADate("Start (DD/MM/YYYY)"));
        assertTrue(SpotLabels.namesADate("Ng\u00E0y k\u00FD"));
        assertFalse(SpotLabels.namesADate("Candidate name"));
        assertFalse(SpotLabels.namesADate("Updated by"));
        assertFalse(SpotLabels.namesADate("Place of birth"));
        assertFalse(SpotLabels.namesADate("Country of birth"));
        assertFalse(SpotLabels.namesADate("Day phone"));
        assertTrue(SpotLabels.namesADate("Birthday"));
        assertTrue(SpotLabels.namesADate("DOB"));
        assertTrue(SpotLabels.signatureLike("Sign here"));
        assertTrue(SpotLabels.signatureLike("Unterschrift"));
        assertFalse(SpotLabels.signatureLike("Assignment"));
    }

    private static SpotNaming rulesOnly(FoundSpots found) {
        return new RulesOnlySpotNamer(SpotNaming.DISABLED).name(1, 1, RulesSpotNamer.namingInput(found));
    }

    private static FoundSpots found(SpotCandidate... candidates) {
        return new FoundSpots(List.of(candidates), List.of(), List.of(), Map.of(), List.of());
    }

    private static SpotCandidate tagged(String id, String tag, String rowKey) {
        return new SpotCandidate(id, SpotCandidate.Kind.EXISTING_TAGGED_CONTROL, CONTROL, tag, null, FieldIds.labelFor(tag),
                FieldType.TEXT, SpotCandidate.Tier.HIGH, rowKey, false, null, "k");
    }

    private static SpotCandidate candidate(
            String id, SpotCandidate.Kind kind, String label, FieldType type, String blank, boolean signature, String rowKey) {
        return new SpotCandidate(id, kind, REPLACE, null, blank, label, type, SpotCandidate.Tier.HIGH, rowKey, signature, null, "k");
    }
}
