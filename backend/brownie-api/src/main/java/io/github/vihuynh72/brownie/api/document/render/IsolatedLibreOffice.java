package io.github.vihuynh72.brownie.api.document.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs LibreOffice once, on one file, in one disposable, network-disabled
 * container -- the same isolation contract the DOCX-binding spike's own
 * {@code render/launch-job.sh} proved by hand, reimplemented here as a real,
 * directly invocable Java component rather than a shell script a
 * production caller would have to shell out to. The image, executable,
 * every mount path, and every isolation flag are fixed; what a caller
 * supplies is which bytes to convert and a {@link Job} naming, from its own
 * constants, what the file is called inside the sandbox, which import filter
 * reads it, what it is converted to, and what the result must look like. No
 * Docker socket is ever handed to anything this process starts -- {@code
 * docker run} itself is invoked as an ordinary subprocess of the trusted
 * caller, the same privilege boundary the launcher spec describes.
 *
 * <p>Rendering a Word document to PDF and converting another format to Word
 * both go through here, so the sandbox is defined in exactly one place.
 * Every failure is a {@link JobFailure} saying which step failed; each caller
 * turns that into its own refusal, because what a failure means differs
 * between them (a render that produces nothing is the renderer's fault; a
 * conversion that produces nothing is usually the file's).
 */
final class IsolatedLibreOffice {

    private static final Logger log = LoggerFactory.getLogger(IsolatedLibreOffice.class);

    private static final long OUTPUT_LOG_CAP_BYTES = 64L * 1024;
    private static final long IMAGE_LOOKUP_SECONDS = 15;
    private static final int STRAY_ENTRIES_REPORTED = 16;
    /** A file name inside the sandbox is always one of the caller's own constants, never anything a file supplied. */
    private static final Pattern FIXED_FILE_NAME = Pattern.compile("[a-z0-9]+\\.[a-z0-9]+");

    private final String imageTag;
    private final String expectedImageId;
    private final Path stagingRoot;
    private final String label;
    private final String role;

    /**
     * {@code label} names the job's staging directory, container and
     * output-drain thread ({@code brownie-<label>-...}); {@code role} is
     * what the logs call the sandboxed program ("renderer", "converter").
     */
    IsolatedLibreOffice(String imageTag, String expectedImageId, Path stagingRoot, String label, String role) {
        this.imageTag = Objects.requireNonNull(imageTag, "imageTag");
        this.expectedImageId = expectedImageId;
        this.stagingRoot = stagingRoot;
        this.label = Objects.requireNonNull(label, "label");
        this.role = Objects.requireNonNull(role, "role");
    }

    String imageTag() {
        return imageTag;
    }

    /**
     * One conversion. {@code inputFilter} may be null only for a caller
     * whose input is always something this process wrote itself; any
     * file that came from outside names its filter, because LibreOffice
     * otherwise decides for itself what the bytes are.
     */
    record Job(byte[] input, String inputName, String inputFilter, String convertTo, String outputName,
               byte[] outputSignature, long outputMaxBytes, Duration deadline) {

        Job {
            Objects.requireNonNull(input, "input");
            requireFixedFileName(inputName);
            requireFixedFileName(outputName);
            Objects.requireNonNull(convertTo, "convertTo");
            Objects.requireNonNull(outputSignature, "outputSignature");
            Objects.requireNonNull(deadline, "deadline");
            if (outputMaxBytes < 1 || deadline.isNegative() || deadline.isZero()) {
                throw new IllegalArgumentException("A job needs a positive output quota and deadline.");
            }
        }

        private static void requireFixedFileName(String name) {
            if (name == null || !FIXED_FILE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Not a fixed file name: " + name);
            }
        }
    }

    /**
     * What came back. {@code strayEntries} names (up to a handful of) other
     * things left in the output directory, which a conversion that did only
     * what it was asked never leaves; whether that matters is the caller's
     * call.
     */
    record Output(byte[] bytes, String imageId, List<String> strayEntries) {
    }

    /** Which step of a job failed. {@code exitStatus} is set for {@link Step#EXITED} only; {@code quota} for {@link Step#OVER_QUOTA}. */
    static final class JobFailure extends RuntimeException {

        enum Step {
            /** The job's staging directory could not be made. */
            WORKSPACE,
            /** The input could not be staged, or the output could not be read back. */
            STAGING,
            IMAGE_LOOKUP_TIMED_OUT,
            IMAGE_MISSING,
            IMAGE_LOOKUP_FAILED,
            IMAGE_LOOKUP_INTERRUPTED,
            IMAGE_NOT_APPROVED,
            /** {@code docker run} itself could not be started. */
            START_FAILED,
            TIMED_OUT,
            INTERRUPTED,
            /** The container ended with a status other than 0: a crash, a resource limit, or Docker itself failing. */
            EXITED,
            /** The container ended normally and left nothing where the output belongs. */
            NO_OUTPUT,
            NOT_A_PLAIN_FILE,
            OVER_QUOTA,
            WRONG_SIGNATURE
        }

        private final Step step;
        private final int exitStatus;
        private final long quota;

        private JobFailure(Step step, int exitStatus, long quota, Throwable cause) {
            super(step.name() + (step == Step.EXITED ? " " + exitStatus : ""), cause);
            this.step = step;
            this.exitStatus = exitStatus;
            this.quota = quota;
        }

        static JobFailure of(Step step) {
            return new JobFailure(step, 0, 0, null);
        }

        static JobFailure of(Step step, Throwable cause) {
            return new JobFailure(step, 0, 0, cause);
        }

        static JobFailure exited(int exitStatus) {
            return new JobFailure(Step.EXITED, exitStatus, 0, null);
        }

        static JobFailure overQuota(long quota) {
            return new JobFailure(Step.OVER_QUOTA, 0, quota, null);
        }

        Step step() {
            return step;
        }

        int exitStatus() {
            return exitStatus;
        }

        long quota() {
            return quota;
        }
    }

    Output run(Job job) {
        Path jobDir;
        try {
            jobDir = stagingRoot == null
                    ? Files.createTempDirectory("brownie-" + label + "-job-")
                    : Files.createTempDirectory(stagingRoot, "brownie-" + label + "-job-");
        } catch (IOException e) {
            throw JobFailure.of(JobFailure.Step.WORKSPACE, e);
        }
        try {
            Path inDir = Files.createDirectory(jobDir.resolve("in"));
            Path outDir = Files.createDirectory(jobDir.resolve("out"));
            Path input = inDir.resolve(job.inputName());
            Files.write(input, job.input());
            makeReadOnly(input);
            makeWorldWritable(outDir);

            // Looked up once and then run by that id, not by the tag: the tag could be pointed somewhere else between
            // the two, and the id is also what is written down as having produced this output.
            String imageId = resolveImageId();
            String containerName = "brownie-" + label + "-" + UUID.randomUUID();
            int exitCode = runWithDeadline(command(job, inDir, outDir, containerName, imageId), containerName, job.deadline());
            if (exitCode != 0) {
                throw JobFailure.exited(exitCode);
            }

            byte[] output = readOutput(outDir.resolve(job.outputName()), job.outputMaxBytes(), job.outputSignature());
            return new Output(output, imageId, strayEntries(outDir, job.outputName()));
        } catch (IOException e) {
            throw JobFailure.of(JobFailure.Step.STAGING, e);
        } finally {
            deleteRecursively(jobDir);
        }
    }

    /**
     * Reads what the sandbox left behind without trusting it to be what it
     * should be. The directory is writable from inside the container, so a
     * program that had been taken over could leave a symbolic link there
     * instead of its output, and a reader that followed it would hand back
     * whatever file on this host the link named. The link is never
     * followed (the open itself refuses one, so there is no moment between
     * checking and reading), only a plain file is accepted, no more than
     * the quota is read however large the file claims to be, and what is
     * read has to begin the way the expected kind of file begins.
     */
    static byte[] readOutput(Path output, long maxBytes, byte[] signature) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(output, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            throw JobFailure.of(JobFailure.Step.NO_OUTPUT);
        }
        if (!attributes.isRegularFile()) {
            throw JobFailure.of(JobFailure.Step.NOT_A_PLAIN_FILE);
        }
        byte[] bytes;
        try (SeekableByteChannel channel =
                     Files.newByteChannel(output, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                InputStream in = Channels.newInputStream(channel)) {
            bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE, maxBytes));
        }
        if (bytes.length >= maxBytes) {
            throw JobFailure.overQuota(maxBytes);
        }
        if (bytes.length < signature.length || !Arrays.equals(bytes, 0, signature.length, signature, 0, signature.length)) {
            throw JobFailure.of(JobFailure.Step.WRONG_SIGNATURE);
        }
        return bytes;
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
            if (!inspect.waitFor(IMAGE_LOOKUP_SECONDS, TimeUnit.SECONDS)) {
                inspect.destroyForcibly();
                throw JobFailure.of(JobFailure.Step.IMAGE_LOOKUP_TIMED_OUT);
            }
            byte[] output = inspect.getInputStream().readNBytes(256);
            imageId = new String(output, StandardCharsets.US_ASCII).trim();
            if (inspect.exitValue() != 0 || !imageId.matches("sha256:[0-9a-f]{64}")) {
                throw JobFailure.of(JobFailure.Step.IMAGE_MISSING);
            }
        } catch (IOException e) {
            throw JobFailure.of(JobFailure.Step.IMAGE_LOOKUP_FAILED, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw JobFailure.of(JobFailure.Step.IMAGE_LOOKUP_INTERRUPTED, e);
        }
        if (expectedImageId != null && !expectedImageId.equals(imageId)) {
            throw JobFailure.of(JobFailure.Step.IMAGE_NOT_APPROVED);
        }
        return imageId;
    }

    private static List<String> command(Job job, Path inDir, Path outDir, String containerName, String imageId) {
        List<String> command = new ArrayList<>(List.of(
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
                "--ulimit", "fsize=" + job.outputMaxBytes(),
                "-v", inDir.toAbsolutePath() + ":/in:ro",
                "-v", outDir.toAbsolutePath() + ":/out",
                imageId,
                "soffice", "--headless", "--norestore", "--nolockcheck", "--nodefault",
                "-env:UserInstallation=file:///home/renderer/.lo-profile"));
        if (job.inputFilter() != null) {
            command.add("--infilter=" + job.inputFilter());
        }
        command.addAll(List.of("--convert-to", job.convertTo(), "--outdir", "/out", "/in/" + job.inputName()));
        return List.copyOf(command);
    }

    /**
     * {@code destroyForcibly()} below only terminates the local {@code
     * docker run} client process, not the container it launched -- a real,
     * confirmed behavior of the Docker CLI (killing the attached client
     * does not stop the daemon-managed container), verified directly
     * against this exact image before writing this method. Without an
     * explicit {@code docker kill} by name, a hung job would leave its
     * container running on the daemon indefinitely -- resource-capped, but
     * never time-capped -- which is exactly the "hard process deadline"
     * this isolation contract requires. Every exit from this method that
     * is not a normal, verified completion kills the container by its own
     * assigned name before returning or throwing.
     */
    private int runWithDeadline(List<String> command, String containerName, Duration deadline) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw JobFailure.of(JobFailure.Step.START_FAILED, e);
        }
        try {
            // Drain stdout on a separate thread. InputStream.readAllBytes() blocks
            // until the stream reaches EOF -- which for a process only happens on
            // exit -- so reading it on this thread before waitFor() would make a
            // hung container's own deadline check unreachable: we would block here
            // forever instead of ever getting to the timeout below. The container's
            // own resource limits (--memory, --cpus, --pids-limit) bound its
            // resource use, not its wall-clock time, so this deadline is the only
            // thing that actually stops a hung job.
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
            }, label + "-output-drain");
            outputDrain.setDaemon(true);
            outputDrain.start();

            boolean finished = process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                killContainer(containerName);
                outputDrain.join(TimeUnit.SECONDS.toMillis(5));
                throw JobFailure.of(JobFailure.Step.TIMED_OUT);
            }
            outputDrain.join(TimeUnit.SECONDS.toMillis(5));
            if (process.exitValue() != 0) {
                log.warn("Isolated {} failed (exit {}): {}", role, process.exitValue(), outputBuffer.toString());
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            killContainer(containerName);
            Thread.currentThread().interrupt();
            throw JobFailure.of(JobFailure.Step.INTERRUPTED, e);
        }
    }

    /**
     * Best-effort: {@code --rm} means a container that is still running
     * when killed is removed automatically, and one that already exited
     * on its own has nothing to kill -- a real Docker CLI failure here
     * (daemon unreachable, name already gone) is logged, not thrown, since
     * this always runs on an already-failing or already-timed-out path
     * that must still surface its own original error.
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
    private static void drainBounded(InputStream in, ByteArrayOutputStream out, long capBytes) throws IOException {
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            long remainingCapacity = capBytes - out.size();
            if (remainingCapacity > 0) {
                out.write(chunk, 0, (int) Math.min(read, remainingCapacity));
            }
        }
    }

    /** Names only: nothing here is opened, and a link is listed as the link it is. */
    private static List<String> strayEntries(Path outDir, String outputName) throws IOException {
        List<String> stray = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(outDir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.equals(outputName)) {
                    stray.add(name);
                    if (stray.size() >= STRAY_ENTRIES_REPORTED) {
                        break;
                    }
                }
            }
        }
        return List.copyOf(stray);
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
     * create its output and the conversion silently produces nothing
     * despite the container itself exiting 0. macOS Docker Desktop's
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
            log.debug("Could not widen {} output directory permissions on this filesystem.", label, e);
        }
    }

    /**
     * Never throws: this runs as a job finishes, and a failure here must
     * not take the place of the job's own result. What cannot be removed
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
                    log.warn("Could not read {} staging path {} to clean it up.", label, file, failure);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                    delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to walk {} staging directory {} for cleanup.", label, root, e);
        }
    }

    private void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to delete {} staging path {}.", label, path, e);
        }
    }
}
