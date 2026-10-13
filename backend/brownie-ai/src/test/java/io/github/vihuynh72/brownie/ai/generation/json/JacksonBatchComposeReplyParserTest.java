package io.github.vihuynh72.brownie.ai.generation.json;

import io.github.vihuynh72.brownie.core.batch.BatchComposeReply;
import io.github.vihuynh72.brownie.core.batch.BatchComposeReplyParseException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The strict reply decode: exactly the asked-for ids, each exactly a text. */
class JacksonBatchComposeReplyParserTest {

    private final JacksonBatchComposeReplyParser parser = new JacksonBatchComposeReplyParser(new ObjectMapper());

    @Test
    void aWellFormedReplyParses() {
        BatchComposeReply reply = parser.parse(
                "{\"places\":{\"citation\":{\"text\":\"For the fair\"},\"note\":{\"text\":\"hi\"}}}",
                Set.of("citation", "note"));
        assertThat(reply.texts()).containsExactly(
                java.util.Map.entry("citation", "For the fair"), java.util.Map.entry("note", "hi"));
    }

    @Test
    void anythingOffShapeRefusesTheWholeReply() {
        assertUnparseable("not json", Set.of("a"));
        assertUnparseable("{\"places\":{\"a\":{\"text\":\"x\"}},\"extra\":1}", Set.of("a"));
        assertUnparseable("{\"places\":[]}", Set.of("a"));
        assertUnparseable("{\"places\":{\"a\":{\"text\":\"x\"},\"b\":{\"text\":\"y\"}}}", Set.of("a"));
        assertUnparseable("{\"places\":{\"a\":{\"text\":\"x\",\"more\":1}}}", Set.of("a"));
        assertUnparseable("{\"places\":{\"a\":{}}}", Set.of("a"));
        assertUnparseable("{\"places\":{\"a\":{\"text\":3}}}", Set.of("a"));
        assertUnparseable("{\"places\":{\"a\":\"x\"}}", Set.of("a"));
        assertUnparseable("{}", Set.of("a"));
        // An id that was never asked for rejects the reply even beside a good one.
        assertUnparseable("{\"places\":{\"a\":{\"text\":\"x\"},\"evil\":{\"text\":\"y\"}}}", Set.of("a"));
    }

    private void assertUnparseable(String json, Set<String> expectedIds) {
        assertThatThrownBy(() -> parser.parse(json, expectedIds))
                .isInstanceOf(BatchComposeReplyParseException.class);
    }
}
