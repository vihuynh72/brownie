package io.github.vihuynh72.brownie.api.job;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
class JobConfig {

    @Bean
    CanonicalRequestHasher canonicalRequestHasher(ObjectMapper objectMapper) {
        return new CanonicalRequestHasher(objectMapper);
    }
}
