package io.github.vihuynh72.brownie.api.compile;

import io.github.vihuynh72.brownie.api.document.docx.PoiTemplateFiller;
import io.github.vihuynh72.brownie.api.document.render.DockerIsolatedDocumentRenderer;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.compile.CompilationRepository;
import io.github.vihuynh72.brownie.core.compile.CompilationService;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CompilationConfig {

    @Bean
    TemplateFiller templateFiller() {
        return new PoiTemplateFiller();
    }

    @Bean
    DocumentRenderer documentRenderer() {
        return new DockerIsolatedDocumentRenderer();
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
