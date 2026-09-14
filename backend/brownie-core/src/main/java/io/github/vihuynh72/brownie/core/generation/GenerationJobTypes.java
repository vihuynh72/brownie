package io.github.vihuynh72.brownie.core.generation;

/**
 * Stable identifiers shared by the two independent processes that must
 * agree on them without depending on each other: the API, which enqueues
 * a job under this exact type and later reads back a result published
 * under this exact output kind, and the trusted worker, which claims a
 * job of this type and publishes its result under this exact kind. Living
 * here, in the one module both already depend on, is what lets each side
 * stay consistent without brownie-worker ever depending on brownie-api.
 */
public final class GenerationJobTypes {

    /** Must satisfy {@code core.job.JobType}'s own lowercase-machine-identifier format contract. */
    public static final String EXTRACTION_JOB_TYPE = "generation.extract-facts";

    /** The published-output kind a completed extraction job's own result is filed under. */
    public static final String EXTRACTION_RESULT_OUTPUT_KIND = "extraction-result";

    private static final String PENDING_QUESTIONS_BLOB_PREFIX = "generation-pending-questions/";
    private static final String RESOLVED_ANSWERS_BLOB_PREFIX = "generation-resolved-answers/";

    /**
     * Deterministic by job ID alone (never a content hash): unlike the
     * frozen input bundle, this blob is overwritten on every attempt that
     * still has open questions, and there is exactly one such blob per job
     * at a time.
     */
    public static String pendingQuestionsObjectKey(long jobId) {
        return PENDING_QUESTIONS_BLOB_PREFIX + jobId + ".json";
    }

    /** Deterministic by job ID alone, for the same reason as {@link #pendingQuestionsObjectKey}. */
    public static String resolvedAnswersObjectKey(long jobId) {
        return RESOLVED_ANSWERS_BLOB_PREFIX + jobId + ".json";
    }

    private GenerationJobTypes() {
    }
}
