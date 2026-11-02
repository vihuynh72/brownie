package io.github.vihuynh72.brownie.core.detail;

import java.util.List;

/**
 * What the who-is-this-form-about call decided: the subject and, for each
 * approved label it was given, the canonical key it mapped it to (or null).
 */
public record LearningReply(Subject subject, List<Pair> pairs) {

    public enum Subject {
        SELF, OTHER, UNSURE
    }

    /** One approved label and the canonical key the model chose for it (null for none). */
    public record Pair(String label, String key) {
    }
}
