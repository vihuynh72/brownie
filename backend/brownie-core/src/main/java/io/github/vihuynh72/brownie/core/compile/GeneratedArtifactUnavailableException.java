package io.github.vihuynh72.brownie.core.compile;

/**
 * A server-generated DOCX or PDF did not reach READY through the ordinary
 * artifact pipeline (for example, a malware-scan rejection of Brownie's
 * own output) -- an unexpected, defensive failure rather than a normal
 * fill or render problem.
 */
public class GeneratedArtifactUnavailableException extends RuntimeException {

    public GeneratedArtifactUnavailableException(String message) {
        super(message);
    }
}
