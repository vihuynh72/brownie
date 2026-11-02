package io.github.vihuynh72.brownie.api.mcp;

/**
 * A tool call answered "no" on purpose: the short code is what is
 * recorded with the refusal and what a client can switch on, the message
 * is what it reads. Distinct from an exception meaning something broke --
 * a refusal is an expected answer and is never logged as an error.
 */
final class McpToolRefusal extends RuntimeException {

    private final String code;

    McpToolRefusal(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }

    // The refusal codes a client can be told apart; recorded on the tool-call row.
    static final String NOT_FOUND = "NOT_FOUND";
    static final String INSUFFICIENT_SCOPE = "INSUFFICIENT_SCOPE";
    static final String PAYLOAD_CHANGED = "PAYLOAD_CHANGED";
    static final String PROPOSAL_NOT_OPEN = "PROPOSAL_NOT_OPEN";
    static final String PROPOSAL_PENDING = "PROPOSAL_PENDING";
    static final String UNREVIEWED_FIELDS = "UNREVIEWED_FIELDS";
    static final String VALIDATION_BLOCKING = "VALIDATION_BLOCKING";
    static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    static final String NO_FILL_SPOTS = "NO_FILL_SPOTS";
    static final String CONFLICT = "CONFLICT";
}
