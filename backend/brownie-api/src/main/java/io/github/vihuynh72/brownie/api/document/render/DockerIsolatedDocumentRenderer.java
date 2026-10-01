package io.github.vihuynh72.brownie.api.document.render;

import io.github.vihuynh72.brownie.api.document.pdf.BoundedPdfTextStripper;
import io.github.vihuynh72.brownie.api.document.pdf.PdfReadingBudget;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderException;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.RenderedPdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Renders a DOCX to PDF using one disposable, network-disabled container
 * per call -- the same isolation contract the DOCX-binding spike's own
 * {@code render/launch-job.sh} proved by hand, reimplemented here as a real,
 * directly invocable Java component rather than a shell script a
 * production caller would have to shell out to. The image, executable,
 * every mount path, and every isolation flag are fixed; the only thing a
 * caller supplies is which bytes to convert. No Docker socket is ever
 * handed to anything this process starts -- {@code docker run} itself is
 * invoked as an ordinary subprocess of the trusted caller, the same
 * privilege boundary the launcher spec describes. The sandbox itself is
 * {@link IsolatedLibreOffice}, which converting other formats to Word
 * shares.
 */
public final class DockerIsolatedDocumentRenderer implements DocumentRenderer {

    private static final String RENDERER_VERSION =
            "LibreOffice 4:7.4.7-1+deb12u14 (Debian package version) via brownie-spike-renderer:pinned; "
                    + "extracted with Apache PDFBox " + org.apache.pdfbox.util.Version.getVersion();
    private static final long OUTPUT_MAX_BYTES = 20L * 1024 * 1024;
    private static final Duration DEADLINE = Duration.ofSeconds(60);

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    /**
     * What comes back from the sandbox is no more trusted than what went in, so it is read under the same
     * limits as an upload: this ceiling on what the library holds, and the reading limits an upload's text is
     * read under.
     */
    private static final long REREAD_MAX_EXPANDED_BYTES = 128L * 1024 * 1024;

    private final IsolatedLibreOffice sandbox;

    public DockerIsolatedDocumentRenderer() {
        this("brownie-spike-renderer:pinned", null, null);
    }

    public DockerIsolatedDocumentRenderer(String imageTag) {
        this(imageTag, null, null);
    }

    public DockerIsolatedDocumentRenderer(String imageTag, String expectedImageId) {
        this(imageTag, expectedImageId, null);
    }

    /**
     * {@code expectedImageId} is the image's content address ({@code
     * sha256:...}). A tag can be pointed at a different image by anyone who
     * can build on the host; when an id is given, nothing else is ever run.
     *
     * <p>{@code stagingRoot} is where a job's input and output directories
     * are made. It matters because those directories are handed to the
     * container as mounts, and a mount is resolved by the daemon wherever the
     * daemon runs -- so when this process is itself in a container, the path
     * has to name the same directory on both sides of that boundary. Unset,
     * it is the ordinary temporary directory, which is right whenever this
     * process and the daemon share a filesystem.
     */
    public DockerIsolatedDocumentRenderer(String imageTag, String expectedImageId, String stagingRoot) {
        this.sandbox = new IsolatedLibreOffice(
                imageTag,
                expectedImageId == null || expectedImageId.isBlank() ? null : expectedImageId.trim(),
                stagingRoot == null || stagingRoot.isBlank() ? null : Path.of(stagingRoot.trim()),
                "render",
                "renderer");
    }

    @Override
    public RenderedPdf renderToPdf(byte[] docxBytes) {
        IsolatedLibreOffice.Output output;
        try {
            output = sandbox.run(new IsolatedLibreOffice.Job(
                    docxBytes, "input.docx", null, "pdf", "input.pdf", PDF_SIGNATURE, OUTPUT_MAX_BYTES, DEADLINE));
        } catch (IsolatedLibreOffice.JobFailure failure) {
            throw renderFailure(failure, sandbox.imageTag());
        }
        byte[] pdfBytes = output.bytes();
        return new RenderedPdf(pdfBytes, RENDERER_VERSION + "; image " + output.imageId(), extractText(pdfBytes));
    }

