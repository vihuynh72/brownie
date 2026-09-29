package io.github.vihuynh72.brownie.core.action;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exactly what adding a document's text to a Google Doc Brownie saved does,
 * as the person approves it: which document and revision the text is of,
 * the text itself, which saved Google Doc it goes into (named by the save
 * that made it and the Doc's title), the Doc's revision and a fingerprint of
 * its text when it was prepared, whether the Doc is shared, and that the text
 * goes at the end of its first tab. Google applies it only while the Doc is
 * still at that revision.
 *
 * <p>The Doc's id at Google is not part of it; its revision is, because the
 * approval is an approval of adding this text to the Doc as it then was.
 */
public record DocAppendPayload(
        String nonce,
        long proposedBy,
        long workspaceId,
        long documentId,
        long revisionId,
        String documentTitle,
        long connectionId,
        String accountEmail,
        long exportReceiptId,
        long targetActionId,
        String targetTitle,
        String targetRevision,
        String targetTextSha256,
        long targetTextLength,
        boolean targetShared,
        String text) {

    public DocAppendPayload {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(documentTitle, "documentTitle");
        Objects.requireNonNull(targetTitle, "targetTitle");
        Objects.requireNonNull(targetRevision, "targetRevision");
        Objects.requireNonNull(targetTextSha256, "targetTextSha256");
        Objects.requireNonNull(text, "text");
        if (!text.startsWith("\n")) {
            throw new IllegalArgumentException("Added text starts on a paragraph of its own.");
        }
    }

    public String canonical() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", documentId);
        document.put("revision", revisionId);
        document.put("title", documentTitle);
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("connection", connectionId);
        account.put("email", accountEmail);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("kind", "SAVED_GOOGLE_DOC");
        target.put("savedBy", targetActionId);
        target.put("title", targetTitle);
        target.put("revision", targetRevision);
        target.put("textSha256", targetTextSha256);
        target.put("textLength", targetTextLength);
        target.put("shared", targetShared);
        target.put("place", "END_OF_FIRST_TAB");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("exportReceipt", exportReceiptId);
        content.put("text", text);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", Payloads.SCHEMA);
        root.put("type", ActionType.GOOGLE_DOC_APPEND.name());
        root.put("nonce", nonce);
        root.put("proposedBy", proposedBy);
        root.put("workspace", workspaceId);
        root.put("document", document);
        root.put("account", account);
        root.put("target", target);
        root.put("content", content);
        root.put("effect", Map.of("appends", "TEXT", "onlyIfUnchanged", true));
        return CanonicalJson.write(root);
    }

    /** The payload this canonical text is, refusing anything Brownie would not have written. */
    public static DocAppendPayload parse(String canonical) {
        Map<String, Object> root = Payloads.read(canonical, ActionType.GOOGLE_DOC_APPEND);
        Map<String, Object> document = Payloads.object(root, "document");
        Map<String, Object> account = Payloads.object(root, "account");
        Map<String, Object> target = Payloads.object(root, "target");
        Map<String, Object> content = Payloads.object(root, "content");
        DocAppendPayload payload;
        try {
            payload = new DocAppendPayload(
                    Payloads.string(root, "nonce"),
                    Payloads.number(root, "proposedBy"),
                    Payloads.number(root, "workspace"),
                    Payloads.number(document, "id"),
                    Payloads.number(document, "revision"),
                    Payloads.string(document, "title"),
                    Payloads.number(account, "connection"),
                    Payloads.stringOrNull(account, "email"),
                    Payloads.number(content, "exportReceipt"),
                    Payloads.number(target, "savedBy"),
                    Payloads.string(target, "title"),
                    Payloads.string(target, "revision"),
                    Payloads.string(target, "textSha256"),
                    Payloads.number(target, "textLength"),
                    Payloads.bool(target, "shared"),
                    Payloads.string(content, "text"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("A stored Doc payload holds text Brownie would not add.", e);
        }
        // Writing it out again must give back the very text that was stored and hashed, fixed parts included.
        if (!payload.canonical().equals(canonical)) {
            throw new IllegalStateException("A stored Doc payload holds something a Doc payload does not.");
        }
        return payload;
    }

    /** What this addition is, for recognising the same addition proposed twice: the same text into the same Doc. */
    public String siblingKey(String targetDocumentId) {
        return CanonicalJson.sha256Hex(CanonicalJson.write(Map.of(
                "type", ActionType.GOOGLE_DOC_APPEND.name(), "doc", targetDocumentId, "text", CanonicalJson.sha256Hex(text))));
    }

    /** A payload holds a person's content, which never belongs in a log line. */
    @Override
    public String toString() {
        return "DocAppendPayload[document=" + documentId + ", " + text.length() + " characters]";
    }
}
