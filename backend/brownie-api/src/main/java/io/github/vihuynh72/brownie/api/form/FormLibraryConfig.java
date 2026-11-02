package io.github.vihuynh72.brownie.api.form;

import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.prepare.FillableFormService;
import io.github.vihuynh72.brownie.core.template.FillSpotReviewService;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the form library: the catalog it searches and the shared intake a start runs. */
@Configuration(proxyBeanMethods = false)
class FormLibraryConfig {

    /** The federal and state hosts a catalog URL may point at, subdomains included. */
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "irs.gov", "uscis.gov", "ssa.gov", "health.ny.gov", "cms.gov", "dol.gov", "va.gov", "opm.gov", "state.gov");

    @Bean
    FormIntakeService formIntakeService(
            ArtifactService artifactService,
            FillableFormService fillableFormService,
            TemplateService templateService,
            FillSpotReviewService fillSpotReviewService) {
        return new FormIntakeService(artifactService, fillableFormService, templateService, fillSpotReviewService);
    }

    @Bean
    FormLibraryCatalog formLibraryCatalog(tools.jackson.databind.ObjectMapper objectMapper) {
        return FormLibraryCatalog.load(objectMapper);
    }

    @Bean
    CatalogFormFetcher catalogFormFetcher() {
        return new HttpCatalogFormFetcher(ALLOWED_HOSTS, true);
    }
}
