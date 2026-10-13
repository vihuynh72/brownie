package io.github.vihuynh72.brownie.core.batch;

import java.util.Objects;

/**
 * One recorded problem on a batch row: a code, the field it concerns when
 * one does, and the 0-based column it came from when one did. Persisted as
 * {@code {"code","fieldId","column"}} -- codes and ids only, never anything
 * a cell held.
 */
public record BatchRowProblem(BatchRowProblemCode code, String fieldId, Integer column) {

    public BatchRowProblem {
        Objects.requireNonNull(code, "code must not be null");
    }
}
