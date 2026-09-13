package io.github.vihuynh72.brownie.core.question;

/** Why a question exists: a required field extraction could not resolve, or a resolved candidate disagrees with the field's own current document value. */
public enum QuestionReason {
    MISSING_REQUIRED,
    CONFLICT
}
