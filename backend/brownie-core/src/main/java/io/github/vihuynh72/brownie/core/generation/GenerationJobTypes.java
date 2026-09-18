package io.github.vihuynh72.brownie.core.generation;

/**
 * Stable identifiers shared by the two independent processes that must
 * agree on them without depending on each other: the API, which enqueues
 * a job under this exact type and later reads back a result published
 * under this exact output kind, and the trusted worker, which claims a
 * job of this type and publishes its result under this exact kind. Living
 * here, in the one module both already depend on, is what lets each side
 * stay consistent without brownie-worker ever depending on brownie-api.
 *
 * <p>Every object key derived here is prefixed with the owning workspace,
 * so a key can only ever be reproduced by a caller that already knows
 * which workspace a job belongs to -- the API proves that through its
 * tenant-scoped run record before touching any of them, and the worker
 * takes it from the job row it holds a live lease on.
 */
public final class GenerationJobTypes {

    /** Must satisfy {@code core.job.JobType}'s own lowercase-machine-identifier format contract. */
    public static final String EXTRACTION_JOB_TYPE = "generation.extract-facts";

    /** The published-output kind a completed extraction job's own result is filed under. */
    public static final String EXTRACTION_RESULT_OUTPUT_KIND = "extraction-result";

    private static final String RUNS_BLOB_PREFIX = "generation-runs/";

    /**
     * Content-addressed by the frozen input bundle's own hash within its
     * workspace: a retried or duplicate start request that produces a
     * byte-identical bundle reuses the object already staged for it.
     */
    public static String inputBundleObjectKey(long workspaceId, String bundleHash) {
        return RUNS_BLOB_PREFIX + workspaceId + "/inputs/" + bundleHash + ".json";
    }

    /**
     * Deterministic by job alone (never a content hash): unlike the frozen
     * input bundle, this blob is overwritten on every attempt that still
     * has open questions, and there is exactly one such blob per job at a
     * time. The bundle inside carries the attempt that wrote it.
     */
    public static String pendingQuestionsObjectKey(long workspaceId, long jobId) {
        return RUNS_BLOB_PREFIX + workspaceId + "/jobs/" + jobId + "/pending-questions.json";
    }

    /** Deterministic by job alone, for the same reason as {@link #pendingQuestionsObjectKey}. */
    public static String resolvedAnswersObjectKey(long workspaceId, long jobId) {
        return RUNS_BLOB_PREFIX + workspaceId + "/jobs/" + jobId + "/resolved-answers.json";
    }

    private GenerationJobTypes() {
    }
}
