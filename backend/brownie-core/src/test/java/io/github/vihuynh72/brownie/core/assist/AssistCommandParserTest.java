package io.github.vihuynh72.brownie.core.assist;

import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
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

    private static FieldDefinition field(String fieldId, FieldType type) {
        return new FieldDefinition(fieldId, type, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag(fieldId));
    }
}
