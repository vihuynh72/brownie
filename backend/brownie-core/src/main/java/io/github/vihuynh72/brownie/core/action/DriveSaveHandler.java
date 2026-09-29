package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Saving an approved export to the person's Drive: as the exact file, or as
 * a Google Doc that Google converts it into.
 *
 * <p>Before anything is sent, the bytes are read from storage and must still
 * be exactly the bytes approved (their size and SHA-256); the very bytes
 * checked are the bytes sent. Afterwards, what Drive made is read back and
 * compared with the approval: the same id, name and type, not in the trash,
 * in one folder (the top of My Drive), shared with nobody, and for the exact
 * file the same size and a matching checksum. A conversion is checked as a
 * conversion: the same place, name and sharing, a Google Doc, and a count of
 * the filled-in values found in its text.
 *
 * <p>A file saved as it is carries an id reserved in advance, so Drive
 * refuses a second copy and a lost answer can be settled by asking Drive for
 * that id. Drive answers the same for an id it never used and for a file
 * deleted for good, so the id's absence proves "never made" only while no
 * answer from Drive ever named the file, and only several minutes after the
 * last send; once an answer named it, its absence means it was made and
 * deleted since. A conversion cannot carry an id (Drive refuses reserved ids
 * for conversions), so a conversion whose answer was lost stays unknown
 * unless an answer named what it made.
 */
public class DriveSaveHandler implements ActionHandler {

    private final ActionType type;
    private final DriveFileWriter driveFileWriter;
    private final GoogleDocs googleDocs;
    private final ArtifactService artifactService;

    public DriveSaveHandler(ActionType type, DriveFileWriter driveFileWriter, GoogleDocs googleDocs, ArtifactService artifactService) {
        if (type != ActionType.DRIVE_SAVE_FILE && type != ActionType.DRIVE_SAVE_AS_GOOGLE_DOC) {
            throw new IllegalArgumentException("Not a Drive save: " + type);
        }
        this.type = type;
        this.driveFileWriter = Objects.requireNonNull(driveFileWriter, "driveFileWriter");
        this.googleDocs = Objects.requireNonNull(googleDocs, "googleDocs");
        this.artifactService = Objects.requireNonNull(artifactService, "artifactService");
    }

    @Override
    public ActionType type() {
        return type;
    }

    @Override
    public ConnectorAccess access() {
        return ConnectorAccess.DRIVE_SAVING;
    }

    @Override
    public PreparedWrite prepare(ActionRequest action, UsableConnection connection) {
        DriveSavePayload payload = DriveSavePayload.parse(action.payloadCanonical(), type);
        // What was approved is the payload; what is sent follows the record. They must name the same things.
        if (payload.documentId() != action.documentId()
                || !Long.valueOf(payload.revisionId()).equals(action.requiredRevisionId())
                || !Long.valueOf(payload.exportReceiptId()).equals(action.exportReceiptId())
                || payload.connectionId() != action.connectionId()
                || payload.workspaceId() != action.workspaceId()
                || payload.proposedBy() != action.userId()) {
            throw new IllegalStateException("A save's payload does not name what its record names; nothing is sent for it.");
        }
        byte[] bytes = approvedBytes(action, payload);
        NewDriveFile file = payload.conversion()
                ? new NewDriveFile(null, payload.fileName(), payload.mimeType(), DriveSavePayload.GOOGLE_DOC_TYPE, bytes)
                : new NewDriveFile(action.providerKey(), payload.fileName(), payload.mimeType(), null, bytes);
        return new PreparedWrite() {
            @Override
            public WriteAnswer send(UsableConnection usable) {
                return driveFileWriter.createFile(usable.accessToken(), file);
            }

            @Override
            public ActionOutcome readBack(UsableConnection usable, WriteAnswer answer) {
                String id = payload.conversion()
                        ? (answer instanceof WriteAnswer.Applied applied ? applied.externalId() : null)
                        : action.providerKey();
                return id == null ? new ActionOutcome.StillUnknown(null) : verify(usable, payload, id, action.providerKey());
            }
        };
    }

