package io.github.vihuynh72.brownie.api.export;

import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.export.ExportApprovalRepository;
import io.github.vihuynh72.brownie.core.export.ExportRepository;
import io.github.vihuynh72.brownie.core.export.ExportService;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.validation.ValidationRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ExportConfig {

    @Bean
    ExportService exportService(
            RevisionService revisionService,
            ValidationRepository validationRepository,
            ArtifactService artifactService,
            ExportApprovalRepository exportApprovalRepository,
            ExportRepository exportRepository) {
        return new ExportService(revisionService, validationRepository, artifactService, exportApprovalRepository, exportRepository);
    }
}
