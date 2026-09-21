package io.github.vihuynh72.brownie.api.compile;

import io.github.vihuynh72.brownie.api.document.docx.PoiTemplateFiller;
import io.github.vihuynh72.brownie.api.document.render.DockerIsolatedDocumentRenderer;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.compile.CompilationRepository;
import io.github.vihuynh72.brownie.core.compile.CompilationService;
import io.github.vihuynh72.brownie.core.compile.ConcurrencyLimitedDocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
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
     * Every render in the application goes through this one bean (previews,
     * validation, a template's baseline), so this is the one place a
     * ceiling on simultaneous renders covers them all.
     */
    @Bean
    DocumentRenderer documentRenderer(
            @Value("${brownie.render.max-concurrent:2}") int maxConcurrent,
            @Value("${brownie.render.max-wait:PT20S}") Duration maxWait,
            @Value("${brownie.render.image:brownie-spike-renderer:pinned}") String image,
            @Value("${brownie.render.expected-image-id:}") String expectedImageId,
            @Value("${brownie.render.staging-dir:}") String stagingDir) {
        return new ConcurrencyLimitedDocumentRenderer(
                new DockerIsolatedDocumentRenderer(image, expectedImageId, stagingDir), maxConcurrent, maxWait);
    }

    @Bean
    CompilationService compilationService(
            RevisionService revisionService,
            TemplateRepository templateRepository,
            ArtifactService artifactService,
            TemplateFiller templateFiller,
            DocumentRenderer documentRenderer,
            CompilationRepository compilationRepository) {
        return new CompilationService(
                revisionService, templateRepository, artifactService, templateFiller, documentRenderer, compilationRepository);
    }
}
