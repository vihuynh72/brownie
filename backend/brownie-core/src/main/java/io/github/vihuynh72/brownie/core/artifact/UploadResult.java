package io.github.vihuynh72.brownie.core.artifact;

/** What a {@link BlobStore} actually observed while writing an object, not what a caller declared in advance. */
public record UploadResult(long byteCount, String sha256Hex) {
}
