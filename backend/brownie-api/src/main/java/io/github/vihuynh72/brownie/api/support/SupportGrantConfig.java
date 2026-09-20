package io.github.vihuynh72.brownie.api.support;

import io.github.vihuynh72.brownie.core.support.SupportGrantRepository;
import io.github.vihuynh72.brownie.core.support.SupportGrantService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SupportGrantConfig {

    @Bean
    SupportGrantService supportGrantService(SupportGrantRepository supportGrantRepository) {
        return new SupportGrantService(supportGrantRepository);
    }
}
