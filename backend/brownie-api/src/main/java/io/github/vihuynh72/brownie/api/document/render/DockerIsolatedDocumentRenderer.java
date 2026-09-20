package io.github.vihuynh72.brownie.api.document.render;

import io.github.vihuynh72.brownie.api.document.pdf.BoundedPdfTextStripper;
import io.github.vihuynh72.brownie.api.document.pdf.PdfReadingBudget;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderException;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.RenderedPdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
 * privilege boundary the launcher spec describes.
 */
public final class DockerIsolatedDocumentRenderer implements DocumentRenderer {

    private static final Logger log = LoggerFactory.getLogger(DockerIsolatedDocumentRenderer.class);

    private static final String RENDERER_VERSION =
            "LibreOffice 4:7.4.7-1+deb12u14 (Debian package version) via brownie-spike-renderer:pinned; "
                    + "extracted with Apache PDFBox " + org.apache.pdfbox.util.Version.getVersion();
    private static final long OUTPUT_MAX_BYTES = 20L * 1024 * 1024;
    private static final long DEADLINE_SECONDS = 60;
    private static final long OUTPUT_LOG_CAP_BYTES = 64L * 1024;

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    /**
     * What comes back from the sandbox is no more trusted than what went in, so it is read under the same
     * limits as an upload: this ceiling on what the library holds, and the reading limits an upload's text is
     * read under.
     */
    private static final long REREAD_MAX_EXPANDED_BYTES = 128L * 1024 * 1024;

    private final String imageTag;
    private final String expectedImageId;

    public DockerIsolatedDocumentRenderer() {
        this("brownie-spike-renderer:pinned", null);
    }

    public DockerIsolatedDocumentRenderer(String imageTag) {
        this(imageTag, null);
    }

    /**
     * {@code expectedImageId} is the image's content address ({@code
     * sha256:...}). A tag can be pointed at a different image by anyone who
     * can build on the host; when an id is given, nothing else is ever run.
     */
    public DockerIsolatedDocumentRenderer(String imageTag, String expectedImageId) {
        this.imageTag = imageTag;
        this.expectedImageId = expectedImageId == null || expectedImageId.isBlank() ? null : expectedImageId.trim();
    }

    @Override
    public RenderedPdf renderToPdf(byte[] docxBytes) {
        Path jobDir;
        try {
            jobDir = Files.createTempDirectory("brownie-render-job-");
        } catch (IOException e) {
            throw new DocumentRenderException("Failed to create an isolated staging directory for rendering.", e);
        }
        try {
            Path inDir = Files.createDirectory(jobDir.resolve("in"));
            Path outDir = Files.createDirectory(jobDir.resolve("out"));
            Path input = inDir.resolve("input.docx");
            Files.write(input, docxBytes);
            makeReadOnly(input);
            makeWorldWritable(outDir);

            // Looked up once and then run by that id, not by the tag: the tag could be pointed somewhere else between
            // the two, and the id is also what is written down as having produced this output.
            String imageId = resolveImageId();
            String containerName = "brownie-render-" + UUID.randomUUID();
            List<String> command = renderCommand(inDir, outDir, containerName, imageId);
            int exitCode = runWithDeadline(command, containerName);
            if (exitCode != 0) {
                throw new DocumentRenderException(
                        "Isolated renderer exited with status " + exitCode + " (a timeout or resource limit kills the same way).");
            }

            byte[] pdfBytes = readRenderedOutput(outDir.resolve("input.pdf"), OUTPUT_MAX_BYTES);
            return new RenderedPdf(pdfBytes, RENDERER_VERSION + "; image " + imageId, extractText(pdfBytes));
        } catch (IOException e) {
            throw new DocumentRenderException("Failed to stage or read back the isolated render job.", e);
        } finally {
            deleteRecursively(jobDir);
        }
    }

