package io.github.vihuynh72.brownie.core.batch;

import java.util.Map;

/**
 * A compose reply after its strict shape was checked: the raw text the
 * model wrote for each field id it was asked about. Trimming, collapsing
 * and dropping unusable texts is the caller's work; this is only the
 * decode.
 */
public record BatchComposeReply(Map<String, String> texts) {
}
