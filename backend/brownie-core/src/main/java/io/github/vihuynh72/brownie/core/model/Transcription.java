package io.github.vihuynh72.brownie.core.model;

/**
 * What the model heard: the words, and when the provider reports them, the
 * language it decided they were in and how long the audio ran. Either may
 * be absent -- the words are the answer; the rest is colour.
 */
public record Transcription(String text, String language, Double durationSeconds) {
}
