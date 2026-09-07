package io.github.vihuynh72.brownie.worker;

import io.github.vihuynh72.brownie.worker.config.BrownieEnvironmentListener;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BrownieWorkerApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BrownieWorkerApplication.class);
        application.addListeners(new BrownieEnvironmentListener());
        application.run(args);
    }
}
