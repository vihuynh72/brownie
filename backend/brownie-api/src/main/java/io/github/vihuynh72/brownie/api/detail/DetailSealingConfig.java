package io.github.vihuynh72.brownie.api.detail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The sealing key for saved details: absent, the API still comes up and
 * only sealing refuses; malformed, startup fails the way the MCP
 * token-key check does, rather than sealing badly or not at all.
 */
@Configuration
public class DetailSealingConfig {

    @Bean
    DetailSealer detailSealer(@Value("${brownie.details.key:}") String key) {
        return DetailSealer.fromBase64(key).orElseGet(DetailSealer::absent);
    }
}
