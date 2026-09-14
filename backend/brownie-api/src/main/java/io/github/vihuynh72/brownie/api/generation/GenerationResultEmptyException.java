package io.github.vihuynh72.brownie.api.generation;

/** A job's own published result had no resolved scalar field to propose -- every field was left unresolved. */
public final class GenerationResultEmptyException extends RuntimeException {

    public GenerationResultEmptyException(long jobId) {
        super("Generation job " + jobId + " has no resolved fields to propose.");
    }
}
