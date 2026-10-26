package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanNotFoundException;
import io.github.vihuynh72.brownie.core.export.ExportNotApprovedException;
import io.github.vihuynh72.brownie.core.export.ExportReceiptNotFoundException;
import io.github.vihuynh72.brownie.core.export.StaleExportApprovalException;
import io.github.vihuynh72.brownie.core.job.JobNotFoundException;
import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import io.github.vihuynh72.brownie.core.mcp.McpInsufficientScopeException;
import io.github.vihuynh72.brownie.core.mcp.McpRequestInvalidException;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import io.github.vihuynh72.brownie.core.mcp.McpToolCallOutcome;
import io.github.vihuynh72.brownie.core.mcp.McpToolCallRepository;
import io.github.vihuynh72.brownie.core.question.QuestionNotFoundException;
import io.github.vihuynh72.brownie.core.revision.DocumentIdempotencyConflictException;
import io.github.vihuynh72.brownie.core.revision.DocumentRevisionConflictException;
import io.github.vihuynh72.brownie.core.revision.PatchProposalNotFoundException;
import io.github.vihuynh72.brownie.core.source.SourceSnapshotNotFoundException;
import io.github.vihuynh72.brownie.core.template.TemplateNotFoundException;
import io.github.vihuynh72.brownie.core.template.TemplateVersionNotFoundException;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * The envelope every tool body runs inside: the caller's scope is checked
 * once more (defense behind the request-level check), the result becomes
 * structured content plus text -- the fields again as JSON first, a
 * summary line second -- and the call is recorded -- OK, REFUSED with
 * its code, or ERROR -- whatever happens.
 * A refusal is an {@code isError} result carrying its code so a client can
 * tell "no" from "broke"; an unexpected exception is logged, recorded,
 * and answered with a message that carries nothing internal.
 */
class McpToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpToolExecutor.class);

    private final ObjectMapper objectMapper;
    private final McpToolCallRepository toolCallRepository;

    McpToolExecutor(ObjectMapper objectMapper, McpToolCallRepository toolCallRepository) {
        this.objectMapper = objectMapper;
        this.toolCallRepository = toolCallRepository;
    }

    /**
     * What a tool body hands back: the structured fields, one line of
     * text, and the document the call turned out to be about when that
     * was only knowable partway through -- a document created by the
     * call, or the one a proposal or receipt id resolved to. The answer
     * sent to the client carries the structured fields as a JSON text
     * block FIRST and the summary line second: some chat apps give the
     * model only a result's text content (Claude reads every text block,
     * OpenAI's connector reads only the first), and the protocol asks
     * for the serialized JSON in a text block alongside structured
     * content, so the data must lead or the model sees the summary and
     * nothing else.
     */
    record ToolResult(Map<String, Object> data, String text, Long documentId) {
        ToolResult(Map<String, Object> data, String text) {
            this(data, text, null);
        }
    }

    McpSchema.CallToolResult run(String tool, Long documentId, Supplier<ToolResult> work) {
        McpCaller caller = McpCallerContext.require();
        try {
            ToolResult result = work.get();
            Long recorded = result.documentId() != null ? result.documentId() : documentId;
            record(caller, tool, recorded, McpToolCallOutcome.OK, null);
            return McpSchema.CallToolResult.builder()
                    .structuredContent(result.data())
                    .addTextContent(objectMapper.writeValueAsString(result.data()))
                    .addTextContent(result.text())
                    .build();
        } catch (McpToolRefusal refusal) {
            record(caller, tool, documentId, McpToolCallOutcome.REFUSED, refusal.code());
            return refused(refusal.code(), refusal.getMessage());
        } catch (McpInsufficientScopeException scope) {
            record(caller, tool, documentId, McpToolCallOutcome.REFUSED, McpToolRefusal.INSUFFICIENT_SCOPE);
            return refused(McpToolRefusal.INSUFFICIENT_SCOPE, scope.getMessage());
        } catch (DocumentNotFoundException | TemplateNotFoundException | TemplateVersionNotFoundException
                | PatchProposalNotFoundException | QuestionNotFoundException | JobNotFoundException
                | SourceSnapshotNotFoundException | SourceSpanNotFoundException
                | ExportReceiptNotFoundException notFound) {
            record(caller, tool, documentId, McpToolCallOutcome.REFUSED, McpToolRefusal.NOT_FOUND);
            return refused(McpToolRefusal.NOT_FOUND, "That id names nothing in this workspace.");
        } catch (DocumentRevisionConflictException | DocumentIdempotencyConflictException
                | StaleExportApprovalException | ExportNotApprovedException conflict) {
            record(caller, tool, documentId, McpToolCallOutcome.REFUSED, McpToolRefusal.CONFLICT);
            return refused(McpToolRefusal.CONFLICT, conflict.getMessage());
        } catch (McpRequestInvalidException | IllegalArgumentException invalid) {
            record(caller, tool, documentId, McpToolCallOutcome.REFUSED, McpToolRefusal.INVALID_ARGUMENT);
            return refused(McpToolRefusal.INVALID_ARGUMENT, invalid.getMessage());
        } catch (RuntimeException failure) {
            log.error("MCP tool {} failed", tool, failure);
            record(caller, tool, documentId, McpToolCallOutcome.ERROR, null);
            return refused("ERROR", "Something went wrong inside the tool; nothing was applied.");
        }
    }

    private void record(McpCaller caller, String tool, Long documentId, McpToolCallOutcome outcome, String code) {
        try {
            toolCallRepository.record(caller.workspaceId(), caller.userId(), caller.grantId(), documentId, tool, outcome, code);
        } catch (RuntimeException e) {
            // The record is the accounting, not the work: a call whose row
            // could not be written still answers, and the failure is loud
            // in the log rather than silent in the record.
            log.warn("MCP tool-call record could not be written (tool={}, outcome={}, code={})", tool, outcome, code, e);
        }
    }

    private static McpSchema.CallToolResult refused(String code, String message) {
        return McpSchema.CallToolResult.builder()
                .isError(true)
                .structuredContent(Map.of("refused", code, "message", message == null ? code : message))
                .addTextContent("Refused: " + code + (message == null ? "" : " -- " + message))
                .build();
    }
}
