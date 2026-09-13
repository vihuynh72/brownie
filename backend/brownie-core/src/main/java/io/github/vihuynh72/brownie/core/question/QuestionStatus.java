package io.github.vihuynh72.brownie.core.question;

/** OPEN until answered exactly once. There is no route back to OPEN and no route to change an existing answer. */
public enum QuestionStatus {
    OPEN,
    ANSWERED
}
