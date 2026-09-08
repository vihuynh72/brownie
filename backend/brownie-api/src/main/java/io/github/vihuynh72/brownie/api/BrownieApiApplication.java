package io.github.vihuynh72.brownie.api;

import io.github.vihuynh72.brownie.api.config.BrownieEnvironmentListener;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BrownieApiApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BrownieApiApplication.class);
        application.addListeners(new BrownieEnvironmentListener());
        application.run(args);
    }
}
