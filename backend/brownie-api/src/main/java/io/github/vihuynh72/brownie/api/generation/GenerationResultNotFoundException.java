package io.github.vihuynh72.brownie.api.generation;

/** No result has been published for this job yet, whether it is still running or never reached this output kind. */
public final class GenerationResultNotFoundException extends RuntimeException {

    public GenerationResultNotFoundException(long jobId) {
        super("No generation result is published yet for job " + jobId + ".");
    }
}
