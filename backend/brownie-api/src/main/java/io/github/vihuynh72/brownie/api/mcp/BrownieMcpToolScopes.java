package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.mcp.McpScope;
import java.util.Map;

/**
 * Which scope each tool needs -- one map, read by both the request-level
 * scope check (which answers 403 before dispatch) and the tool layer's
 * own defensive check, so the two can never disagree.
 */
final class BrownieMcpToolScopes {

    static final String LIST_TEMPLATES = "list_templates";
    static final String GET_DOCUMENT = "get_document";
    static final String START_DOCUMENT = "start_document";
    static final String ADD_TEXT_SOURCE = "add_text_source";
    static final String PROPOSE_FILL = "propose_fill";
    static final String GET_FILL_STATUS = "get_fill_status";
    static final String ANSWER_QUESTION = "answer_question";
    static final String CONFIRM_FILL = "confirm_fill";
    static final String EXPORT_DOCUMENT = "export_document";
    static final String GET_EXPORT_LINK = "get_export_link";

    static final Map<String, McpScope> REQUIRED = Map.of(
            LIST_TEMPLATES, McpScope.READ,
            GET_DOCUMENT, McpScope.READ,
            START_DOCUMENT, McpScope.FILL,
            ADD_TEXT_SOURCE, McpScope.FILL,
            PROPOSE_FILL, McpScope.FILL,
            GET_FILL_STATUS, McpScope.FILL,
            ANSWER_QUESTION, McpScope.FILL,
            CONFIRM_FILL, McpScope.FILL,
            EXPORT_DOCUMENT, McpScope.EXPORT,
            GET_EXPORT_LINK, McpScope.EXPORT);

    private BrownieMcpToolScopes() {
    }
}
