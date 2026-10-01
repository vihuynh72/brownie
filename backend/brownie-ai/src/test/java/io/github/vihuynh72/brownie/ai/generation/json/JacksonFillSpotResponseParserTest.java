package io.github.vihuynh72.brownie.ai.generation.json;

import io.github.vihuynh72.brownie.core.prepare.FillSpotReply;
import io.github.vihuynh72.brownie.core.prepare.FillSpotResponseParseException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parser checks a naming reply's shape and nothing more: exactly the
 * asked-for properties, each of the right kind. What the reply says (ids,
 * labels, rows) is kept as given, for the namer to check against what it
 * offered.
 */
class JacksonFillSpotResponseParserTest {

    private final JacksonFillSpotResponseParser parser = new JacksonFillSpotResponseParser(new ObjectMapper());

    @Test
    void aReplyOfTheAskedForShapeIsReadAsGiven() throws Exception {
        FillSpotReply reply = parser.parse("""
                {"spots":[
                  {"id":"c1","keep":true,"label":"Full name","type":"TEXT","required":true},
                  {"id":"c9","keep":false,"label":"","type":"NUMBER","required":false}],
                 "repeatingRow":"T1R2"}
                """);

        assertThat(reply.spots()).containsExactly(
                new FillSpotReply.Spot("c1", true, "Full name", "TEXT", true),
                new FillSpotReply.Spot("c9", false, "", "NUMBER", false));
        assertThat(reply.repeatingRow()).isEqualTo("T1R2");
    }

    @Test
    void noRepeatingRowIsNull() throws Exception {
        assertThat(parser.parse("{\"spots\":[],\"repeatingRow\":null}").repeatingRow()).isNull();
    }

    @Test
    void aReplyOfAnyOtherShapeIsRefused() {
        assertThatThrownBy(() -> parser.parse("not json")).isInstanceOf(FillSpotResponseParseException.class);
        assertThatThrownBy(() -> parser.parse("[]")).isInstanceOf(FillSpotResponseParseException.class);
        assertThatThrownBy(() -> parser.parse("{\"spots\":[]}")).isInstanceOf(FillSpotResponseParseException.class);
        assertThatThrownBy(() -> parser.parse("{\"spots\":[],\"repeatingRow\":null,\"note\":\"x\"}"))
                .isInstanceOf(FillSpotResponseParseException.class);
        assertThatThrownBy(() -> parser.parse("{\"spots\":{},\"repeatingRow\":null}")).isInstanceOf(FillSpotResponseParseException.class);
        assertThatThrownBy(() -> parser.parse("{\"spots\":[],\"repeatingRow\":3}")).isInstanceOf(FillSpotResponseParseException.class);
        assertThatThrownBy(() -> parser.parse(
                "{\"spots\":[{\"id\":\"c1\",\"keep\":\"yes\",\"label\":\"A\",\"type\":\"TEXT\",\"required\":false}],\"repeatingRow\":null}"))
                .isInstanceOf(FillSpotResponseParseException.class);
        assertThatThrownBy(() -> parser.parse(
                "{\"spots\":[{\"id\":\"c1\",\"keep\":true,\"label\":null,\"type\":\"TEXT\",\"required\":false}],\"repeatingRow\":null}"))
                .isInstanceOf(FillSpotResponseParseException.class);
        assertThatThrownBy(() -> parser.parse(
                "{\"spots\":[{\"id\":\"c1\",\"keep\":true,\"label\":\"A\",\"type\":\"TEXT\"}],\"repeatingRow\":null}"))
                .isInstanceOf(FillSpotResponseParseException.class);
    }
}