    /**
     * Reads what the sandbox left behind without trusting it to be what it
     * should be: never through a link, only a plain file, never past the
     * quota, and only if it begins like a PDF (see
     * {@link IsolatedLibreOffice#readOutput}).
     */
    static byte[] readRenderedOutput(Path outputPdf, long maxBytes) throws IOException {
        try {
            return IsolatedLibreOffice.readOutput(outputPdf, maxBytes, PDF_SIGNATURE);
        } catch (IsolatedLibreOffice.JobFailure failure) {
            throw renderFailure(failure, null);
        }
    }

    /** The content address of the image the tag names right now, or a refusal if it is not the one this deployment approved. */
    String resolveImageId() {
        try {
            return sandbox.resolveImageId();
        } catch (IsolatedLibreOffice.JobFailure failure) {
            throw renderFailure(failure, sandbox.imageTag());
        }
    }

    /**
     * Stops a container by name, not just the local {@code docker run}
     * client that launched it (killing the client leaves the container
     * running). Package-private so a test can exercise it directly against
     * a real, deliberately long-running container, the same reasoning
     * {@code ArtifactContentInspector} already gives for its own
     * package-private test seam -- forcing this class's own real 60-second
     * deadline to actually elapse in a test would make that test itself
     * take a minute.
     */
    void killContainer(String containerName) {
        sandbox.killContainer(containerName);
    }

    /** Each step's failure, in the words this renderer has always used for it. */
    private static DocumentRenderException renderFailure(IsolatedLibreOffice.JobFailure failure, String imageTag) {
        Throwable cause = failure.getCause();
        String message = switch (failure.step()) {
            case WORKSPACE -> "Failed to create an isolated staging directory for rendering.";
            case STAGING -> "Failed to stage or read back the isolated render job.";
            case IMAGE_LOOKUP_TIMED_OUT -> "Looking up the renderer image did not finish in time.";
            case IMAGE_MISSING -> "The renderer image " + imageTag + " is not present on this host.";
            case IMAGE_LOOKUP_FAILED -> "Failed to look up the renderer image.";
            case IMAGE_LOOKUP_INTERRUPTED -> "Interrupted while looking up the renderer image.";
            case IMAGE_NOT_APPROVED ->
                    "The renderer image " + imageTag + " is not the approved one, so nothing was rendered with it.";
            case START_FAILED -> "Failed to start the isolated renderer process.";
            case TIMED_OUT -> "Isolated renderer exceeded its " + DEADLINE.toSeconds() + "-second deadline.";
            case INTERRUPTED -> "Interrupted while waiting for the isolated renderer.";
            case EXITED -> "Isolated renderer exited with status " + failure.exitStatus()
                    + " (a timeout or resource limit kills the same way).";
            case NO_OUTPUT -> "Isolated renderer reported success but produced no output PDF.";
            case NOT_A_PLAIN_FILE -> "Isolated renderer left something other than a plain file where its output belongs.";
            case OVER_QUOTA -> "Rendered PDF hit the " + failure.quota() + "-byte quota; likely truncated.";
            case WRONG_SIGNATURE -> "Isolated renderer's output is not a PDF.";
        };
        return cause == null ? new DocumentRenderException(message) : new DocumentRenderException(message, cause);
    }

    private String extractText(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(
                pdfBytes, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(REREAD_MAX_EXPANDED_BYTES).streamCache)) {
            return new BoundedPdfTextStripper(PdfReadingBudget.standard()).getText(document);
        } catch (IOException | RuntimeException e) {
            // Unchecked too: that is how the reading limits announce themselves, and how the library fails on a
            // file that is not what it claims to be.
            throw new DocumentRenderException("Rendered PDF could not be independently re-read to extract its text.", e);
        }
    }
}
