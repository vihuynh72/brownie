package io.github.vihuynh72.brownie.core.mcp;

import io.github.vihuynh72.brownie.core.action.CanonicalJson;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.PatchProposal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The exact text a chat client hashes when it confirms a proposal, and
 * the server recomputes from the stored proposal before applying it:
 * canonical JSON of {@code {"proposal": id, "document": id,
 * "base_revision": id, "values": {fieldId: value}}} where each value is
 * its canonical scalar or list-of-scalars form. The same canonicalization
 * {@link CanonicalJson} already gives action payloads is used so a hash
 * names exactly one proposal content, and a client that confirms an
 * edited or replayed payload fails the comparison.
 */
public final class McpConfirmationHash {

    private McpConfirmationHash() {
    }

    /** The canonical payload text for one stored proposal, the same text the client hashed. */
    public static String canonical(PatchProposal proposal) {
        Map<String, Object> values = new LinkedHashMap<>();
        proposal.proposedValues().forEach((fieldId, value) -> values.put(fieldId, canonicalValue(value)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposal", proposal.id());
        payload.put("document", proposal.documentId());
        payload.put("base_revision", proposal.baseRevisionId());
        payload.put("values", values);
        return CanonicalJson.write(payload);
    }

    public static String sha256Hex(PatchProposal proposal) {
        return CanonicalJson.sha256Hex(canonical(proposal));
    }

    /** A field value in its canonical form: scalars as their one string, repeated values as the list of them. */
    private static Object canonicalValue(FieldValue value) {
        return switch (value) {
            case FieldValue.TextValue text -> text.value();
            case FieldValue.DateValue date -> date.value().toString();
            case FieldValue.RepeatedTextValue texts -> texts.values();
            case FieldValue.RepeatedDateValue dates -> dates.values().stream().map(Object::toString).toList();
            case FieldValue.CheckboxValue checkbox -> checkbox.checked() ? "checked" : "unchecked";
        };
    }
}
