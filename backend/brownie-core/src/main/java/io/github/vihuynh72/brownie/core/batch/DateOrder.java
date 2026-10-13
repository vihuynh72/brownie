package io.github.vihuynh72.brownie.core.batch;

/**
 * Which way round a numeric date like "03/04/2025" is read: month first,
 * the American convention, or day first, the convention most of the rest of
 * the world uses. Only ever matters for numeric dates with a four-digit
 * year; text and ISO dates need no help.
 */
public enum DateOrder {
    MDY,
    DMY
}
