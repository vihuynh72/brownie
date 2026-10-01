package io.github.vihuynh72.brownie.core.prepare;

import java.util.List;
import java.util.Set;

/**
 * What {@link FillableCopyVerifier} found. {@code failedFieldIds} are the
 * fields that failed on their own (a tag not found once, a spot not placed,
 * a sample value not written); {@code copyFailed} means the copy as a whole
 * failed (it does not read, its text changed, something active is left)
 * and no one field is to blame; {@code repeatedGroupFailed} means only the
 * repeating row failed to fill, which filling it once as single values
 * would avoid. {@code problems} say what went wrong, for the log.
 */
public record FillableCopyCheck(boolean copyFailed, Set<String> failedFieldIds, boolean repeatedGroupFailed, List<String> problems) {

    public FillableCopyCheck {
        failedFieldIds = Set.copyOf(failedFieldIds);
        problems = List.copyOf(problems);
    }

    public static FillableCopyCheck passed() {
        return new FillableCopyCheck(false, Set.of(), false, List.of());
    }

    public boolean ok() {
        return !copyFailed && failedFieldIds.isEmpty() && !repeatedGroupFailed;
    }
}
