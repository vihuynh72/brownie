package io.github.vihuynh72.brownie.api.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code add_form_file}: ChatGPT's file-attachment way in. The tool spec is
 * registered programmatically rather than annotated, because the annotation
 * has no {@code _meta} slot and ChatGPT reads {@code openai/fileParams}
 * there to know which argument a chat-uploaded file belongs in.
 *
 * <p>The download URL Brownie may ask for is narrowly allowed:
 * {@code https} on a host ending {@code .oaiusercontent.com}. Anything else
 * -- another host, {@code localhost}, an IP literal, a {@code file:}
 * scheme, or a plain string where the file object belongs (what the mobile
 * client sends as {@code chat_upload}) -- is refused without a request.
 */
final class McpFormFileTool {

    private static final Logger log = LoggerFactory.getLogger(McpFormFileTool.class);

    private McpFormFileTool() {
    }

    static SyncToolSpecification specification(
            McpToolExecutor executor,
            io.github.vihuynh72.brownie.core.mcp.McpFormUploadService uploadService,
            McpFormIntakeService intakeService,
            io.github.vihuynh72.brownie.core.mcp.McpToolCallRepository toolCallRepository,
            io.github.vihuynh72.brownie.core.mcp.McpAuditRepository auditRepository,
            FormFileFetcher fetcher) {
        McpSchema.Tool tool = McpSchema.Tool.builder(BrownieMcpToolScopes.ADD_FORM_FILE, Map.of(
                        "type", "object",
                        "properties", Map.of("form", Map.of(
                                "description", "The file the person attached in chat",
                                // A mobile client sends the attachment as a bare string; letting it
                                // through schema validation means it gets the explained refusal, not a
                                // raw validation error.
                                "anyOf", java.util.List.of(java.util.Map.of("type","object"), java.util.Map.of("type","string")),
                                "properties", Map.of(
                                        "file_id", Map.of("type", "string"),
                                        "download_url", Map.of("type", "string"),
                                        "name", Map.of("type", "string"),
                                        "mime_type", Map.of("type", "string"),
                                        "size", Map.of("type", "number")),
                                "required", List.of("file_id", "download_url", "name")))))
                .description("Fill a form the person attached in this chat: "
                        + "the file object the chat app supplies lands in \"form\". "
                        + "Brownie fetches it, finds the places it can fill, and answers "
                        + "like get_form_upload does -- poll that tool with the upload_id.")
                .annotations(new McpSchema.ToolAnnotations(null, false, false, false, null, null))
                .meta(Map.of("openai/fileParams", List.of("form")))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((transport, request) -> executor.run(
                        BrownieMcpToolScopes.ADD_FORM_FILE, null,
                        () -> handle(request.arguments(), uploadService, intakeService,
                                toolCallRepository, auditRepository, fetcher)))
                .build();
    }

    private static McpToolExecutor.ToolResult handle(
            Object arguments,
            io.github.vihuynh72.brownie.core.mcp.McpFormUploadService uploadService,
            McpFormIntakeService intakeService,
            io.github.vihuynh72.brownie.core.mcp.McpToolCallRepository toolCallRepository,
            io.github.vihuynh72.brownie.core.mcp.McpAuditRepository auditRepository,
            FormFileFetcher fetcher) {
        McpCaller caller = McpCallerContext.require();
        if (!caller.has(McpScope.FILL)) {
            throw new io.github.vihuynh72.brownie.core.mcp.McpInsufficientScopeException(McpScope.FILL);
        }

        Object form = arguments instanceof Map<?, ?> args ? args.get("form") : null;
        if (form instanceof String) {
            // The mobile client sends the attachment as a bare string instead of the file object.
            throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT,
                    "The attachment arrived as text, not a file; attach it in the desktop or web app instead.");
        }
        if (!(form instanceof Map<?, ?> file)) {
            throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT,
                    "\"form\" must be the file object the chat app supplies.");
        }
        String url = stringField(file, "download_url");
        String name = stringField(file, "name");
        if (name == null || name.isBlank()) {
            name = "form";
        }
        URI uri = checkUrl(url);

        byte[] bytes;
        try {
            bytes = fetcher.fetch(uri);
        } catch (Exception e) {
            throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT,
                    "The file could not be fetched: " + e.getMessage());
        }

        var issued = uploadService.create(caller, name);
        long uploadId = issued.upload().id();
        try {
            var taken = intakeService.takeIn(caller.workspaceId(), caller.userId(), name,
                    new ByteArrayInputStream(bytes));
            uploadService.finish(caller.workspaceId(), uploadId, "READY", taken.template().id(), null);
            toolCallRepository.record(caller.workspaceId(), caller.userId(), caller.grantId(), null,
                    BrownieMcpToolScopes.ADD_FORM_FILE, io.github.vihuynh72.brownie.core.mcp.McpToolCallOutcome.OK, null);
            auditRepository.formUploaded(caller.workspaceId(), caller.userId(), uploadId, "READY");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("upload_id", uploadId);
            out.put("state", "READY");
            out.put("template_id", taken.template().id());
            out.put("name", taken.template().displayName());
            out.put("spot_count", taken.activeVersion().fieldDefinitions().size());
            return new McpToolExecutor.ToolResult(out,
                    "Form \"" + name + "\" is template " + taken.template().id() + ", ready to fill.");
        } catch (Exception failure) {
            String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            log.warn("add_form_file upload {} failed intake: {}", uploadId, reason);
            uploadService.finish(caller.workspaceId(), uploadId, "FAILED", null, reason);
            toolCallRepository.record(caller.workspaceId(), caller.userId(), caller.grantId(), null,
                    BrownieMcpToolScopes.ADD_FORM_FILE, io.github.vihuynh72.brownie.core.mcp.McpToolCallOutcome.ERROR,
                    "INTAKE_FAILED");
            auditRepository.formUploaded(caller.workspaceId(), caller.userId(), uploadId, "FAILED");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("upload_id", uploadId);
            out.put("state", "FAILED");
            out.put("reason", reason);
            return new McpToolExecutor.ToolResult(out, "The file could not become a template: " + reason);
        }
    }

    private static String stringField(Map<?, ?> file, String key) {
        Object value = file.get(key);
        return value instanceof String s ? s : null;
    }

    /**
     * Only {@code https} on an {@code *.oaiusercontent.com} host is fetched;
     * everything else -- {@code localhost}, IP literals, {@code file:}, any
     * other scheme or host -- is refused before a byte moves.
     */
    private static URI checkUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT, "The file object has no download_url.");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException bad) {
            throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT, "The download_url is not a URL.");
        }
        String host = uri.getHost();
        boolean allowed = "https".equalsIgnoreCase(uri.getScheme())
                && host != null
                && host.endsWith(".oaiusercontent.com")
                && host.indexOf(':') < 0;
        if (!allowed) {
            throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT,
                    "Only files hosted by the chat app itself can be fetched.");
        }
        return uri;
    }
}
