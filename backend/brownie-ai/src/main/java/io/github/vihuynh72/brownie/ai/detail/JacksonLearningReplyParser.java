package io.github.vihuynh72.brownie.ai.detail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.detail.LearningReply;
import io.github.vihuynh72.brownie.core.detail.LearningReplyParser;

import java.util.ArrayList;
import java.util.List;

/** Jackson reading of the who-is-this-form-about call's strict-JSON reply. */
public class JacksonLearningReplyParser implements LearningReplyParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public LearningReply parse(String content) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(content);
            LearningReply.Subject subject = LearningReply.Subject.valueOf(root.path("subject").asText("UNSURE"));
            List<LearningReply.Pair> pairs = new ArrayList<>();
            for (JsonNode pair : root.path("pairs")) {
                JsonNode key = pair.path("key");
                pairs.add(new LearningReply.Pair(
                        pair.path("label").asText(null), key.isNull() || !key.isTextual() ? null : key.asText()));
            }
            return new LearningReply(subject, pairs);
        } catch (Exception e) {
            throw new IllegalStateException("The who-is-this-form-about reply was not the asked-for JSON.", e);
        }
    }
}
