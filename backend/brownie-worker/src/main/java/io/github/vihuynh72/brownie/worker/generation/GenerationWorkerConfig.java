package io.github.vihuynh72.brownie.worker.generation;

import io.github.vihuynh72.brownie.core.job.WorkerId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.management.ManagementFactory;

@Configuration
class GenerationWorkerConfig {

    /**
     * A stable identity for this one running process -- {@code
     * "pid@hostname"}, the JVM's own runtime name, is unique enough to
     * tell two worker instances apart in a lease row without requiring a
     * new configuration value nobody has had to set until now.
     */
    @Bean
    WorkerId workerId() {
        return new WorkerId(ManagementFactory.getRuntimeMXBean().getName());
    }
}
