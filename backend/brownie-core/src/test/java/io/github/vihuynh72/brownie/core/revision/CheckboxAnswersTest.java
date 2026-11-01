package io.github.vihuynh72.brownie.core.revision;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A checkbox's truthy and falsy spellings, and everything else means nothing. */
class CheckboxAnswersTest {

    @Test
    void truthySpellingsParseChecked() {
        for (String text : new String[]{"checked", "yes", "y", "true", "x", "on", "1", "✓", "✔", " YES ", "Checked"}) {
            assertEquals(Optional.of(Boolean.TRUE), CheckboxAnswers.parse(text), text);
        }
    }

    @Test
    void falsySpellingsParseUnchecked() {
        for (String text : new String[]{"unchecked", "no", "n", "false", "off", "0", " NO "}) {
            assertEquals(Optional.of(Boolean.FALSE), CheckboxAnswers.parse(text), text);
        }
    }

    @Test
    void anythingElseMeansNothing() {
        assertEquals(Optional.empty(), CheckboxAnswers.parse(""));
        assertEquals(Optional.empty(), CheckboxAnswers.parse("  "));
        assertEquals(Optional.empty(), CheckboxAnswers.parse("maybe"));
        assertEquals(Optional.empty(), CheckboxAnswers.parse(null));
    }
}