    @Override
    public ActionOutcome reconcile(ActionRequest action, UsableConnection connection, List<ActionAttempt> attempts, Instant now) {
        DriveSavePayload payload = DriveSavePayload.parse(action.payloadCanonical(), type);
        if (payload.conversion()) {
            // Only an answer that named what it made can be followed; without one there is nothing to ask Drive about.
            Optional<String> named = attempts.stream().map(ActionAttempt::externalId).filter(Objects::nonNull).findFirst();
            return named.map(id -> verify(connection, payload, id, null)).orElseGet(() -> new ActionOutcome.StillUnknown(null));
        }
        Optional<SavedDriveFile> saved = driveFileWriter.describeSavedFile(connection.accessToken(), action.providerKey());
        if (saved.isPresent()) {
            return compare(connection, payload, saved.get(), action.providerKey());
        }
        Instant lastSent = attempts.stream().map(ActionAttempt::sentAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        boolean settled = now.isAfter(lastSent.plus(ActionService.SETTLE_INTERVAL));
        // An answer that named the file proves Drive made it: its absence since is a deletion, never "not made".
        boolean madeOnce = attempts.stream().anyMatch(attempt -> action.providerKey().equals(attempt.externalId()));
        if (madeOnce) {
            return settled
                    ? new ActionOutcome.Done(ActionVerification.REMOVED_AFTERWARDS, action.providerKey(), null, null)
                    : new ActionOutcome.StillUnknown(action.providerKey());
        }
        // Right after a lost answer Drive may not show the file yet; after a while its absence means it was never made.
        return settled ? new ActionOutcome.NotApplied() : new ActionOutcome.StillUnknown(null);
    }

    private ActionOutcome verify(UsableConnection connection, DriveSavePayload payload, String id, String reservedId) {
        return driveFileWriter.describeSavedFile(connection.accessToken(), id)
                .map(saved -> compare(connection, payload, saved, reservedId))
                .orElseGet(() -> new ActionOutcome.StillUnknown(id));
    }

    private ActionOutcome compare(UsableConnection connection, DriveSavePayload payload, SavedDriveFile saved, String reservedId) {
        boolean placedAsApproved = payload.fileName().equals(saved.name())
                && !saved.trashed()
                && saved.parentCount() == 1
                && !saved.shared();
        if (payload.conversion()) {
            if (!placedAsApproved || !DriveSavePayload.GOOGLE_DOC_TYPE.equals(saved.mimeType())) {
                return new ActionOutcome.Mismatched(saved.id(), saved.link());
            }
            Optional<GoogleDocContent> converted = googleDocs.read(connection.accessToken(), saved.id());
            if (converted.isEmpty()) {
                return new ActionOutcome.StillUnknown(saved.id());
            }
            ConversionCount count = ConversionCheck.count(payload.checkedValues(), converted.get().text());
            return new ActionOutcome.Done(count.verification(), saved.id(), saved.link(), count);
        }
        boolean sameFile = saved.id().equals(reservedId)
                && placedAsApproved
                && payload.mimeType().equals(saved.mimeType())
                && saved.size() != null && saved.size() == payload.bytes();
        if (!sameFile) {
            return new ActionOutcome.Mismatched(saved.id(), saved.link());
        }
        if (saved.sha256() != null) {
            return saved.sha256().equalsIgnoreCase(payload.sha256())
                    ? new ActionOutcome.Done(ActionVerification.MATCHED, saved.id(), saved.link(), null)
                    : new ActionOutcome.Mismatched(saved.id(), saved.link());
        }
        if (saved.md5() != null) {
            return saved.md5().equalsIgnoreCase(payload.md5())
                    ? new ActionOutcome.Done(ActionVerification.MATCHED, saved.id(), saved.link(), null)
                    : new ActionOutcome.Mismatched(saved.id(), saved.link());
        }
        // Drive has not worked out its checksums yet: the file is there, and its bytes can be checked later.
        return new ActionOutcome.StillUnknown(saved.id());
    }

    /** The stored bytes, read now; they must be the ones approved, or nothing is sent. */
    private byte[] approvedBytes(ActionRequest action, DriveSavePayload payload) {
        byte[] bytes;
        try (ReadableArtifact readable = artifactService.openContent(action.workspaceId(), action.userId(), payload.artifactId())) {
            bytes = readable.content().readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, payload.bytes() + 1));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read an approved file.", e);
        }
        if (bytes.length != payload.bytes() || !sha256(bytes).equals(payload.sha256())) {
            throw new ActionChangedException(ActionFailure.CONTENT_CHANGED);
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a JDK-guaranteed algorithm; this should be unreachable.", e);
        }
    }
}
