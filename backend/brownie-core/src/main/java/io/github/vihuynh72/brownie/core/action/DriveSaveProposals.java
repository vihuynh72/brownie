package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorService;
import io.github.vihuynh72.brownie.core.connector.ProviderTokenRejectedException;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;
import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.export.ExportReceipt;
import io.github.vihuynh72.brownie.core.export.ExportService;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Proposes saving a document's latest export to the person's Drive, as the
 * exact file (Word or PDF) or as a Google Doc that Google converts it into.
 *
 * <p>Only what was approved for export is saved, and only while it is still
 * the document's content: the latest export receipt must be of the current
 * revision, and must have approved the format asked for. The bytes are read
 * now, and their size and both checksums go into the payload, so that what
 * is later sent can be proved to be exactly what the person approved. For a
 * conversion, the values the template filler wrote into the file are worked
 * out again the same way it did, so the converted Doc can be checked for them.
 * Nothing is sent to Drive here except the request that reserves an id for a
 * file saved as it is.
 */
public class DriveSaveProposals {

    /** Google documents its one-request upload for files up to 5 MB; an exported document is tens of kilobytes. */
    public static final long MAX_FILE_BYTES = 5_000_000;
    /** Google's conversion has no documented time, and all of it must fit in one request's deadline. */
    public static final long MAX_CONVERSION_BYTES = 2_000_000;
    static final int MAX_CHECKED_VALUES = 200;
    static final int MAX_CHECKED_VALUE_LENGTH = 2_000;
    /** What the checked values may take in a payload together, well inside what the database keeps of one. */
    static final int MAX_CHECKED_TOTAL_LENGTH = 200_000;
    static final int MAX_FILE_NAME_LENGTH = 200;

    /** What the person asks for. */
    public enum Kind {
        WORD_FILE,
        PDF_FILE,
        GOOGLE_DOC
    }

    private final ActionService actionService;
    private final ConnectorService connectorService;
    private final DriveFileWriter driveFileWriter;
    private final RevisionService revisionService;
    private final ExportService exportService;
    private final ArtifactService artifactService;
    private final TemplateRepository templateRepository;
    private final TemplateFiller templateFiller;

    public DriveSaveProposals(
            ActionService actionService,
            ConnectorService connectorService,
            DriveFileWriter driveFileWriter,
            RevisionService revisionService,
            ExportService exportService,
            ArtifactService artifactService,
            TemplateRepository templateRepository,
            TemplateFiller templateFiller) {
        this.actionService = actionService;
        this.connectorService = connectorService;
        this.driveFileWriter = driveFileWriter;
        this.revisionService = revisionService;
        this.exportService = exportService;
        this.artifactService = artifactService;
        this.templateRepository = templateRepository;
        this.templateFiller = templateFiller;
    }

    public ActionRequest propose(long workspaceId, long userId, long documentId, Kind kind) {
        ActionType type = kind == Kind.GOOGLE_DOC ? ActionType.DRIVE_SAVE_AS_GOOGLE_DOC : ActionType.DRIVE_SAVE_FILE;
        actionService.requireProposable(workspaceId, userId, type);
        Document document = revisionService.findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        ExportReceipt receipt = exportService.findLatestReceipt(workspaceId, userId, documentId)
                .orElseThrow(() -> new ActionNotProposableException(ActionNotProposableException.Reason.NO_EXPORT,
                        "This document has not been exported yet."));
        if (receipt.revisionId() != document.currentRevisionId()) {
            throw new ActionNotProposableException(ActionNotProposableException.Reason.EXPORT_STALE,
                    "The document changed after it was last exported.");
        }
        boolean pdf = kind == Kind.PDF_FILE;
        if (!pdf && receipt.docxArtifactId() == null) {
            throw new ActionNotProposableException(ActionNotProposableException.Reason.FORMAT_NOT_EXPORTED,
                    "This is a PDF form, so Brownie saves it as a PDF. It does not turn it into a Word file or a Google Doc.");
        }
        if (!exportOffers(receipt, kind)) {
            throw new ActionNotProposableException(ActionNotProposableException.Reason.FORMAT_NOT_EXPORTED,
                    "The latest export did not include this format.");
        }
        if (VisibleText.hidesSomething(document.title())) {
            throw new ActionNotProposableException(ActionNotProposableException.Reason.HIDDEN_CHARACTERS,
                    "The document's title holds characters that reorder it or cannot be seen.");
        }
        Long artifactId = pdf ? receipt.pdfArtifactId() : receipt.docxArtifactId();
        String expectedSha256 = pdf ? receipt.pdfSha256() : receipt.docxSha256();
        long limit = kind == Kind.GOOGLE_DOC ? MAX_CONVERSION_BYTES : MAX_FILE_BYTES;
        ExportedFile exported = readExported(workspaceId, userId, artifactId, expectedSha256, limit);
        SupportedMediaType media = pdf ? SupportedMediaType.PDF : SupportedMediaType.DOCX;
        List<String> checkedValues = kind == Kind.GOOGLE_DOC ? filledValues(workspaceId, userId, document) : List.of();

        UsableConnection drive = connectorService.use(workspaceId, userId, ConnectorAccess.DRIVE_SAVING);
        String reservedId = null;
        if (type == ActionType.DRIVE_SAVE_FILE) {
            try {
                reservedId = driveFileWriter.reserveFileId(drive.accessToken());
            } catch (ProviderTokenRejectedException e) {
                throw connectorService.tokenRefusedDuringUse(drive.connection());
            }
        }
        DriveSavePayload payload = new DriveSavePayload(
                type,
                UUID.randomUUID().toString(),
                userId,
                workspaceId,
                documentId,
                receipt.revisionId(),
                Payloads.shownTitle(document.title()),
                drive.connection().id(),
                drive.connection().accountEmail(),
                receipt.id(),
                artifactId,
                pdf ? "PDF" : "DOCX",
                fileName(document.title(), kind == Kind.GOOGLE_DOC ? null : media.defaultFileExtension()),
                media.mimeType(),
                exported.bytes(),
                exported.sha256(),
                exported.md5(),
                checkedValues);
        return actionService.propose(workspaceId, userId, new NewAction(
                documentId,
                drive.connection().id(),
                type,
                payload.canonical(),
                payload.siblingKey(),
                receipt.revisionId(),
                receipt.id(),
                null,
                null,
                reservedId));
    }

