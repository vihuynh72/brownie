package io.github.vihuynh72.brownie.api.document.render;

import io.github.vihuynh72.brownie.core.compile.DocumentRenderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What comes back from the sandbox is read as if the sandbox had been taken
 * over, because that is the case the sandbox exists for. No container is
 * needed to prove it: the question is only what this process does with
 * whatever it finds where the output belongs.
 */
class RenderedOutputReadBackTest {

    @TempDir
    Path outDir;

    @Test
    void aLinkLeftWhereTheOutputBelongsIsNeverFollowedToTheFileItNames() throws Exception {
        Path secret = Files.writeString(outDir.resolveSibling("host-secret-" + System.nanoTime() + ".txt"), "%PDF-1.7 not yours to read");
        try {
            Path planted = outDir.resolve("input.pdf");
            Files.createSymbolicLink(planted, secret);

            assertThatThrownBy(() -> DockerIsolatedDocumentRenderer.readRenderedOutput(planted, 1_000_000))
                    .isInstanceOf(DocumentRenderException.class)
                    .hasMessageContaining("other than a plain file");
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    void aDirectoryOrNothingAtAllIsRefused() throws Exception {
        assertThatThrownBy(() -> DockerIsolatedDocumentRenderer.readRenderedOutput(outDir.resolve("input.pdf"), 1_000_000))
                .isInstanceOf(DocumentRenderException.class)
                .hasMessageContaining("no output");
        Path directory = Files.createDirectory(outDir.resolve("input.pdf"));
        assertThatThrownBy(() -> DockerIsolatedDocumentRenderer.readRenderedOutput(directory, 1_000_000))
                .isInstanceOf(DocumentRenderException.class)
                .hasMessageContaining("other than a plain file");
    }

    @Test
    void noMoreThanTheQuotaIsEverReadAndWhatIsReadMustBeginLikeAPdf() throws Exception {
        Path huge = Files.write(outDir.resolve("huge.pdf"), ("%PDF-1.7\n" + "x".repeat(10_000)).getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> DockerIsolatedDocumentRenderer.readRenderedOutput(huge, 4096))
                .isInstanceOf(DocumentRenderException.class)
                .hasMessageContaining("quota");

        Path notAPdf = Files.writeString(outDir.resolve("other.pdf"), "root:x:0:0:root:/root:/bin/sh");
        assertThatThrownBy(() -> DockerIsolatedDocumentRenderer.readRenderedOutput(notAPdf, 4096))
                .isInstanceOf(DocumentRenderException.class)
                .hasMessageContaining("not a PDF");

        Path real = Files.write(outDir.resolve("real.pdf"), "%PDF-1.7\nfine".getBytes(StandardCharsets.US_ASCII));
        assertThat(DockerIsolatedDocumentRenderer.readRenderedOutput(real, 4096)).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
    }
}
