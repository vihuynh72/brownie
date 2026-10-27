package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.mcp.McpAppliedRunRepository;
import io.github.vihuynh72.brownie.core.mcp.McpDownloadLinkRepository;
import io.github.vihuynh72.brownie.core.mcp.McpDownloadLinkService;
import io.github.vihuynh72.brownie.core.mcp.McpGrantRepository;
import io.github.vihuynh72.brownie.core.mcp.McpGrantService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the MCP feature's beans. Everything here exists only while
 * {@code brownie.mcp.enabled} is true: off means the routes are not
 * mapped, the chain is not built and the beans simply are not there --
 * so a disabled deployment answers 404, not a configured-but-inert API.
 * On without a token key refuses start-up instead: a half-configured
 * feature is worse than a loud one.
 */
@Configuration
class McpConfig {

    private static final String ENABLED = "brownie.mcp.enabled";

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    McpSettings mcpSettings(@Value("${brownie.public-origin}") String publicOrigin) {
        return new McpSettings(publicOrigin);
    }

    /**
     * Loaded for its validation alone in this slice: nothing reads the key
     * yet, so nobody injects it, and that is the point -- enabling MCP
     * without the key must fail at start-up, not at the first OAuth call.
     */
    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    McpTokenKey mcpTokenKey(@Value("${brownie.mcp.token-key:}") String tokenKey) {
        return McpTokenKey.fromBase64(tokenKey);
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    McpGrantService mcpGrantService(McpGrantRepository grantRepository) {
        return new McpGrantService(grantRepository);
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    McpDownloadLinkService mcpDownloadLinkService(McpDownloadLinkRepository downloadLinkRepository) {
        return new McpDownloadLinkService(downloadLinkRepository, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    io.github.vihuynh72.brownie.core.mcp.McpFormUploadService mcpFormUploadService(
            io.github.vihuynh72.brownie.core.mcp.McpFormUploadRepository uploadRepository) {
        return new io.github.vihuynh72.brownie.core.mcp.McpFormUploadService(uploadRepository);
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    McpFormIntakeService mcpFormIntakeService(
            io.github.vihuynh72.brownie.core.artifact.ArtifactService artifactService,
            io.github.vihuynh72.brownie.core.document.DocumentExtractionService documentExtractionService,
            io.github.vihuynh72.brownie.core.prepare.PdfFormPreparationService pdfFormPreparationService,
            io.github.vihuynh72.brownie.core.template.TemplateService templateService) {
        return new McpFormIntakeService(
                artifactService, documentExtractionService, pdfFormPreparationService, templateService);
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    FormFileFetcher formFileFetcher() {
        return new OaiFormFileFetcher();
    }

    // ChatGPT's attached-file way in, registered as a spec rather than an
    // annotated tool: the annotation has no _meta slot, and
    // openai/fileParams is what tells ChatGPT which argument a chat
    // upload belongs in.
    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    java.util.List<io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification> addFormFileSpecification(
            McpToolExecutor executor,
            io.github.vihuynh72.brownie.core.mcp.McpFormUploadService uploadService,
            McpFormIntakeService intakeService,
            io.github.vihuynh72.brownie.core.mcp.McpToolCallRepository toolCallRepository,
            io.github.vihuynh72.brownie.core.mcp.McpAuditRepository auditRepository,
            FormFileFetcher fetcher) {
        return java.util.List.of(McpFormFileTool.specification(
                executor, uploadService, intakeService, toolCallRepository, auditRepository, fetcher));
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    McpCallerResolver personalTokenCallerResolver(McpGrantService grantService) {
        return new PersonalTokenCallerResolver(grantService);
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    McpToolExecutor mcpToolExecutor(
            tools.jackson.databind.ObjectMapper objectMapper,
            io.github.vihuynh72.brownie.core.mcp.McpToolCallRepository toolCallRepository) {
        return new McpToolExecutor(objectMapper, toolCallRepository);
    }

    /**
     * The transport the server wiring sees: delegates everything to the
     * real stateless transport except the handler it installs, which it
     * wraps so a request the handler cannot answer becomes a JSON-RPC
     * error response instead of the transport's serialized-exception 500.
     * Primary because the concrete transport bean implements the same
     * interface and must keep serving the HTTP router itself.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    McpJsonRpcErrorBoundary mcpJsonRpcErrorBoundary(
            org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport transport) {
        return new McpJsonRpcErrorBoundary(transport);
    }

    /**
     * Replaces the autoconfigured router bean, which is conditional on a
     * missing bean, so a 5xx the transport produces before any handler
     * runs -- its catch-all serializes the exception into the body -- is
     * answered with the same clean internal-error body instead.
     */
    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    org.springframework.web.servlet.function.RouterFunction<org.springframework.web.servlet.function.ServerResponse>
            webMvcStatelessServerRouterFunction(
                    org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport transport) {
        return transport.getRouterFunction().filter(McpJsonRpcErrorBoundary::rewriteServerErrors);
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    McpFillOrchestration mcpFillOrchestration(
            io.github.vihuynh72.brownie.api.generation.GenerationOrchestrationService orchestrationService,
            io.github.vihuynh72.brownie.core.generation.GenerationRunRepository runRepository,
            io.github.vihuynh72.brownie.core.job.JobCommandRepository jobRepository,
            io.github.vihuynh72.brownie.core.question.QuestionService questionService,
            io.github.vihuynh72.brownie.core.revision.PatchProposalRepository proposalRepository,
            McpAppliedRunRepository appliedRunRepository,
            io.github.vihuynh72.brownie.core.revision.RevisionService revisionService,
            io.github.vihuynh72.brownie.core.template.TemplateService templateService,
            io.github.vihuynh72.brownie.core.source.DocumentSourceService documentSourceService,
            McpSettings settings) {
        return new McpFillOrchestration(
                orchestrationService, runRepository, jobRepository, questionService, proposalRepository,
                appliedRunRepository, revisionService, templateService, documentSourceService, settings);
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    BrownieMcpTools brownieMcpTools(
            McpToolExecutor executor,
            McpFillOrchestration fillOrchestration,
            io.github.vihuynh72.brownie.core.template.TemplateService templateService,
            io.github.vihuynh72.brownie.core.revision.RevisionService revisionService,
            io.github.vihuynh72.brownie.core.artifact.ArtifactService artifactService,
            io.github.vihuynh72.brownie.core.source.DocumentSourceService documentSourceService,
            io.github.vihuynh72.brownie.core.mcp.McpDownloadLinkService downloadLinkService,
            io.github.vihuynh72.brownie.core.export.ExportRepository exportRepository,
            io.github.vihuynh72.brownie.core.question.QuestionRepository questionRepository,
            io.github.vihuynh72.brownie.core.revision.PatchProposalRepository proposalRepository,
            io.github.vihuynh72.brownie.core.validation.ValidationService validationService,
            io.github.vihuynh72.brownie.core.export.ExportService exportService,
            io.github.vihuynh72.brownie.core.mcp.McpAuditRepository auditRepository,
            McpSettings settings,
            io.github.vihuynh72.brownie.core.mcp.McpFormUploadService formUploadService) {
        return new BrownieMcpTools(
                executor, fillOrchestration, templateService, revisionService, artifactService, documentSourceService,
                downloadLinkService, exportRepository, questionRepository, proposalRepository,
                validationService, exportService, auditRepository, settings,
                formUploadService);
    }
}