    /**
     * Reads what the sandbox left behind without trusting it to be what it
     * should be. The directory is writable from inside the container, so a
     * renderer that had been taken over could leave a symbolic link there
     * instead of a PDF, and a reader that followed it would hand back
     * whatever file on this host the link named. The link is never
     * followed (the open itself refuses one, so there is no moment between
     * checking and reading), only a plain file is accepted, no more than
     * the quota is read however large the file claims to be, and what is
     * read has to begin like a PDF.
     */
    static byte[] readRenderedOutput(Path outputPdf, long maxBytes) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(outputPdf, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            throw new DocumentRenderException("Isolated renderer reported success but produced no output PDF.");
        }
        if (!attributes.isRegularFile()) {
            throw new DocumentRenderException("Isolated renderer left something other than a plain file where its output belongs.");
        }
        byte[] pdfBytes;
        try (SeekableByteChannel channel =
                     Files.newByteChannel(outputPdf, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                InputStream in = Channels.newInputStream(channel)) {
            pdfBytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE, maxBytes));
        }
        if (pdfBytes.length >= maxBytes) {
            throw new DocumentRenderException("Rendered PDF hit the " + maxBytes + "-byte quota; likely truncated.");
        }
        if (pdfBytes.length < PDF_SIGNATURE.length
                || !java.util.Arrays.equals(pdfBytes, 0, PDF_SIGNATURE.length, PDF_SIGNATURE, 0, PDF_SIGNATURE.length)) {
            throw new DocumentRenderException("Isolated renderer's output is not a PDF.");
        }
        return pdfBytes;
    }

    /** The content address of the image the tag names right now, or a refusal if it is not the one this deployment approved. */
    String resolveImageId() {
        String imageId;
        try {
            Process inspect = new ProcessBuilder("docker", "image", "inspect", "--format", "{{.Id}}", imageTag)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // Waited for first, read second: a read has no deadline, and what is printed (one line) is far too
            // little to fill the pipe and hold the command up.
            if (!inspect.waitFor(15, TimeUnit.SECONDS)) {
                inspect.destroyForcibly();
                throw new DocumentRenderException("Looking up the renderer image did not finish in time.");
            }
            byte[] output = inspect.getInputStream().readNBytes(256);
            imageId = new String(output, java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (inspect.exitValue() != 0 || !imageId.matches("sha256:[0-9a-f]{64}")) {
                throw new DocumentRenderException("The renderer image " + imageTag + " is not present on this host.");
            }
        } catch (IOException e) {
            throw new DocumentRenderException("Failed to look up the renderer image.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentRenderException("Interrupted while looking up the renderer image.", e);
        }
        if (expectedImageId != null && !expectedImageId.equals(imageId)) {
            throw new DocumentRenderException(
                    "The renderer image " + imageTag + " is not the approved one, so nothing was rendered with it.");
        }
        return imageId;
    }

    private List<String> renderCommand(Path inDir, Path outDir, String containerName, String imageId) {
        return List.of(
                "docker", "run", "--rm",
                "--name", containerName,
                "--network", "none",
                "--read-only",
                "--tmpfs", "/tmp:rw,size=64m,mode=1777",
                "--tmpfs", "/home/renderer:rw,size=32m,mode=0700,uid=10001,gid=10001",
                "--memory=512m", "--memory-swap=512m",
                "--pids-limit=128",
                "--cpus=1",
                "--cap-drop=ALL",
                "--security-opt", "no-new-privileges",
                "--ulimit", "fsize=" + OUTPUT_MAX_BYTES,
                "-v", inDir.toAbsolutePath() + ":/in:ro",
                "-v", outDir.toAbsolutePath() + ":/out",
                imageId,
                "soffice", "--headless", "--norestore", "--nolockcheck", "--nodefault",
                "-env:UserInstallation=file:///home/renderer/.lo-profile",
                "--convert-to", "pdf", "--outdir", "/out", "/in/input.docx");
    }

    /**
     * {@code destroyForcibly()} below only terminates the local {@code
     * docker run} client process, not the container it launched -- a real,
     * confirmed behavior of the Docker CLI (killing the attached client
     * does not stop the daemon-managed container), verified directly
     * against this exact image before writing this method. Without an
     * explicit {@code docker kill} by name, a hung render would leave its
     * container running on the daemon indefinitely -- resource-capped, but
     * never time-capped -- which is exactly the "hard process deadline"
     * this isolation contract requires. Every exit from this method that
     * is not a normal, verified completion kills the container by its own
     * assigned name before returning or throwing.
     */
    private int runWithDeadline(List<String> command, String containerName) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new DocumentRenderException("Failed to start the isolated renderer process.", e);
        }
        try {
            // Drain stdout on a separate thread. InputStream.readAllBytes() blocks
            // until the stream reaches EOF -- which for a process only happens on
            // exit -- so reading it on this thread before waitFor() would make a
            // hung container's own deadline check unreachable: we would block here
            // forever instead of ever getting to the timeout below. The container's
            // own resource limits (--memory, --cpus, --pids-limit) bound its
            // resource use, not its wall-clock time, so this deadline is the only
            // thing that actually stops a hung render.
            // Bounded, not transferTo(outputBuffer) directly: this buffer exists only
            // to put a few diagnostic lines in a WARN log below, but the stream itself
            // is fully attacker-influenced (a crafted document driving LibreOffice into
            // emitting warnings for the whole deadline window). Capturing it unbounded
            // would let a hostile or merely malformed input grow this buffer on the
            // trusted host JVM's own heap for up to the full deadline, with none of the
            // container's own --memory limit applying to memory held outside it. The
            // drain keeps consuming past the cap (discarding the excess) rather than
            // stopping, so a full pipe can never block the container from exiting.
            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            Thread outputDrain = new Thread(() -> {
                try {
                    drainBounded(process.getInputStream(), outputBuffer, OUTPUT_LOG_CAP_BYTES);
                } catch (IOException ignored) {
                    // The process is being destroyed concurrently; nothing more to capture.
                }
            }, "render-output-drain");
            outputDrain.setDaemon(true);
            outputDrain.start();

            boolean finished = process.waitFor(DEADLINE_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                killContainer(containerName);
                outputDrain.join(TimeUnit.SECONDS.toMillis(5));
                throw new DocumentRenderException("Isolated renderer exceeded its " + DEADLINE_SECONDS + "-second deadline.");
            }
            outputDrain.join(TimeUnit.SECONDS.toMillis(5));
            if (process.exitValue() != 0) {
                log.warn("Isolated renderer failed (exit {}): {}", process.exitValue(), outputBuffer.toString());
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            killContainer(containerName);
            Thread.currentThread().interrupt();
            throw new DocumentRenderException("Interrupted while waiting for the isolated renderer.", e);
        }
    }

    /**
     * Best-effort: {@code --rm} means a container that is still running
     * when killed is removed automatically, and one that already exited
     * on its own has nothing to kill -- a real Docker CLI failure here
     * (daemon unreachable, name already gone) is logged, not thrown, since
     * this always runs on an already-failing or already-timed-out path
     * that must still surface its own original error. Package-private so
     * a test can exercise it directly against a real, deliberately
     * long-running container, the same reasoning {@code
     * ArtifactContentInspector} already gives for its own package-private
     * test seam -- forcing this class's own real 60-second deadline to
     * actually elapse in a test would make that test itself take a minute.
     */
    void killContainer(String containerName) {
        try {
            Process kill = new ProcessBuilder("docker", "kill", containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!kill.waitFor(10, TimeUnit.SECONDS)) {
                kill.destroyForcibly();
                log.warn("docker kill {} did not complete within its own 10-second bound.", containerName);
            }
        } catch (IOException e) {
            log.warn("Failed to invoke docker kill for container {}.", containerName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while killing container {}.", containerName, e);
        }
    }

    /**
     * Keeps reading (and discarding past {@code capBytes}) until the
     * stream reaches EOF, rather than stopping once the cap is hit --
     * stopping early would leave the pipe's own buffer to fill up, which
     * would block the writer (the container process) instead of letting
     * it exit or be killed on schedule, defeating the whole point of
     * draining this stream off the calling thread in the first place.
     */
    private static void drainBounded(java.io.InputStream in, ByteArrayOutputStream out, long capBytes) throws IOException {
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            long remainingCapacity = capBytes - out.size();
            if (remainingCapacity > 0) {
                out.write(chunk, 0, (int) Math.min(read, remainingCapacity));
            }
        }
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

    private void makeReadOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--r--r--"));
        } catch (UnsupportedOperationException | IOException e) {
            // Best effort: the mount into the container is already read-only regardless of host file permissions.
            log.debug("Could not mark staged input read-only on this filesystem.", e);
        }
    }

    /**
     * Without this, the output directory keeps the default {@code
     * createDirectory} mode (owner rwx, group/other r-x) -- which denies
     * write access to the container's fixed, non-root {@code uid=10001}
     * (baked into the pinned image's {@code USER renderer}), since that uid
     * matches neither the host JVM's owning user nor its group. A real
     * Linux Docker host enforces that host-side mode on the bind-mounted
     * directory, so the container's own {@code soffice} process cannot
     * create {@code /out/input.pdf} and the conversion silently produces no
     * output despite the container itself exiting 0. macOS Docker Desktop's
     * bind-mount layer does not enforce these bits the same way, which is
     * why this was never caught testing only on that platform -- confirmed
     * directly by running the pinned image as uid 10001 against a 0755 host
     * directory on both platforms. The job directory is single-use,
     * unpredictably named, and deleted immediately after this call
     * returns, so widening only this short-lived directory to
     * world-writable is a contained trade-off for a uid we cannot chown to
     * without host root.
     */
    private void makeWorldWritable(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("Could not widen render output directory permissions on this filesystem.", e);
        }
    }

    /**
     * Never throws: this runs as a render finishes, and a failure here must
     * not take the place of the render's own result. What cannot be removed
     * (the container runs as another user and may leave something this
     * process cannot even list) is logged and left.
     */
    private void deleteRecursively(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    log.warn("Could not read render staging path {} to clean it up.", file, failure);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                    delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to walk render staging directory {} for cleanup.", root, e);
        }
    }

    private void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to delete render staging path {}.", path, e);
        }
    }
}
