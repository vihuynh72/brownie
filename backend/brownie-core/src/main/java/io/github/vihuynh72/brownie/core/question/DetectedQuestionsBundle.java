package io.github.vihuynh72.brownie.core.question;

import java.util.List;
import java.util.Objects;

/**
 * The trusted worker's own detected-but-not-yet-persisted questions for one
 * generation attempt, handed back to the API the same way {@code
 * ExtractionInputBundle} hands facts the other direction: a plain,
 * framework-free record either side's Jackson runtime can (de)serialize
 * with no tenant-database access required on the worker's side. The worker
 * cannot itself insert a {@link Question} row -- it has no access to the
 * {@code question} table -- so it stages this list at a deterministic,
 * job-scoped object key instead; the API reads it back and persists real,
 * answerable questions from it exactly once.
 *
 * <p>{@code fencingToken} is the job's fencing token of the attempt that
 * staged this bundle. The API only materializes a bundle whose token is
 * the job's current one: a slower, superseded attempt that overwrites the
 * blob after a newer claim cannot put stale questions in front of a
 * person, and an attempt whose questions were already materialized is
 * recognised by the same token rather than re-persisted.
 */
public record DetectedQuestionsBundle(long fencingToken, List<DetectedQuestion> questions) {

    public DetectedQuestionsBundle {
        questions = List.copyOf(Objects.requireNonNull(questions, "questions"));
    }
}
