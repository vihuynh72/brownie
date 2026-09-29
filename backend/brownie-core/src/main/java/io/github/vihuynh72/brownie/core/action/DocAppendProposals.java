package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
import io.github.vihuynh72.brownie.core.connector.Connection;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorService;
import io.github.vihuynh72.brownie.core.connector.ProviderTokenRejectedException;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Proposes adding the text of a document's current version to the end of a
 * Google Doc that Brownie saved for the same person, from the same Google
 * account: the Doc must be one a save of theirs converted and read back.
 *
 * <p>The text is the document as its latest export holds it, worked out
 * again exactly as the export was made, and the export must be of the
 * current version, so what is added is what the person approved for export.
 * The Doc is read now: its revision (which Google gives only to someone who
 * can edit it), a fingerprint of its text, whether it is in the trash or
 * shared. The addition is approved against that revision, and Google applies
 * it only while the Doc is still at it. Nothing is sent to Google here but
 * the reads that say what the Doc is now.
 */
public class DocAppendProposals {

    private final ActionService actionService;
    private final ConnectorService connectorService;
    private final GoogleDocs googleDocs;
    private final DriveFileWriter driveFileWriter;
    private final RevisionService revisionService;
    private final ExportService exportService;
    private final ArtifactService artifactService;
    private final TemplateRepository templateRepository;
    private final TemplateFiller templateFiller;

    public DocAppendProposals(
            ActionService actionService,
            ConnectorService connectorService,
            GoogleDocs googleDocs,
            DriveFileWriter driveFileWriter,
            RevisionService revisionService,
            ExportService exportService,
            ArtifactService artifactService,
            TemplateRepository templateRepository,
            TemplateFiller templateFiller) {
        this.actionService = Objects.requireNonNull(actionService, "actionService");
        this.connectorService = Objects.requireNonNull(connectorService, "connectorService");
        this.googleDocs = Objects.requireNonNull(googleDocs, "googleDocs");
        this.driveFileWriter = Objects.requireNonNull(driveFileWriter, "driveFileWriter");
        this.revisionService = Objects.requireNonNull(revisionService, "revisionService");
        this.exportService = Objects.requireNonNull(exportService, "exportService");
        this.artifactService = Objects.requireNonNull(artifactService, "artifactService");
        this.templateRepository = Objects.requireNonNull(templateRepository, "templateRepository");
        this.templateFiller = Objects.requireNonNull(templateFiller, "templateFiller");
    }

    public ActionRequest propose(long workspaceId, long userId, long documentId, long targetActionId) {
        actionService.requireProposable(workspaceId, userId, ActionType.GOOGLE_DOC_APPEND);
        Document document = revisionService.findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        ExportReceipt receipt = exportService.findLatestReceipt(workspaceId, userId, documentId)
                .orElseThrow(() -> new ActionNotProposableException(ActionNotProposableException.Reason.NO_EXPORT,
                        "This document has not been exported yet."));
        if (receipt.revisionId() != document.currentRevisionId()) {
            throw new ActionNotProposableException(ActionNotProposableException.Reason.EXPORT_STALE,
                    "The document changed after it was last exported.");
        }
        ActionRequest target = actionService.find(workspaceId, userId, targetActionId)
                .filter(action -> action.type() == ActionType.DRIVE_SAVE_AS_GOOGLE_DOC
                        && action.state() == ActionState.SUCCEEDED
                        && action.externalId() != null)
                .orElseThrow(() -> invalid("Text can only be added to a Google Doc that Brownie saved for you."));

        String text = DocAppendText.cleaned(documentText(workspaceId, userId, document));
        if (text.isEmpty()) {
            throw invalid("This version of the document has no text to add.");
        }
        if (text.length() > DocAppendText.MAX_LENGTH) {
            throw invalid("This version's text is longer than Brownie adds to a Google Doc at once.");
        }
        if (VisibleText.hidesSomething(text)) {
            throw new ActionNotProposableException(ActionNotProposableException.Reason.HIDDEN_CHARACTERS,
                    "The text to add holds characters that reorder it or cannot be seen.");
        }

        UsableConnection drive = connectorService.use(workspaceId, userId, ConnectorAccess.DRIVE_SAVING);
        requireSameAccount(workspaceId, userId, target, drive.connection());
        GoogleDocContent doc;
        SavedDriveFile file;
        try {
            doc = googleDocs.read(drive.accessToken(), target.externalId())
                    .orElseThrow(() -> invalid("That Google Doc is no longer in Google Drive, or no longer open to Brownie."));
            file = driveFileWriter.describeSavedFile(drive.accessToken(), target.externalId())
                    .orElseThrow(() -> invalid("That Google Doc is no longer in Google Drive, or no longer open to Brownie."));
        } catch (ProviderTokenRejectedException e) {
            throw connectorService.tokenRefusedDuringUse(drive.connection());
        }
        if (file.trashed()) {
            throw invalid("That Google Doc is in the trash in Google Drive. Take it out of the trash to add to it.");
        }
        // Google gives the revision only to someone who may edit the Doc.
        if (doc.revisionId() == null || doc.revisionId().isBlank()) {
            throw invalid("Brownie may no longer edit that Google Doc.");
        }
        String title = Optional.ofNullable(doc.title()).filter(value -> !value.isBlank()).orElse(file.name() == null ? "" : file.name());

        DocAppendPayload payload = new DocAppendPayload(
                UUID.randomUUID().toString(),
                userId,
                workspaceId,
                documentId,
                receipt.revisionId(),
                Payloads.shownTitle(document.title()),
                drive.connection().id(),
                drive.connection().accountEmail(),
                receipt.id(),
                target.id(),
                Payloads.shownTitle(title),
                doc.revisionId(),
                DocAppendText.sha256(doc.text()),
                doc.text().length(),
                file.shared(),
                text);
        return actionService.propose(workspaceId, userId, new NewAction(
                documentId,
                drive.connection().id(),
                ActionType.GOOGLE_DOC_APPEND,
                payload.canonical(),
                payload.siblingKey(target.externalId()),
                receipt.revisionId(),
                receipt.id(),
                target.id(),
                target.externalId(),
                null));
    }

    /** Only the Google account that made the Doc may add to it; asked now rather than left to the approval to find. */
    private void requireSameAccount(long workspaceId, long userId, ActionRequest target, Connection current) {
        String madeBy = connectorService.connections(workspaceId, userId).stream()
                .filter(connection -> connection.id() == target.connectionId())
                .map(Connection::accountId)
                .findFirst()
                .orElse(null);
        if (!current.accountId().equals(madeBy)) {
            throw invalid("That Google Doc was saved from another Google account. Connect that account for saving to add to it.");
        }
    }

    /** The document's text as its export holds it: the template filled for this version, read back as text. */
    private String documentText(long workspaceId, long userId, Document document) {
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
        return templateFiller.fill(templateBytes, version.fieldDefinitions(), revision.content()).reopenedBodyText();
    }

    private static ActionNotProposableException invalid(String message) {
        return new ActionNotProposableException(ActionNotProposableException.Reason.INVALID, message);
    }
}
