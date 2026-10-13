package io.github.vihuynh72.brownie.api.batch;

import io.github.vihuynh72.brownie.api.document.table.PoiXlsxTableReader;
import io.github.vihuynh72.brownie.api.generation.FillSpotNamingSetting;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.batch.BatchComposeReplyParser;
import io.github.vihuynh72.brownie.core.batch.BatchComposer;
import io.github.vihuynh72.brownie.core.batch.BatchRepository;
import io.github.vihuynh72.brownie.core.batch.BatchService;
import io.github.vihuynh72.brownie.core.batch.CsvTableParser;
import io.github.vihuynh72.brownie.core.batch.XlsxTableReader;
import io.github.vihuynh72.brownie.core.export.ExportService;
import io.github.vihuynh72.brownie.core.generation.TransportRetryPolicy;
import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.validation.ValidationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the batch service, its table readers and the paid compose path.
 * {@code brownie.batch.max-rows} bounds the rows a batch may hold and is
 * refused at startup outside 1..2000: below one, no batch could ever be
 * made; past two thousand, a single request could be asked to hold more
 * table than a request should carry. {@code brownie.batch.compose-offered}
 * is only half the compose switch -- the model the rows would pay must
 * also be enabled, so the capability a person sees is the conjunction of
 * the two.
 */
@Configuration
class BatchConfig {

    static final int MIN_MAX_ROWS = 1;
    static final int MAX_MAX_ROWS = 2_000;

    @Bean
    CsvTableParser csvTableParser() {
        return new CsvTableParser();
    }

    @Bean
    XlsxTableReader xlsxTableReader() {
        return new PoiXlsxTableReader();
    }

    @Bean
    BatchComposer batchComposer(
            ModelGateway modelGateway,
            BatchComposeReplyParser batchComposeReplyParser,
            MemberUsageRepository usageRepository,
            UsageLimits directRequestLimits,
            MonthlyUsageLimits monthlyUsageLimits,
            ModelPricing modelPricing,
            @Value("${brownie.ai.openai.model}") String modelName) {
        return new BatchComposer(
                modelGateway, batchComposeReplyParser, usageRepository, directRequestLimits, monthlyUsageLimits,
                modelPricing, modelName, TransportRetryPolicy.standard());
    }

    @Bean
    BatchService batchService(
            BatchRepository batchRepository,
            TemplateRepository templateRepository,
            ArtifactService artifactService,
            RevisionService revisionService,
            XlsxTableReader xlsxTableReader,
            CsvTableParser csvTableParser,
            RuleRepository ruleRepository,
            ValidationService validationService,
            ExportService exportService,
            BatchComposer batchComposer,
            FillSpotNamingSetting fillSpotNamingSetting,
            @Value("${brownie.batch.max-rows:500}") int maxRows,
            @Value("${brownie.batch.compose-offered:false}") boolean composeOffered) {
        if (maxRows < MIN_MAX_ROWS || maxRows > MAX_MAX_ROWS) {
            throw new IllegalStateException(
                    "brownie.batch.max-rows must be between " + MIN_MAX_ROWS + " and " + MAX_MAX_ROWS
                            + "; got " + maxRows + ".");
        }
        return new BatchService(
                batchRepository,
                templateRepository,
                artifactService,
                revisionService,
                xlsxTableReader,
                csvTableParser,
                ruleRepository,
                validationService,
                exportService,
                batchComposer,
                maxRows,
                composeOffered && fillSpotNamingSetting.usesModel());
    }
}
