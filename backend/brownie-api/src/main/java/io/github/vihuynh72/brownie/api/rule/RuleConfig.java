package io.github.vihuynh72.brownie.api.rule;

import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.rule.RuleService;
import io.github.vihuynh72.brownie.core.rule.RuleVocabulary;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RuleConfig {

    @Bean
    RuleService ruleService(
            RuleRepository ruleRepository, TemplateRepository templateRepository, ExtractionVersionRepository extractionVersionRepository) {
        return new RuleService(ruleRepository, templateRepository, extractionVersionRepository, RuleVocabulary.SCHEMA_VERSION);
    }
}
