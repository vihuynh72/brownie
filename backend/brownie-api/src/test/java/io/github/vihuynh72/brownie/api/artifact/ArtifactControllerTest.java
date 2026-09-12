package io.github.vihuynh72.brownie.api.artifact;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Exercises {@link ArtifactController}'s manual copy loop directly, against
 * stub streams that fail on demand. A read failure (the stored object
 * itself could not be read) and a write failure (almost always the client
 * disconnecting mid-download) must both be handled the same way from the
 * caller's perspective: neither should ever propagate out of {@code copy}
 * as an uncaught exception, since by the time it runs the response headers
 * and a fixed {@code Content-Length} are already committed and there is no
 * well-formed error body left to send.
 */
class ArtifactControllerTest {

    @Test
    void copyTransfersEveryByteOnTheHappyPath() throws IOException {
        byte[] content = "hello world".getBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ArtifactController.copy(new ByteArrayInputStream(content), out, 1L);

        assertArrayEquals(content, out.toByteArray());
    }

    @Test
    void copyReturnsQuietlyWhenReadingTheStoredContentFails() {
        InputStream failingToRead = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("simulated blob read failure");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("simulated blob read failure");
            }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertDoesNotThrow(() -> ArtifactController.copy(failingToRead, out, 1L));
    }

    @Test
    void copyReturnsQuietlyWhenWritingToTheClientFails() {
        InputStream content = new ByteArrayInputStream("hello world".getBytes());
        OutputStream failingToWrite = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("simulated client disconnect");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("simulated client disconnect");
            }
        };

        assertDoesNotThrow(() -> ArtifactController.copy(content, failingToWrite, 1L));
    }
}