    /**
     * Whether an export offers the file a save would send, exactly as its
     * download links do: the PDF when one was made for a PDF export, and the
     * Word file when there is one, unless the export was of the PDF alone
     * and that PDF was made. A conversion is made from the Word file, so it
     * goes with it; a PDF form's export has no Word file at all.
     */
    static boolean exportOffers(ExportReceipt receipt, Kind kind) {
        return kind == Kind.PDF_FILE
                ? receipt.pdfArtifactId() != null && receipt.format() != ExportFormat.DOCX
                : receipt.docxArtifactId() != null && (receipt.format() != ExportFormat.PDF || receipt.pdfArtifactId() == null);
    }

    private record ExportedFile(long bytes, String sha256, String md5) {
    }

    /** The exported bytes as stored now, checked against the receipt, and never more than can be saved. */
    private ExportedFile readExported(long workspaceId, long userId, long artifactId, String expectedSha256, long limit) {
        try (ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId)) {
            Artifact artifact = readable.artifact();
            if (artifact.byteCount() == null || artifact.byteCount() > limit) {
                throw new ActionNotProposableException(ActionNotProposableException.Reason.FILE_TOO_LARGE,
                        "The exported file is larger than Brownie saves to Drive.");
            }
            byte[] bytes = readable.content().readNBytes((int) limit + 1);
            if (bytes.length > limit) {
                throw new ActionNotProposableException(ActionNotProposableException.Reason.FILE_TOO_LARGE,
                        "The exported file is larger than Brownie saves to Drive.");
            }
            String sha256 = hex("SHA-256", bytes);
            if (!sha256.equals(expectedSha256)) {
                throw new IllegalStateException("A stored export no longer has the bytes its receipt names; it is not saved anywhere.");
            }
            return new ExportedFile(bytes.length, sha256, hex("MD5", bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read an exported file.", e);
        }
    }

    /**
     * The text the template filler writes into the file for this revision,
     * worked out again exactly as it does: long-form dates, each repeated
     * item on its own. Distinct values, in the filler's order, bounded.
     */
    private List<String> filledValues(long workspaceId, long userId, Document document) {
        DocumentRevision revision = revisionService.findRevision(workspaceId, userId, document.id(), document.currentRevisionId())
                .orElseThrow(() -> new DocumentNotFoundException(document.id()));
        TemplateVersion version = templateRepository.findVersion(workspaceId, userId, document.templateId(), document.templateVersionId())
                .orElseThrow(() -> new DocumentNotFoundException(document.id()));
        byte[] templateBytes;
        try (ReadableArtifact readable = artifactService.openContent(workspaceId, userId, version.sourceArtifactId())) {
            templateBytes = readable.content().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the template a document was filled from.", e);
        }
        Set<String> values = new LinkedHashSet<>();
        templateFiller.fill(templateBytes, version.fieldDefinitions(), revision.content()).intendedText().values()
                .forEach(texts -> texts.stream().filter(text -> !text.isBlank()).forEach(values::add));
        return bounded(values);
    }

    /**
     * At most so many values, each cut to a length at a whole character, and
     * no more than fit together in a payload: a sample of what to look for,
     * so a shorter list only means fewer values are looked for. A value is
     * composed first, as the check composes both sides, and never cut
     * between a letter and a mark that belongs to it, which would leave a
     * bare letter the converted text does not hold.
     */
    static List<String> bounded(Iterable<String> values) {
        List<String> bounded = new ArrayList<>();
        int total = 0;
        for (String value : values) {
            String cut = cutAtWholeCharacter(Normalizer.normalize(value, Normalizer.Form.NFC), MAX_CHECKED_VALUE_LENGTH);
            if (cut.isEmpty()) {
                continue;
            }
            int written = CanonicalJson.writtenLength(cut) + 1;
            if (bounded.size() == MAX_CHECKED_VALUES || total + written > MAX_CHECKED_TOTAL_LENGTH) {
                break;
            }
            bounded.add(cut);
            total += written;
        }
        return bounded;
    }

    private static String cutAtWholeCharacter(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        // While the first code point left out is a mark, the one before it belongs with it and is left out too.
        while (end > 0 && isMark(value.codePointAt(end))) {
            end = value.offsetByCodePoints(end, -1);
        }
        return value.substring(0, end);
    }

    private static boolean isMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    /** The document's title as a file name, on one line and of a length Drive and every page show whole. */
    static String fileName(String title, String extension) {
        String oneLine = title.strip().replaceAll("\\p{Cntrl}+", " ");
        String base = oneLine.isEmpty() ? "Document" : oneLine;
        int maxBase = MAX_FILE_NAME_LENGTH - (extension == null ? 0 : extension.length() + 1);
        if (base.codePointCount(0, base.length()) > maxBase) {
            base = base.substring(0, base.offsetByCodePoints(0, maxBase)).strip();
        }
        return extension == null ? base : base + "." + extension;
    }

    private static String hex(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is a JDK-guaranteed algorithm; this should be unreachable.", e);
        }
    }
}
