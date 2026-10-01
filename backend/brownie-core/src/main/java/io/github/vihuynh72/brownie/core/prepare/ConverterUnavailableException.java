package io.github.vihuynh72.brownie.core.prepare;

/**
 * The converter could not be run: its image is missing or is not the
 * approved one, the container runtime did not answer, or its working space
 * could not be made. Nothing is known about the file, and trying again later
 * may work.
 */
public class ConverterUnavailableException extends RuntimeException {

    public ConverterUnavailableException(String message) {
        super(message);
    }

    public ConverterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
