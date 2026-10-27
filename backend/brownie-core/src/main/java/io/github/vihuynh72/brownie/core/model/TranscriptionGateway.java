package io.github.vihuynh72.brownie.core.model;

/**
 * Turns audio bytes into words through the configured provider, the
 * transcription analogue of {@link ModelGateway}: one fixed model per
 * deployment, set by configuration and never by a caller. The bytes go to
 * the model and back in memory -- an implementation stores nothing.
 */
public interface TranscriptionGateway {

    Transcription transcribe(byte[] audio, String fileName) throws ModelTransportException;
}
