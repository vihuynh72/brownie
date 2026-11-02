package io.github.vihuynh72.brownie.core.revision;

import java.util.Optional;

/**
 * A checkbox's truthy and falsy spellings. A proposal, a person's reply and a
 * stored value all land here before they become a {@link
 * FieldValue.CheckboxValue}.
 */
public final class CheckboxAnswers {

    private CheckboxAnswers() {
    }

    /** "yes"/"no" and their spellings; empty for anything else. */
    public static Optional<Boolean> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        return switch (text.trim().toLowerCase()) {
            case "checked", "yes", "y", "true", "x", "on", "1", "\u2713", "\u2714" -> Optional.of(Boolean.TRUE);
            case "unchecked", "no", "n", "false", "off", "0" -> Optional.of(Boolean.FALSE);
            default -> Optional.empty();
        };
    }
}
