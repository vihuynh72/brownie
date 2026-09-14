package io.github.vihuynh72.brownie.api.question;

/** A question command does not match the bounded typed request contract. */
public final class QuestionRequestValidationException extends IllegalArgumentException {

    public QuestionRequestValidationException(String message) {
        super(message);
    }
}
