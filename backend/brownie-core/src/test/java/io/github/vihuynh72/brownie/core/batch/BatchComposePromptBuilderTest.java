package io.github.vihuynh72.brownie.core.batch;

import io.github.vihuynh72.brownie.core.model.ModelMessageRole;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compose request, exactly as settled: the fixed system policy, the
 * three-block user message of JSON lines, the closed reply schema keyed
 * on the asked-for ids, and the output cap. A cell's text is data on a
 * line, never a shape the request itself takes.
 */
class BatchComposePromptBuilderTest {

    @Test
    void theSystemMessageIsTheSettledPolicyVerbatim() {
        ModelRequest request = BatchComposePromptBuilder.build(
                "Participation certificate",
                List.of(),
                List.of(new BatchComposePromptBuilder.Place("citation", "Citation", "For the fair")));
        assertEquals(ModelMessageRole.SYSTEM, request.messages().get(0).role());
        assertEquals(BatchComposePromptBuilder.SYSTEM_POLICY, request.messages().get(0).content());
        assertEquals("batch-compose-v1", request.promptVersion());
    }

    @Test
    void theUserMessageLaysOutTemplateContextAndPlacesAsJsonLines() {
        ModelRequest request = BatchComposePromptBuilder.build(
                "Participation certificate",
                List.of(
                        new BatchComposePromptBuilder.ContextValue("Member name", "Ana Lima"),
                        new BatchComposePromptBuilder.ContextValue("Club", "North Chapter")),
                List.of(new BatchComposePromptBuilder.Place("citation", "Citation", "For the winter fair")));
        assertEquals(
                "The document is made from this template: {\"template\":\"Participation certificate\"}\n\n"
                        + "The row's other values, one JSON object per line:\n"
                        + "{\"place\":\"Member name\",\"value\":\"Ana Lima\"}\n"
                        + "{\"place\":\"Club\",\"value\":\"North Chapter\"}\n\n"
                        + "The places to write, one JSON object per line:\n"
                        + "{\"id\":\"citation\",\"place\":\"Citation\",\"notes\":\"For the winter fair\"}",
                request.messages().get(1).content());
    }

    @Test
    void aRowWithNoContextValuesSaysSo() {
        ModelRequest request = BatchComposePromptBuilder.build(
                "T", List.of(), List.of(new BatchComposePromptBuilder.Place("f", "F", "n")));
        assertTrue(request.messages().get(1).content().contains("(none)"));
    }

    @Test
    void everyInlinedStringIsBoundedFlattenedAndEscaped() {
        String longNotes = "word ".repeat(400);
        ModelRequest request = BatchComposePromptBuilder.build(
                "x".repeat(500),
                List.of(new BatchComposePromptBuilder.ContextValue("a\tb", "one\r\ntwo")),
                List.of(new BatchComposePromptBuilder.Place("f", "l", longNotes + " \"tail\"")));
        String user = request.messages().get(1).content();
        assertFalse(user.contains("\t"));
        assertFalse(user.contains("\r"));
        assertTrue(user.contains("\"value\":\"one two\""));
        // The template name is cut at its bound.
        assertTrue(user.contains("\"template\":\"" + "x".repeat(200) + "..."));
        for (String line : user.split("\n")) {
            assertFalse(line.contains("\t"));
        }
    }

    @Test
    void theSchemaIsClosedAndKeysOnEveryAskedId() {
        ModelRequest request = BatchComposePromptBuilder.build(
                "T",
                List.of(),
                List.of(
                        new BatchComposePromptBuilder.Place("a.one", "A", "n"),
                        new BatchComposePromptBuilder.Place("b-two_2", "B", "n")));
        assertEquals(
                "{\"type\":\"object\",\"properties\":{\"places\":{\"type\":\"object\",\"properties\":{"
                        + "\"a.one\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
                        + "\"required\":[\"text\"],\"additionalProperties\":false},"
                        + "\"b-two_2\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
                        + "\"required\":[\"text\"],\"additionalProperties\":false}},"
                        + "\"required\":[\"a.one\",\"b-two_2\"],\"additionalProperties\":false}},"
                        + "\"required\":[\"places\"],\"additionalProperties\":false}",
                request.responseSchema().schemaJson());
    }

    @Test
    void anUnsafeFieldIdIsRefusedBeforeItCouldBeInlined() {
        for (String bad : List.of("1abc", "a b", "a\"b", "a{b", "", "x".repeat(49))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> BatchComposePromptBuilder.build(
                            "T", List.of(), List.of(new BatchComposePromptBuilder.Place(bad, "L", "n"))),
                    "id " + bad);
        }
        BatchComposePromptBuilder.build(
                "T", List.of(), List.of(new BatchComposePromptBuilder.Place("a.B-c_1", "L", "n")));
    }

    @Test
    void theOutputCapIsSixtyFourPlusTwoHundredPerPlaceToAThousand() {
        assertEquals(264, BatchComposePromptBuilder.maxOutputTokens(1));
        assertEquals(864, BatchComposePromptBuilder.maxOutputTokens(4));
        assertEquals(1000, BatchComposePromptBuilder.maxOutputTokens(5));
        assertEquals(1000, BatchComposePromptBuilder.maxOutputTokens(20));
    }
}
