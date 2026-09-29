package io.github.vihuynh72.brownie.core.action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exactly what saving an export to Drive does, as the person approves it:
 * which document and revision, which exported file (its id, size and two
 * checksums of its bytes), the name it gets, where it goes (the top of My
 * Drive), that it is shared with nobody, and whether Google converts it into
 * a Google Doc. For a conversion, {@code checkedValues} are the filled-in
 * values Brownie will look for in the result.
 *
 * <p>{@code nonce} and {@code proposedBy} make every proposal's text, and so
 * its hash, its own. The provider's identifiers are not part of it: nothing
 * a person is shown carries one.
 */
public record DriveSavePayload(
        ActionType type,
        String nonce,
        long proposedBy,
        long workspaceId,
        long documentId,
        long revisionId,
        String documentTitle,
        long connectionId,
        String accountEmail,
        long exportReceiptId,
        long artifactId,
        String format,
        String fileName,
        String mimeType,
        long bytes,
        String sha256,
        String md5,
        List<String> checkedValues) {

    /** What a converted file becomes. */
    public static final String GOOGLE_DOC_TYPE = "application/vnd.google-apps.document";

    public DriveSavePayload {
        Objects.requireNonNull(type, "type");
        if (type != ActionType.DRIVE_SAVE_FILE && type != ActionType.DRIVE_SAVE_AS_GOOGLE_DOC) {
            throw new IllegalArgumentException("Not a Drive save: " + type);
        }
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(documentTitle, "documentTitle");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(md5, "md5");
        checkedValues = List.copyOf(checkedValues);
        if (type == ActionType.DRIVE_SAVE_FILE && !checkedValues.isEmpty()) {
            throw new IllegalArgumentException("Only a conversion has values to check.");
        }
    }

    public boolean conversion() {
        return type == ActionType.DRIVE_SAVE_AS_GOOGLE_DOC;
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
        target.put("place", "MY_DRIVE_TOP");
        target.put("sharing", "NOBODY");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("exportReceipt", exportReceiptId);
        content.put("artifact", artifactId);
        content.put("format", format);
        content.put("fileName", fileName);
        content.put("mimeType", mimeType);
        content.put("bytes", bytes);
        content.put("sha256", sha256);
        content.put("md5", md5);
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("creates", "NEW_FILE");
        effect.put("conversion", conversion() ? "GOOGLE_DOC" : "NONE");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", Payloads.SCHEMA);
        root.put("type", type.name());
        root.put("nonce", nonce);
        root.put("proposedBy", proposedBy);
        root.put("workspace", workspaceId);
        root.put("document", document);
        root.put("account", account);
        root.put("target", target);
        root.put("content", content);
        root.put("effect", effect);
        if (conversion()) {
            root.put("check", Map.of("values", checkedValues));
        }
        return CanonicalJson.write(root);
    }

    /** The payload this canonical text is, refusing anything Brownie would not have written. */
    public static DriveSavePayload parse(String canonical, ActionType type) {
        Map<String, Object> root = Payloads.read(canonical, type);
        Map<String, Object> document = Payloads.object(root, "document");
        Map<String, Object> account = Payloads.object(root, "account");
        Map<String, Object> target = Payloads.object(root, "target");
        Map<String, Object> content = Payloads.object(root, "content");
        Map<String, Object> effect = Payloads.object(root, "effect");
        boolean conversion = type == ActionType.DRIVE_SAVE_AS_GOOGLE_DOC;
        if (!"MY_DRIVE_TOP".equals(Payloads.string(target, "place")) || !"NOBODY".equals(Payloads.string(target, "sharing"))
                || !"NEW_FILE".equals(Payloads.string(effect, "creates"))
                || !(conversion ? "GOOGLE_DOC" : "NONE").equals(Payloads.string(effect, "conversion"))) {
            throw new IllegalStateException("A stored Drive payload names a target or an effect Brownie never proposes.");
        }
        DriveSavePayload payload = new DriveSavePayload(
                type,
                Payloads.string(root, "nonce"),
                Payloads.number(root, "proposedBy"),
                Payloads.number(root, "workspace"),
                Payloads.number(document, "id"),
                Payloads.number(document, "revision"),
                Payloads.string(document, "title"),
                Payloads.number(account, "connection"),
                Payloads.stringOrNull(account, "email"),
                Payloads.number(content, "exportReceipt"),
                Payloads.number(content, "artifact"),
                Payloads.string(content, "format"),
                Payloads.string(content, "fileName"),
                Payloads.string(content, "mimeType"),
                Payloads.number(content, "bytes"),
                Payloads.string(content, "sha256"),
                Payloads.string(content, "md5"),
                conversion ? Payloads.strings(Payloads.object(root, "check"), "values") : List.of());
        // Writing it out again must give back the very text that was stored and hashed.
        if (!payload.canonical().equals(canonical)) {
            throw new IllegalStateException("A stored Drive payload holds something a Drive payload does not.");
        }
        return payload;
    }

    /** What this save is, for recognising the same save proposed twice: the document, the bytes, and whether they are converted. */
    public String siblingKey() {
        return CanonicalJson.sha256Hex(CanonicalJson.write(Map.of(
                "type", type.name(), "document", documentId, "sha256", sha256, "fileName", fileName)));
    }

    /** A payload holds a person's content, which never belongs in a log line. */
    @Override
    public String toString() {
        return "DriveSavePayload[type=" + type + ", document=" + documentId + ", bytes=" + bytes + "]";
    }
}
