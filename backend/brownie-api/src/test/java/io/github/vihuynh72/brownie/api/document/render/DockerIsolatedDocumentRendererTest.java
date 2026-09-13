package io.github.vihuynh72.brownie.api.document.render;

import io.github.vihuynh72.brownie.api.document.docx.PoiTemplateFiller;
import io.github.vihuynh72.brownie.core.compile.FilledDocument;
import io.github.vihuynh72.brownie.core.compile.RenderedPdf;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplate;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplateRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real, pinned isolated-renderer image built during the
 * rendering feasibility spike -- not a mock -- so this proves the actual
 * network-disabled, resource-capped container conversion this plan
 * requires actually runs and produces a real PDF. Requires the local
 * {@code brownie-spike-renderer:pinned} image to already exist; skipped
 * environments (no Docker) will fail loudly rather than silently pass.
 */
class DockerIsolatedDocumentRendererTest {

    private final PoiTemplateFiller filler = new PoiTemplateFiller();
    private final DockerIsolatedDocumentRenderer renderer = new DockerIsolatedDocumentRenderer();

    @Test
    void rendersARealFilledBuiltInTemplateToAValidInspectablePdf() throws IOException {
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("table-led-meeting-minutes").orElseThrow();
        byte[] blank = Files.readAllBytes(fixturePath(template.templateFixturePath()));
        DocumentContent content = new DocumentContent(Map.of(
                "meeting.title", new FieldValue.TextValue("Spring Budget Planning"),
                "meeting.organization", new FieldValue.TextValue("Riverside Robotics Club"),
                "meeting.date", new FieldValue.DateValue(LocalDate.of(2026, 3, 5)),
                "action.item.task", new FieldValue.RepeatedTextValue(List.of("Reserve the van")),
                "action.item.owner", new FieldValue.RepeatedTextValue(List.of("Alex Chen")),
                "action.item.due", new FieldValue.RepeatedDateValue(List.of(LocalDate.of(2026, 3, 10)))));

        FilledDocument filled = filler.fill(blank, template.fields(), content);
        RenderedPdf rendered = renderer.renderToPdf(filled.docxBytes());

        assertTrue(rendered.pdfBytes().length > 0);
        assertTrue(new String(rendered.pdfBytes(), 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"));
        assertTrue(rendered.extractedText().contains("Spring Budget Planning"));
        assertTrue(rendered.extractedText().contains("Riverside Robotics Club"));
        assertTrue(rendered.extractedText().contains("Reserve the van"));
        assertTrue(rendered.extractedText().contains("Alex Chen"));
        assertTrue(!rendered.rendererVersion().isBlank());
    }

    /**
     * A real, surprising finding from actually running this against the
     * pinned image rather than assuming: LibreOffice's own format
     * auto-detection does not refuse a ".docx"-named file whose bytes are
     * plain text -- it falls back to importing it as text and still
     * produces a valid one-page PDF (confirmed independently with a bare
     * {@code docker run} before writing this assertion). This means
     * rejecting an unsupported or malformed input is this plan's own
     * preflight responsibility, not something the renderer can be trusted
     * to refuse on the isolated conversion's behalf.
     */
    @Test
    void nonDocxBytesAreLenientlyConvertedRatherThanRejectedByLibreOfficeItself() {
        byte[] notActuallyADocx = "this is not a docx".getBytes(StandardCharsets.UTF_8);

        RenderedPdf rendered = renderer.renderToPdf(notActuallyADocx);

        assertTrue(rendered.pdfBytes().length > 0);
        assertTrue(rendered.extractedText().contains("this is not a docx"));
    }

    /**
     * Proves the actual bug once found here: killing the local {@code
     * docker run} client process does not stop the container it launched
     * -- confirmed directly against this exact image with a bare {@code
     * docker run ... sleep 120 &} followed by {@code kill -9} on the
     * client PID before this fix existed; the container kept running.
     * This starts a real, deliberately long-lived container under the
     * pinned image, calls the renderer's own kill path directly (forcing
     * the class's real 60-second deadline to actually elapse would make
     * this test itself take a minute), and confirms with a real {@code
     * docker inspect} that the container is genuinely gone afterward, not
     * just detached from.
     */
    @Test
    void killContainerActuallyStopsTheContainerNotJustTheLocalDockerClient() throws Exception {
        String containerName = "brownie-render-test-kill-" + java.util.UUID.randomUUID();
        Process longRunning = new ProcessBuilder(
                        "docker", "run", "--rm", "--name", containerName,
                        "brownie-spike-renderer:pinned", "sleep", "120")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            awaitRunning(containerName);

            renderer.killContainer(containerName);

            assertTrue(waitUntilGone(containerName), "container " + containerName + " was still running after killContainer");
        } finally {
            longRunning.destroy();
            new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /**
     * A generous 15-second budget, not 5: this runs right after two other
     * tests in this class each launched their own {@code --cpus=1
     * --memory=512m} LibreOffice container, and on a shared, 2-vCPU CI
     * runner (unlike this author's own faster local machine) the daemon
     * scheduling a fresh container while those cgroups are still settling
     * can genuinely take longer than a locally-tuned constant assumed.
     */
    private static void awaitRunning(String containerName) throws Exception {
        for (int i = 0; i < 150; i++) {
            if (isRunning(containerName)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("container " + containerName + " never reached running state");
    }

    private static boolean waitUntilGone(String containerName) throws Exception {
        for (int i = 0; i < 150; i++) {
            if (!isRunning(containerName)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static boolean isRunning(String containerName) throws Exception {
        Process ps = new ProcessBuilder("docker", "ps", "--filter", "name=" + containerName, "--format", "{{.Names}}")
                .redirectErrorStream(true)
                .start();
        String output = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        ps.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        return output.contains(containerName);
    }

    private static Path fixturePath(String repositoryRelativePath) {
        Path path = repositoryRoot().resolve(repositoryRelativePath);
        assertTrue(Files.isRegularFile(path), () -> "missing fixture: " + path);
        return path;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("fixtures/public/templates"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("could not locate the repository root from the test working directory");
    }
}
