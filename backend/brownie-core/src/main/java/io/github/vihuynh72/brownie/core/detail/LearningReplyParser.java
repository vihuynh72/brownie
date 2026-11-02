package io.github.vihuynh72.brownie.core.detail;

/**
 * Turns the who-is-this-form-about call's raw answer into a {@link
 * LearningReply}; the strict schema is the same JSON the reply carries.
 * Implementations live where a JSON reader does ({@code brownie-ai}).
 */
public interface LearningReplyParser {

    LearningReply parse(String content);
}
