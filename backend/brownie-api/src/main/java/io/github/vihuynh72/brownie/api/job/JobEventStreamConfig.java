package io.github.vihuynh72.brownie.api.job;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
class JobEventStreamConfig {

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService jobEventStreamExecutor() {
        return Executors.newScheduledThreadPool(
                2,
                Thread.ofPlatform().name("brownie-event-stream-", 0).factory());
    }
}
