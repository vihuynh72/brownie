package io.github.vihuynh72.brownie.core.question;

/** No OPEN question by that ID exists in the caller's workspace -- whether it never existed, belongs to someone else, or was already answered. */
public class QuestionNotFoundException extends RuntimeException {

    public QuestionNotFoundException(long questionId) {
        super("No open question " + questionId + " in this workspace.");
    }
}
