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
                                "type", "object",
                                "description", "The file the person attached in chat",
                                "properties", Map.of(
                                        "download_url", Map.of("type", "string"),
                                        "file_id", Map.of("type", "string"),
                                        "mime_type", Map.of("type", "string"),
                                        "file_name", Map.of("type", "string")),
                                "required", List.of("download_url", "file_id"),
                                "additionalProperties", false)),
                        "required", List.of("form")))
                .description("ChatGPT only: takes the file object ChatGPT supplies when the person attaches a "
                        + "form in the chat (the \"form\" argument). Brownie fetches it, finds the places it can "
                        + "fill, and answers like get_form_upload does. No other chat app can hand Brownie a file "
                        + "this way, Claude included: call request_form_upload instead and give the person its link.")
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
                    "This chat app passed the attachment as text, so Brownie cannot open it. Call "
                            + "request_form_upload and give the person the link it returns; they upload the form there.");
        }
        if (!(form instanceof Map<?, ?> file)) {
            throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT,
                    "\"form\" must be the file object ChatGPT supplies for an attachment. In any other chat app, "
                            + "call request_form_upload and give the person the link it returns.");
        }
        String url = stringField(file, "download_url");
        String name = stringField(file, "file_name");
        if (name == null || name.isBlank()) {
            name = stringField(file, "name");
        }
        if (name == null || name.isBlank()) {
            name = "form";
        }
        URI uri = checkUrl(url);

        byte[] bytes;
        try {
            bytes = fetcher.fetch(uri);
        } catch (Exception e) {
            throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT,
                    "The file could not be fetched: " + e.getMessage()
                            + " Call request_form_upload and give the person the link it returns; they upload the form there.");
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
            out.put("places_left_out", taken.placesLeftOut());
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
            // The host only, never the path or query: enough to learn what hosts chat apps really send.
            log.info("add_form_file refused a download_url on host {}", host == null ? "" : host);
            throw new McpToolRefusal(McpToolRefusal.INVALID_ARGUMENT,
                    "Brownie could not fetch that file from here. Call request_form_upload and give the person "
                            + "the link it returns; they upload the form there.");
        }
        return uri;
    }
}
