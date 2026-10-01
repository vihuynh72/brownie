package io.github.vihuynh72.brownie.api.document.render;

import io.github.vihuynh72.brownie.core.prepare.ConvertibleFormat;
import io.github.vihuynh72.brownie.core.prepare.DocumentConversionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules a job is held to before any container starts, and the read-back
 * a conversion's output gets. None of it needs Docker: the question is only
 * what this process accepts from a caller and from the sandbox.
 */
class IsolatedJobRulesTest {

    private static final byte[] ZIP = {0x50, 0x4B, 0x03, 0x04};

    @TempDir
    Path outDir;

    @Test
    void aFileInsideTheSandboxIsOnlyEverCalledByAFixedPlainName() {
        for (String name : new String[] {"../input.doc", "in/input.doc", "/in/input.doc", "input", "Input.DOC", "input.doc ", ""}) {
            assertThatThrownBy(() -> new IsolatedLibreOffice.Job(
                    new byte[] {1}, name, "MS Word 97", "docx:MS Word 2007 XML", "input.docx", ZIP, 1024, Duration.ofSeconds(1)))
                    .as(name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new IsolatedLibreOffice.Job(
                new byte[] {1}, "input.doc", "MS Word 97", "docx:MS Word 2007 XML", "input.docx", ZIP, 1024, Duration.ofSeconds(1))
                .inputName()).isEqualTo("input.doc");
    }

    @Test
    void aConvertedDocumentIsReadBackOnlyIfItBeginsLikeAZipPackage() throws Exception {
        Path docx = Files.write(outDir.resolve("input.docx"), new byte[] {0x50, 0x4B, 0x03, 0x04, 9, 9});
        assertThat(IsolatedLibreOffice.readOutput(docx, 4096, ZIP)).startsWith(ZIP);

        Path pdf = Files.write(outDir.resolve("other.docx"), "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> IsolatedLibreOffice.readOutput(pdf, 4096, ZIP))
                .isInstanceOfSatisfying(IsolatedLibreOffice.JobFailure.class,
                        failure -> assertThat(failure.step()).isEqualTo(IsolatedLibreOffice.JobFailure.Step.WRONG_SIGNATURE));
    }

    @Test
    void anEmptyFileIsRefusedAsUnopenableBeforeAnyContainerStarts() {
        DockerIsolatedDocumentConverter converter =
                new DockerIsolatedDocumentConverter("brownie-image-that-does-not-exist:never", null, null, 1024);

        assertThatThrownBy(() -> converter.convertToDocx(new byte[0], ConvertibleFormat.RTF))
                .isInstanceOfSatisfying(DocumentConversionException.class,
                        refused -> assertThat(refused.reason()).isEqualTo(DocumentConversionException.Reason.CANNOT_OPEN));
    }
}
