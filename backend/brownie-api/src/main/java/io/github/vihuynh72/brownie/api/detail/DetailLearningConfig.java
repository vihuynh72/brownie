package io.github.vihuynh72.brownie.api.detail;

import io.github.vihuynh72.brownie.ai.detail.JacksonLearningReplyParser;
import io.github.vihuynh72.brownie.core.batch.BatchRepository;
import io.github.vihuynh72.brownie.core.detail.DetailLearningRepository;
import io.github.vihuynh72.brownie.core.detail.DetailLearningService;
import io.github.vihuynh72.brownie.core.detail.LearningReplyParser;
import io.github.vihuynh72.brownie.core.detail.SavedDetailRepository;
import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.revision.DocumentRepository;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The auto-learn-after-approval wiring: the model call follows the same ledger path a naming call does. */
@Configuration
class DetailLearningConfig {

    @Bean
    LearningReplyParser learningReplyParser() {
        return new JacksonLearningReplyParser();
    }

    @Bean
    DetailLearningService detailLearningService(
            DocumentRepository documentRepository,
            TemplateService templateService,
            SavedDetailRepository savedDetailRepository,
            DetailLearningRepository detailLearningRepository,
            BatchRepository batchRepository,
            ModelGateway modelGateway,
            MemberUsageRepository usageRepository,
            ModelPricing modelPricing,
            UsageLimits directRequestLimits,
            MonthlyUsageLimits monthlyUsageLimits,
            @Value("${brownie.ai.openai.model}") String modelName,
            LearningReplyParser learningReplyParser) {
        return new DetailLearningService(
                documentRepository, templateService, savedDetailRepository, detailLearningRepository, batchRepository,
                modelGateway, usageRepository, modelPricing, directRequestLimits, monthlyUsageLimits, modelName,
                learningReplyParser);
    }
}
