package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adding an approved text to the end of a Google Doc Brownie saved.
 *
 * <p>Just before sending, the Doc is looked at again: still where it was, not
 * in the trash, shared exactly as it was when the person approved, and still
 * at the revision the addition names. Anything else ends the action before
 * anything is sent: Google's revision check covers the Doc's text, not who
 * can see it.
 *
 * <p>The text is sent in one request that names that revision, and Google
 * applies it only while the Doc is still at it, whole or not at all. That is
 * what makes it safe to send again once Google shows the Doc still at that
 * revision well after the last request: had any earlier request been
 * applied, the revision would have moved, and the next would be refused.
 * Right after a send, an unchanged revision proves nothing yet (a request
 * whose answer was lost may still be applied), so it is believed only after
 * the settle interval.
 *
 * <p>A refusal with a plain "bad request" is read again, to tell a Doc that
 * changed after the approval from a request Google would not take. After an
 * answer that says it was applied, or when asked later, the Doc is read back:
 * see {@link DocAppendText#read}.
 */
public class DocAppendHandler implements ActionHandler {

    private final GoogleDocs googleDocs;
    private final DriveFileWriter driveFileWriter;

    public DocAppendHandler(GoogleDocs googleDocs, DriveFileWriter driveFileWriter) {
        this.googleDocs = Objects.requireNonNull(googleDocs, "googleDocs");
        this.driveFileWriter = Objects.requireNonNull(driveFileWriter, "driveFileWriter");
    }

    @Override
    public ActionType type() {
        return ActionType.GOOGLE_DOC_APPEND;
    }

    @Override
    public ConnectorAccess access() {
        return ConnectorAccess.DRIVE_SAVING;
    }

    /** An addition has no id at Google: the same text added by another action reads back exactly the same. */
    @Override
    public boolean recognisedByContentOnly() {
        return true;
    }

    @Override
    public PreparedWrite prepare(ActionRequest action, UsableConnection connection) {
        DocAppendPayload payload = DocAppendPayload.parse(action.payloadCanonical());
        requireMatchesRow(action, payload);
        String documentId = Objects.requireNonNull(action.targetExternalId(), "An addition always names the Doc it goes into.");
        requireUnchanged(connection, documentId, payload);
        return new PreparedWrite() {
            @Override
            public WriteAnswer send(UsableConnection usable) {
                WriteAnswer answer = googleDocs.append(usable.accessToken(), documentId, payload.text(), payload.targetRevision());
                if (answer instanceof WriteAnswer.NotAppliedFinal refused && refused.status() != null && refused.status() == 400) {
                    return conflictOrRefusal(usable, documentId, payload, refused);
                }
                return answer;
            }

            @Override
            public ActionOutcome readBack(UsableConnection usable, WriteAnswer answer) {
                // Google said it applied it (or that it was there): an unchanged Doc now is Google being slow, not "not added".
                return decide(usable, documentId, payload, false);
            }
        };
    }

    @Override
    public ActionOutcome reconcile(ActionRequest action, UsableConnection connection, List<ActionAttempt> attempts, Instant now) {
        DocAppendPayload payload = DocAppendPayload.parse(action.payloadCanonical());
        Instant lastSent = attempts.stream().map(ActionAttempt::sentAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        boolean settled = now.isAfter(lastSent.plus(ActionService.SETTLE_INTERVAL));
        return decide(connection, action.targetExternalId(), payload, settled);
    }

    /**
     * The approved payload and the row that is carried out must name the same
     * document, version, export, save and connection: the write goes to the
     * row's Doc, and what was approved is the payload.
     */
    static void requireMatchesRow(ActionRequest action, DocAppendPayload payload) {
        boolean matches = payload.documentId() == action.documentId()
                && Long.valueOf(payload.revisionId()).equals(action.requiredRevisionId())
                && Long.valueOf(payload.exportReceiptId()).equals(action.exportReceiptId())
                && Long.valueOf(payload.targetActionId()).equals(action.targetActionId())
                && payload.connectionId() == action.connectionId()
                && payload.workspaceId() == action.workspaceId()
                && payload.proposedBy() == action.userId();
        if (!matches) {
            throw new IllegalStateException("An addition's payload does not name what its record names; nothing is sent for it.");
        }
    }

    /** The Doc as the person approved adding to it: there, not in the trash, shared as it was, at the same revision. */
    private void requireUnchanged(UsableConnection connection, String documentId, DocAppendPayload payload) {
        Optional<SavedDriveFile> file = driveFileWriter.describeSavedFile(connection.accessToken(), documentId);
        if (file.isEmpty() || file.get().trashed() || file.get().shared() != payload.targetShared()) {
            throw new ActionChangedException(ActionFailure.TARGET_CHANGED);
        }
        Optional<GoogleDocContent> doc = googleDocs.read(connection.accessToken(), documentId);
        if (doc.isEmpty() || !payload.targetRevision().equals(doc.get().revisionId())) {
            throw new ActionChangedException(ActionFailure.TARGET_CHANGED);
        }
    }

    /**
     * A plain refusal is read again: a Doc no longer at the approved revision
     * means the Doc changed since the approval; one still at it means Google
     * would not take the request. Either way this request added nothing, and
     * the Doc was at the approved revision a moment before it was sent. A Doc
     * that now ends with exactly this text on top of the approved one was
     * moved by an earlier request of this addition, landing late: that is
     * read back as added, never reported as a change that stopped it.
     */
    private WriteAnswer conflictOrRefusal(UsableConnection connection, String documentId, DocAppendPayload payload,
            WriteAnswer.NotAppliedFinal refused) {
        try {
            Optional<GoogleDocContent> doc = googleDocs.read(connection.accessToken(), documentId);
            if (doc.isPresent() && !payload.targetRevision().equals(doc.get().revisionId())) {
                if (DocAppendText.read(payload, doc.get()) == DocAppendText.Reading.ADDED) {
                    return new WriteAnswer.Exists(refused.status(), refused.reasons());
                }
                return new WriteAnswer.NotAppliedFinal(refused.status(), refused.reasons(), ActionFailure.TARGET_CHANGED);
            }
        } catch (RuntimeException e) {
            // The refusal stands as Google gave it: nothing was added either way.
        }
        return refused;
    }

    /** {@code settled}: long enough after the last send that an unchanged Doc proves nothing was added. */
    private ActionOutcome decide(UsableConnection connection, String documentId, DocAppendPayload payload, boolean settled) {
        Optional<GoogleDocContent> doc = googleDocs.read(connection.accessToken(), documentId);
        if (doc.isEmpty()) {
            return new ActionOutcome.StillUnknown(null);
        }
        return switch (DocAppendText.read(payload, doc.get())) {
            case ADDED -> new ActionOutcome.Done(ActionVerification.MATCHED, documentId, link(documentId), null);
            case NOT_ADDED -> settled ? new ActionOutcome.NotApplied() : new ActionOutcome.StillUnknown(null);
            case UNKNOWN -> new ActionOutcome.StillUnknown(null);
        };
    }

    /** The Doc's own page, built from its id on Google's own host. */
    static String link(String documentId) {
        return "https://docs.google.com/document/d/" + documentId + "/edit";
    }
}
