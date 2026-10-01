package io.github.vihuynh72.brownie.core.assist;

import io.github.vihuynh72.brownie.core.template.DocxControlOrigin;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class AssistCommandParserTest {

    private static final List<FieldDefinition> FIELDS = List.of(
            field("meeting.title", FieldType.TEXT),
            field("meeting.date", FieldType.DATE),
            field("meeting.decisions", FieldType.TEXT),
            field("action.item.task", FieldType.TEXT));

    @Test
    void changeNamesTheFieldByLabelOrIdAndKeepsTheValueAsTyped() {
        assertEquals(new AssistCommand.ChangeField("meeting.title", "Spring Planning"),
                AssistCommandParser.parse("change the meeting title to Spring Planning", FIELDS));
        assertEquals(new AssistCommand.ChangeField("meeting.title", "Spring Planning"),
                AssistCommandParser.parse("Set meeting.title to \"Spring Planning\".", FIELDS));
        assertEquals(new AssistCommand.ChangeField("meeting.date", "2026-04-09"),
                AssistCommandParser.parse("Meeting date: 2026-04-09", FIELDS));
        assertEquals(new AssistCommand.ChangeField("meeting.title", "Budget review: final"),
                AssistCommandParser.parse("update Meeting Title to Budget review: final", FIELDS));
    }

    @Test
    void shortenAndRewriteNameATextFieldAndKeepAnyInstruction() {
        assertEquals(new AssistCommand.RewriteField("meeting.decisions", AssistCommand.RewriteMode.SHORTEN, null),
                AssistCommandParser.parse("shorten the decisions section", FIELDS));
        assertEquals(new AssistCommand.RewriteField("meeting.decisions", AssistCommand.RewriteMode.SHORTEN, null),
                AssistCommandParser.parse("make meeting decisions shorter", FIELDS));
        assertEquals(new AssistCommand.RewriteField("meeting.title", AssistCommand.RewriteMode.REWRITE, "sound more formal"),
                AssistCommandParser.parse("rewrite the meeting title to sound more formal", FIELDS));
        assertEquals(new AssistCommand.RewriteField("meeting.decisions", AssistCommand.RewriteMode.REWRITE, null),
                AssistCommandParser.parse("Rephrase meeting.decisions", FIELDS));
    }

    @Test
    void draftAndExplainAreRecognisedInTheirOrdinaryWordings() {
        assertInstanceOf(AssistCommand.DraftFromSources.class, AssistCommandParser.parse("draft from these sources", FIELDS));
        assertInstanceOf(AssistCommand.DraftFromSources.class, AssistCommandParser.parse("Fill it in from the transcript", FIELDS));
        assertInstanceOf(AssistCommand.DraftFromSources.class, AssistCommandParser.parse("draft", FIELDS));

        AssistCommand explainAll = AssistCommandParser.parse("explain this finding", FIELDS);
        assertInstanceOf(AssistCommand.ExplainFinding.class, explainAll);
        assertNull(((AssistCommand.ExplainFinding) explainAll).fieldId());
        assertEquals(new AssistCommand.ExplainFinding("meeting.date"),
                AssistCommandParser.parse("why is the meeting date blocking export?", FIELDS));
        assertEquals(new AssistCommand.ExplainFinding("action.item.task"),
                AssistCommandParser.parse("Explain the finding on action.item.task", FIELDS));
    }

    @Test
    void anythingElseIsUnrecognisedRatherThanGuessed() {
        assertInstanceOf(AssistCommand.Unrecognized.class, AssistCommandParser.parse("write me a poem about the club", FIELDS));
        assertInstanceOf(AssistCommand.Unrecognized.class, AssistCommandParser.parse("change the venue to the library", FIELDS));
        assertInstanceOf(AssistCommand.Unrecognized.class, AssistCommandParser.parse("shorten the agenda", FIELDS));
        // "item" ends two labels (action.item.task and, in a fuller template, action.item.owner); one word that fits several fields resolves none.
        assertInstanceOf(AssistCommand.Unrecognized.class, AssistCommandParser.parse(
                "shorten the task", List.of(field("action.item.task", FieldType.TEXT), field("other.task", FieldType.TEXT))));
        assertInstanceOf(AssistCommand.Unrecognized.class, AssistCommandParser.parse("   ", FIELDS));
        assertEquals("delete everything", ((AssistCommand.Unrecognized) AssistCommandParser.parse("delete everything", FIELDS)).text());
    }

    @Test
    void labelsMatchWhatTheWorkspaceShows() {
        assertEquals("Meeting title", AssistCommandParser.labelFor("meeting.title"));
        assertEquals("Action item due", AssistCommandParser.labelFor("action.item.due"));
    }

    /** A field found in a form is named by the words the form used, which the person reads and types back. */
    @Test
    void aStoredLabelNamesTheFieldAsWellAsItsId() {
        List<FieldDefinition> fields = List.of(
                labelled("ho.va.ten", FieldType.TEXT, "H\u1ecd v\u00e0 t\u00ean"),
                labelled("spot.3", FieldType.DATE, "Date of birth"),
                labelled("spot.4", FieldType.TEXT, "Home address"),
                field("meeting.title", FieldType.TEXT));

        assertEquals(new AssistCommand.ChangeField("ho.va.ten", "Nguy\u1ec5n V\u0103n An"),
                AssistCommandParser.parse("change h\u1ecd v\u00e0 t\u00ean to Nguy\u1ec5n V\u0103n An", fields));
        assertEquals(new AssistCommand.ChangeField("ho.va.ten", "An"), AssistCommandParser.parse("Ho va ten: An", fields));
        // Typed with separate accent marks, as some keyboards send it, the label still matches.
        assertEquals(new AssistCommand.ChangeField("ho.va.ten", "An"),
                AssistCommandParser.parse("change Ho\u0323 va\u0300 te\u0302n to An", fields));
        assertEquals(new AssistCommand.ChangeField("spot.3", "1990-04-09"),
                AssistCommandParser.parse("set the date of birth to 1990-04-09", fields));
        assertEquals(new AssistCommand.RewriteField("spot.4", AssistCommand.RewriteMode.SHORTEN, null),
                AssistCommandParser.parse("shorten the address", fields));
        assertEquals(new AssistCommand.ExplainFinding("spot.3"),
                AssistCommandParser.parse("why is the date of birth blocking export?", fields));
        assertEquals(new AssistCommand.ChangeField("meeting.title", "Spring Planning"),
                AssistCommandParser.parse("change the meeting title to Spring Planning", fields));
    }

    @Test
    void addingASpotAfterQuotedWordsKeepsTheWordsAndReplacesAQuotedBlank() {
        assertEquals(add("Company", "Company:", AssistCommand.SpotPlacement.AFTER, false, FieldType.TEXT),
                AssistCommandParser.parse("Add a fill spot for Company after \"Company:\"", FIELDS));
        assertEquals(add("Company name", "Name of company", AssistCommand.SpotPlacement.AFTER, false, FieldType.TEXT),
                AssistCommandParser.parse("please put the company name next to \u201CName of company\u201D.", FIELDS));
        assertEquals(add("Company", "________", AssistCommand.SpotPlacement.REPLACE, false, FieldType.TEXT),
                AssistCommandParser.parse("add a spot called company after \"________\"", FIELDS));
        assertEquals(add("Company", "[Company]", AssistCommand.SpotPlacement.REPLACE, false, FieldType.TEXT),
                AssistCommandParser.parse("insert a field for Company in place of \"[Company]\"", FIELDS));
        assertEquals(add("Start date", "Starts:", AssistCommand.SpotPlacement.WHOLE_LINE, false, FieldType.DATE),
                AssistCommandParser.parse("add a fill spot for the start date at the end of the line \"Starts:\"", FIELDS));
        assertEquals(add("Phone", "Phone", AssistCommand.SpotPlacement.IN_LINE, false, FieldType.TEXT),
                AssistCommandParser.parse("add a fill spot for Phone on the line \"Phone\"", FIELDS));
    }

    @Test
    void addingASpotHereOrOnThisLineUsesThePlaceSelectedOnThePage() {
        assertEquals(add("Company", null, AssistCommand.SpotPlacement.UNSPECIFIED, true, FieldType.TEXT),
                AssistCommandParser.parse("add a fill spot for Company here", FIELDS));
        assertEquals(add("Company", null, AssistCommand.SpotPlacement.UNSPECIFIED, true, FieldType.TEXT),
                AssistCommandParser.parse("add a spot here called company", FIELDS));
        assertEquals(add("Signature date", null, AssistCommand.SpotPlacement.UNSPECIFIED, true, FieldType.DATE),
                AssistCommandParser.parse("fill in here: signature date", FIELDS));
        assertEquals(add(null, null, AssistCommand.SpotPlacement.UNSPECIFIED, true, FieldType.TEXT),
                AssistCommandParser.parse("fill in here", FIELDS));
        assertEquals(add("Company", null, AssistCommand.SpotPlacement.UNSPECIFIED, true, FieldType.TEXT),
                AssistCommandParser.parse("add a fill spot for Company on this line", FIELDS));
        assertEquals(add("Company", null, AssistCommand.SpotPlacement.WHOLE_LINE, true, FieldType.TEXT),
                AssistCommandParser.parse("this line is the company", FIELDS));
        assertEquals(add("Company", "____", AssistCommand.SpotPlacement.WHOLE_LINE, false, FieldType.TEXT),
                AssistCommandParser.parse("the line that says \"____\" is the Company", FIELDS));
        // Naming what goes there is enough: the thing put is the spot's name.
        assertEquals(add("Company name", null, AssistCommand.SpotPlacement.UNSPECIFIED, true, FieldType.TEXT),
                AssistCommandParser.parse("put the company name here", FIELDS));
        assertEquals(add("Date of birth", null, AssistCommand.SpotPlacement.UNSPECIFIED, true, FieldType.DATE),
                AssistCommandParser.parse("Please write the date of birth on this line", FIELDS));
    }

    @Test
    void aSpotsNameMeansThatSpotEvenWhenARenamedSpotKeptTheSameWordsAsItsId() {
        // "Full name" (full.name) was renamed "Name", and a new spot then took the name "Full name".
        FieldDefinition renamed = labelled("full.name", FieldType.TEXT, "Name");
        FieldDefinition added = labelled("full.name.2", FieldType.TEXT, "Full name");
        for (List<FieldDefinition> fields : List.of(List.of(renamed, added), List.of(added, renamed))) {
            assertEquals(new AssistCommand.RemoveFillSpot("full.name.2"), AssistCommandParser.parse("remove the Full name spot", fields));
            assertEquals(new AssistCommand.RenameFillSpot("full.name.2", "Legal name"),
                    AssistCommandParser.parse("rename Full name to Legal name", fields));
            assertEquals(new AssistCommand.ChangeField("full.name.2", "Priya Rao"), AssistCommandParser.parse("set full name to Priya Rao", fields));
            assertEquals(new AssistCommand.ExplainFinding("full.name.2"), AssistCommandParser.parse("explain the full name finding", fields));
            // An id still names its spot when no label does.
            assertEquals(new AssistCommand.ChangeField("full.name.2", "Priya Rao"), AssistCommandParser.parse("set full.name.2 to Priya Rao", fields));
            assertEquals(new AssistCommand.ChangeField("full.name", "Priya"), AssistCommandParser.parse("set name to Priya", fields));
        }
    }

    @Test
    void aValueToWriteSomewhereIsNotTakenForTheNameOfANewSpot() {
        for (String text : List.of("write Priya Rao here", "put 12 March 2026 here", "write N/A here", "put Priya Rao on this line",
                "put priya@example.com here", "put 12 March 2026 after \"Date:\"")) {
            assertInstanceOf(AssistCommand.Unrecognized.class, AssistCommandParser.parse(text, FIELDS), text);
        }
        // Words that name a thing still add a spot for it, and "add a fill spot for" names a spot whatever follows.
        assertEquals(add("Company name", null, AssistCommand.SpotPlacement.UNSPECIFIED, true, FieldType.TEXT),
                AssistCommandParser.parse("put company name here", FIELDS));
        assertEquals(add("Priya Rao", null, AssistCommand.SpotPlacement.UNSPECIFIED, true, FieldType.TEXT),
                AssistCommandParser.parse("add a fill spot for Priya Rao here", FIELDS));
    }

    @Test
    void addingASpotWhoseWordsGiveNoQuotedPlaceIsLeftForTheModelToPlace() {
        assertEquals(add("Company", null, AssistCommand.SpotPlacement.UNSPECIFIED, false, FieldType.TEXT),
                AssistCommandParser.parse("add a fill spot for Company after the address", FIELDS));
        assertEquals(add("Date of birth", null, AssistCommand.SpotPlacement.UNSPECIFIED, false, FieldType.DATE),
                AssistCommandParser.parse("create a new field called date of birth", FIELDS));
    }

    @Test
    void renamingAndTakingAwayNameAnExistingSpotAndTakingAwayNeedsTheWordSpot() {
        assertEquals(new AssistCommand.RenameFillSpot("meeting.title", "Meeting name"),
                AssistCommandParser.parse("rename the meeting title to meeting name", FIELDS));
        assertEquals(new AssistCommand.RenameFillSpot("meeting.decisions", "Outcomes"),
                AssistCommandParser.parse("Rename the fill spot decisions to \"Outcomes\"", FIELDS));
        assertEquals(new AssistCommand.RenameFillSpot("meeting.date", "Held on"),
                AssistCommandParser.parse("rename the meeting date spot as Held on", FIELDS));
        assertEquals(new AssistCommand.RemoveFillSpot("meeting.decisions"),
                AssistCommandParser.parse("remove the fill spot for decisions", FIELDS));
        assertEquals(new AssistCommand.RemoveFillSpot("meeting.date"),
                AssistCommandParser.parse("take away the meeting date field", FIELDS));
        assertInstanceOf(AssistCommand.Unrecognized.class, AssistCommandParser.parse("remove the fill spot for invoice number", FIELDS));
        assertInstanceOf(AssistCommand.Unrecognized.class, AssistCommandParser.parse("rename the invoice number to Invoice", FIELDS));
        // Without the word "spot" this may mean clearing a value, so it is not read as taking a spot away.
        assertInstanceOf(AssistCommand.Unrecognized.class, AssistCommandParser.parse("remove the meeting title", FIELDS));
        // The ordinary commands keep their meaning.
        assertEquals(new AssistCommand.ChangeField("meeting.title", "Spring Planning"),
                AssistCommandParser.parse("change the meeting title to Spring Planning", FIELDS));
        assertInstanceOf(AssistCommand.DraftFromSources.class, AssistCommandParser.parse("fill it in from the transcript", FIELDS));
    }

    private static AssistCommand add(String label, String quoted, AssistCommand.SpotPlacement placement, boolean here, FieldType type) {
        return new AssistCommand.AddFillSpot(label, quoted, placement, here, type);
    }

    private static FieldDefinition field(String fieldId, FieldType type) {
        return new FieldDefinition(fieldId, type, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(fieldId));
    }

    private static FieldDefinition labelled(String fieldId, FieldType type, String label) {
        return new FieldDefinition(
                fieldId, type, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(fieldId),
                label, SpotOrigin.FOUND_BY_BROWNIE, DocxControlOrigin.INSERTED_BY_BROWNIE, "________");
    }
}
