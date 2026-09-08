package io.github.vihuynh72.brownie.api.config;

import io.github.vihuynh72.brownie.core.config.BrownieEnvironment;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Resolves {@code BROWNIE_ENVIRONMENT} before any bean is created and
 * activates the matching Spring profile. Fails fast, with the message from
 * {@link BrownieEnvironment#fromValue(String)}, when the value is missing
 * or not one of the validated environments.
 */
public class BrownieEnvironmentListener
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    static final String ENVIRONMENT_PROPERTY = "BROWNIE_ENVIRONMENT";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        resolveAndActivate(event.getEnvironment());
    }

    BrownieEnvironment resolveAndActivate(ConfigurableEnvironment environment) {
        BrownieEnvironment resolved = BrownieEnvironment.fromValue(environment.getProperty(ENVIRONMENT_PROPERTY));
        environment.setActiveProfiles(resolved.configValue());
        return resolved;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
