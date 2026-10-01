package io.github.vihuynh72.brownie.api.document.render;

import io.github.vihuynh72.brownie.core.prepare.ConvertedDocument;
import io.github.vihuynh72.brownie.core.prepare.ConverterUnavailableException;
import io.github.vihuynh72.brownie.core.prepare.ConvertibleFormat;
import io.github.vihuynh72.brownie.core.prepare.DocumentConversionException;
import io.github.vihuynh72.brownie.core.prepare.DocumentConverter;
import io.github.vihuynh72.brownie.core.prepare.PagesPackages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Converts a .doc, .rtf, .odt, .ott or .pages file to a Word document in the
 * same disposable, network-disabled container that renders PDFs
 * ({@link IsolatedLibreOffice}), with the same image, limits and deadline.
 *
 * <p>This is the first time LibreOffice reads a file straight from a
 * stranger rather than one Brownie wrote itself, so two things are never
 * left to it. The file's name inside the sandbox is fixed per format, and
 * the import filter is named on the command line: left to guess, LibreOffice
 * reads whatever the bytes look like (it happily turns plain text named
 * .docx into a PDF), and a file built to be read as one format by the
 * detector and as another by a parser is exactly what guessing lets
 * through. With the filter named, bytes that are not that format open as
 * nothing, and that is {@link DocumentConversionException.Reason#CANNOT_OPEN}.
 *
 * <p>What comes back is checked the way a render's output is (never
 * through a link, a plain file, within the quota, beginning like a ZIP
 * package) and is then still untrusted: the caller inspects and cleans it
 * like an upload. The output directory must hold nothing but the one file:
 * a conversion that only converted never leaves anything else, so anything
 * else means something in the file did more than it should, and the result
 * is refused as {@link DocumentConversionException.Reason#DAMAGED}.
 */
public final class DockerIsolatedDocumentConverter implements DocumentConverter {

    private static final Logger log = LoggerFactory.getLogger(DockerIsolatedDocumentConverter.class);

    private static final String LIBREOFFICE_VERSION = "LibreOffice 4:7.4.7-1+deb12u14 (Debian package version)";
    private static final String CONVERT_TO = "docx:MS Word 2007 XML";
    private static final String OUTPUT_NAME = "input.docx";
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04}; // "PK\3\4"
    /** The quota a converted document is read under when none is configured: the same one a render's PDF gets. */
    public static final long DEFAULT_OUTPUT_MAX_BYTES = 20L * 1024 * 1024;
    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(60);

    private final IsolatedLibreOffice sandbox;
    private final long outputMaxBytes;
    private final Duration deadline;

    /** Same meaning as the renderer's: see {@code DockerIsolatedDocumentRenderer(String, String, String)}. */
    public DockerIsolatedDocumentConverter(String imageTag, String expectedImageId, String stagingRoot, long outputMaxBytes) {
        this(imageTag, expectedImageId, stagingRoot, outputMaxBytes, DEFAULT_DEADLINE);
    }

    public DockerIsolatedDocumentConverter(
            String imageTag, String expectedImageId, String stagingRoot, long outputMaxBytes, Duration deadline) {
        if (outputMaxBytes < 1) {
            throw new IllegalArgumentException("The converted document's quota must be at least one byte.");
        }
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        if (deadline.isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("The conversion deadline must be positive.");
        }
        this.outputMaxBytes = outputMaxBytes;
        this.sandbox = new IsolatedLibreOffice(
                imageTag,
                expectedImageId == null || expectedImageId.isBlank() ? null : expectedImageId.trim(),
                stagingRoot == null || stagingRoot.isBlank() ? null : Path.of(stagingRoot.trim()),
                "convert",
                "converter");
    }

    @Override
    public ConvertedDocument convertToDocx(byte[] source, ConvertibleFormat format) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(format, "format");
        if (source.length == 0) {
            throw new DocumentConversionException(DocumentConversionException.Reason.CANNOT_OPEN, "The file is empty.");
        }
        StagedInput staged = stagedInputFor(format);
        byte[] input = format == ConvertibleFormat.PAGES ? PagesPackages.repack(source) : source;

        IsolatedLibreOffice.Output output;
        try {
            output = sandbox.run(new IsolatedLibreOffice.Job(
                    input, staged.name(), staged.filter(), CONVERT_TO, OUTPUT_NAME, ZIP_SIGNATURE, outputMaxBytes, deadline));
        } catch (IsolatedLibreOffice.JobFailure failure) {
            throw conversionFailure(failure, format);
        }
        if (!output.strayEntries().isEmpty()) {
            log.warn("Isolated converter left files it was not asked for ({}) while converting a {} file; refusing its output.",
                    output.strayEntries(), format);
            throw new DocumentConversionException(DocumentConversionException.Reason.DAMAGED,
                    "Converting the file left more behind than the converted document, so the result was not used.");
        }
        return new ConvertedDocument(output.bytes(),
                LIBREOFFICE_VERSION + " via image " + output.imageId() + "; import filter " + staged.filter());
    }

    /** The name the file has inside the sandbox, and the one filter allowed to read it. */
    private record StagedInput(String name, String filter) {
    }

    /** The import filter a format is read with; package-private so a test can check the image registers each one. */
    static String importFilterFor(ConvertibleFormat format) {
        return stagedInputFor(format).filter();
    }

    private static StagedInput stagedInputFor(ConvertibleFormat format) {
        return switch (format) {
            case WORD_97 -> new StagedInput("input.doc", "MS Word 97");
            case WORD_95 -> new StagedInput("input.doc", "MS Word 95");
            case RTF -> new StagedInput("input.rtf", "Rich Text Format");
            case ODT -> new StagedInput("input.odt", "writer8");
            case ODT_TEMPLATE -> new StagedInput("input.ott", "writer8_template");
            case PAGES -> new StagedInput("input.pages", "Apple Pages");
        };
    }

    /**
     * What each failed step says about the file. Anything that went wrong
     * before LibreOffice read a byte (the image, Docker, this host's disk,
     * an interruption) says nothing about it, so the converter is
     * unavailable. Docker reports its own failures to start a container as
     * 125 to 127, which are the same. Otherwise: nothing produced means the
     * file did not open as its format; a crash, a resource limit, or output
     * that is not a Word package means reading it went wrong.
     */
    private static RuntimeException conversionFailure(IsolatedLibreOffice.JobFailure failure, ConvertibleFormat format) {
        Throwable cause = failure.getCause();
        return switch (failure.step()) {
            case WORKSPACE, STAGING -> new ConverterUnavailableException(
                    "The converter's working space could not be prepared or read back.", cause);
            case IMAGE_LOOKUP_TIMED_OUT, IMAGE_MISSING, IMAGE_LOOKUP_FAILED, IMAGE_LOOKUP_INTERRUPTED -> new ConverterUnavailableException(
                    "The converter image could not be looked up on this host.", cause);
            case IMAGE_NOT_APPROVED -> new ConverterUnavailableException(
                    "The converter image is not the approved one, so nothing was converted with it.", cause);
            case START_FAILED -> new ConverterUnavailableException("The converter could not be started.", cause);
            case INTERRUPTED -> new ConverterUnavailableException("Interrupted while waiting for the converter.", cause);
            case EXITED -> failure.exitStatus() >= 125 && failure.exitStatus() <= 127
                    ? new ConverterUnavailableException(
                            "The container runtime could not run the converter (status " + failure.exitStatus() + ").")
                    : new DocumentConversionException(DocumentConversionException.Reason.DAMAGED,
                            "The converter stopped with status " + failure.exitStatus() + " while reading this " + format
                                    + " file (a crash or a resource limit).");
            case TIMED_OUT -> new DocumentConversionException(DocumentConversionException.Reason.TIMED_OUT,
                    "Converting this " + format + " file did not finish in time, so it was stopped.");
            case NO_OUTPUT -> new DocumentConversionException(DocumentConversionException.Reason.CANNOT_OPEN,
                    "The converter could not open this file as " + format + ".");
            case NOT_A_PLAIN_FILE -> new DocumentConversionException(DocumentConversionException.Reason.DAMAGED,
                    "The converter left something other than a plain file where the converted document belongs.");
            case OVER_QUOTA -> new DocumentConversionException(DocumentConversionException.Reason.DAMAGED,
                    "The converted document is larger than the " + failure.quota() + "-byte limit.");
            case WRONG_SIGNATURE -> new DocumentConversionException(DocumentConversionException.Reason.DAMAGED,
                    "The converter's output is not a Word document.");
        };
    }
}
