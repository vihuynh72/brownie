package io.github.vihuynh72.brownie.core.prepare;

import java.util.List;
import java.util.Objects;

/** The clean working copy, and what making it changed or kept, in the order the steps ran. */
public record PreparedCopy(byte[] docxBytes, List<PreparationNotice> notices) {

    public PreparedCopy {
        Objects.requireNonNull(docxBytes, "docxBytes");
        notices = List.copyOf(notices);
    }
}
