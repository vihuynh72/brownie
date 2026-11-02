package io.github.vihuynh72.brownie.core.detail;

/**
 * How a saved detail got its value: {@code AUTO} when an approved fill put
 * it there, {@code MANUAL} when the person wrote it in by hand (the only
 * way a sensitive value can be saved at all).
 */
public enum DetailOrigin {
    AUTO,
    MANUAL
}
