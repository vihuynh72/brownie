package io.github.vihuynh72.brownie.core.artifact;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * A READY artifact paired with an open stream of its stored bytes.
 * Closing this closes that stream -- callers should always obtain one in
 * a try-with-resources block rather than closing {@link #content} by hand.
 */
public record ReadableArtifact(Artifact artifact, InputStream content) implements Closeable {

    @Override
    public void close() throws IOException {
        content.close();
    }
}
