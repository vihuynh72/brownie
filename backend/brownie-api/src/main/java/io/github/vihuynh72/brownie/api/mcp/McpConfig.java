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
            McpSettings settings) {
        return new BrownieMcpTools(
                executor, fillOrchestration, templateService, revisionService, artifactService, documentSourceService,
                downloadLinkService, exportRepository, questionRepository, proposalRepository,
                validationService, exportService, auditRepository, settings);
    }
}
