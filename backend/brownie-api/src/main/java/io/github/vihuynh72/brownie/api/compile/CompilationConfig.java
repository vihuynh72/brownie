package io.github.vihuynh72.brownie.api.compile;

import io.github.vihuynh72.brownie.api.document.docx.PoiTemplateFiller;
import io.github.vihuynh72.brownie.api.document.render.DockerIsolatedDocumentConverter;
import io.github.vihuynh72.brownie.api.document.render.DockerIsolatedDocumentRenderer;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.compile.CompilationRepository;
import io.github.vihuynh72.brownie.core.compile.CompilationService;
import io.github.vihuynh72.brownie.core.compile.ConcurrencyLimitedDocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.PdfTemplateFill;
import io.github.vihuynh72.brownie.core.compile.RenderSlots;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
import io.github.vihuynh72.brownie.core.prepare.ConcurrencyLimitedDocumentConverter;
import io.github.vihuynh72.brownie.core.prepare.DocumentConverter;
import io.github.vihuynh72.brownie.core.prepare.EnabledFormatsDocumentConverter;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
class CompilationConfig {

    @Bean
    TemplateFiller templateFiller() {
        return new PoiTemplateFiller();
    }

    /**
     * The one ceiling on simultaneous isolated containers. Every render in
     * the application (previews, validation, a template's baseline) and
     * every conversion of another format to Word takes its turn here, so
     * this is the one place a ceiling covers them all.
     */
    @Bean
    RenderSlots renderSlots(
            @Value("${brownie.render.max-concurrent:2}") int maxConcurrent,
            @Value("${brownie.render.max-wait:PT20S}") Duration maxWait) {
        return new RenderSlots(maxConcurrent, maxWait);
    }

    @Bean
    DocumentRenderer documentRenderer(
            RenderSlots renderSlots,
            @Value("${brownie.render.image:brownie-spike-renderer:pinned}") String image,
            @Value("${brownie.render.expected-image-id:}") String expectedImageId,
            @Value("${brownie.render.staging-dir:}") String stagingDir) {
        return new ConcurrencyLimitedDocumentRenderer(
                new DockerIsolatedDocumentRenderer(image, expectedImageId, stagingDir), renderSlots);
    }

    /**
     * Runs in the renderer's own image, under its expected content address
     * and staging directory: one sandbox, defined once. A format switched
     * off in {@code brownie.convert.enabled-formats} is refused before it
     * waits for a turn.
     */
    @Bean
    DocumentConverter documentConverter(
            RenderSlots renderSlots,
            @Value("${brownie.render.image:brownie-spike-renderer:pinned}") String image,
            @Value("${brownie.render.expected-image-id:}") String expectedImageId,
            @Value("${brownie.render.staging-dir:}") String stagingDir,
            @Value("${brownie.convert.output-max-bytes:20971520}") long outputMaxBytes,
            @Value("${brownie.convert.enabled-formats:WORD_97,WORD_95,RTF,ODT,ODT_TEMPLATE,PAGES}") String enabledFormats) {
        return new EnabledFormatsDocumentConverter(
                new ConcurrencyLimitedDocumentConverter(
                        new DockerIsolatedDocumentConverter(image, expectedImageId, stagingDir, outputMaxBytes), renderSlots),
                EnabledFormatsDocumentConverter.parseEnabledFormats(enabledFormats));
    }

    @Bean
    CompilationService compilationService(
            RevisionService revisionService,
            TemplateRepository templateRepository,
            ArtifactService artifactService,
            TemplateFiller templateFiller,
            DocumentRenderer documentRenderer,
            CompilationRepository compilationRepository,
            PdfTemplateFill pdfTemplateFill) {
        return new CompilationService(
                revisionService, templateRepository, artifactService, templateFiller, documentRenderer, compilationRepository,
                pdfTemplateFill);
    }
}
