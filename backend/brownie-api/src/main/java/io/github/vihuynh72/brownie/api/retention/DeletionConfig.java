package io.github.vihuynh72.brownie.api.retention;

import io.github.vihuynh72.brownie.core.retention.DeletionRepository;
import io.github.vihuynh72.brownie.core.retention.DeletionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DeletionConfig {

    /**
     * How long something stays in the trash is a product setting, not a
     * constant: the service refuses a value outside its bounds, so a bad
     * setting stops the application at startup instead of failing the
     * first person who deletes something.
     */
    @Bean
    DeletionService deletionService(
            DeletionRepository deletionRepository,
            @Value("${brownie.retention.trash-days:30}") int trashRetentionDays) {
        return new DeletionService(deletionRepository, trashRetentionDays);
    }
}
