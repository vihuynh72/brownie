package io.github.vihuynh72.brownie.worker;

import io.github.vihuynh72.brownie.worker.config.BrownieEnvironmentListener;
import io.github.vihuynh72.brownie.storage.azure.AzureBlobStorageConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@Import(AzureBlobStorageConfig.class)
@EnableScheduling
public class BrownieWorkerApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BrownieWorkerApplication.class);
        application.addListeners(new BrownieEnvironmentListener());
        application.run(args);
    }
}
